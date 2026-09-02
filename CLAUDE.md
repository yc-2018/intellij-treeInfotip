# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

沟通、注释与文档一律用中文。

## 项目概览

TreeInfotip 是一个 IntelliJ 平台插件，给项目目录树的节点加备注、颜色、图标、悬浮提示、删除线和自定义显示名。所有配置都存在**项目根目录的 `DirectoryV3.xml`** 里，不用 IDE 的持久化设置。

## 构建命令

仓库里**没有提交 `gradle/wrapper/gradle-wrapper.jar`**（只有一个孤立的 `gradle-wrapper.properties`），所以 `./gradlew` 用不了，必须直接调本机 Gradle：

```bash
JAVA_HOME="D:/green/jdks/jdk-17.0.8" /d/green/Gradle/dists/gradle-7.6.4/bin/gradle <任务> --no-daemon --offline
```

- `--offline` 是必要的：联网时 gradle-intellij-plugin 会去 GitHub 查最新版本，国内网络下抛 `getHeaderField("Location") must not be null`（不致命，但很吵）。
- 必须用 JDK 17，gradle-intellij-plugin 1.13.2 + 平台 2022.3 在 JDK 8 上跑不起来。
- **换机器或清了缓存后，`--offline` 会先失败一次**：Kotlin 编译器自己的 classpath（`kotlin-gradle-plugin`、`kotlin-script-runtime` 等）不在缓存里就没法离线解析。去掉 `--offline` 跑一次 `compileJava` 把它们拉下来（首次约 4 分钟），之后就一直能离线。本机 `GRADLE_USER_HOME` 是 `D:\green\Gradle\repository`，不是默认的 `~/.gradle`。

常用任务：

| 任务 | 用途 |
|---|---|
| `compileJava` | 只编译，最快的语法校验 |
| `buildPlugin` | 打包，产物在 `build/distributions/TreeInfotip-<版本>.zip` |
| `verifyPlugin` | 校验 plugin.xml 配置 |
| `runIde` | 起沙箱 IDE 实测（沙箱目录是仓库根的 `idea-sandbox/`） |
| `runPluginVerifier -PverifierIdeVersions=IU-2022.3.2,IU-2026.2` | 跨版本兼容性检查 |

**没有测试代码**：`src/test` 目录不存在，`gradle test` 会通过但什么都没跑。要验证纯算法逻辑（比如路径匹配优先级），可以把逻辑抄成临时的单文件 Java，用 `java Xxx.java` 跑断言，跑完删掉。

改完代码**必须 `runIde` 实测**。`plugin.xml` 里 action 注册写错、或者引用了新版本已删除的 `AllIcons` 字段，都是启动期抛异常（历史事故：`AllIcons.Actions.Menu_paste` 在 2026.2 被移除，右键菜单整个不可用）。编译通过说明不了任何问题；另外目录树只在**重绘时**才会应用新样式。

## 源码布局与 Kotlin 混编

主体是 Java（`src/main/java`），5.1.1 起额外开了 `src/main/kotlin`，两边可以互相调用，`compileKotlin` 先跑、`compileJava` 后跑。**老的 `.java` 不需要动**，新代码想写 Kotlin 就直接写。

已经是 Kotlin 的：`gui/ColorsUtils.kt`、`gui/IconsUtils.kt`、`gui/entity/IconEntity.kt`（都是没有 Lombok、没有 Swing 继承的叶子类）。

混编的硬约束：

- **绝对不能把 kotlin-stdlib 打进插件**。IDE 自带一份，重复会冲突。靠两条配合实现：`gradle.properties` 里 `kotlin.stdlib.default.dependency=false`，`build.gradle` 里 stdlib 写成 `compileOnly`。打包后 `TreeInfotip/lib/` 下只应该有插件 jar、`javatuples`、`searchableOptions` 三个，出现 `kotlin-stdlib-*.jar` 就是配置漏了。
- **Kotlin 语言版本要压到最低支持 IDE 那一档**。`since-build=223` 对应 2022.3.0，它自带 Kotlin 1.7.21，所以 `apiVersion`/`languageVersion` 都锁 `"1.7"`。不锁的话用到新 stdlib 才有的函数编译期不报错，要到用户的老 IDE 上炸 `NoSuchMethodError`。
- Java 要调 Kotlin，签名得手动配：`object` 里的函数加 `@JvmStatic`，字段加 `@JvmField`，**被 Java `switch` case 标签用到的常量必须是 `const val`**（`ColorsUtils.COLOR_TEXT_COLOR_NAME` 就是，写成 `@JvmField val` 直接编译不过）。


**只要改动了功能代码，就必须升一个新版本、本地打包、推送远端仓库。** 纯文档、注释、CI 配置的改动不需要升版本。

版本号按改动大小定：

- **补丁位**（5.0.3 → 5.0.4）：bug 修复、文案调整、小的行为修正
- **次版本位**（5.0.4 → 5.1.0）：新增菜单项、新增匹配方式等新功能
- **主版本位**（5.x → 6.0.0）：`DirectoryV3.xml` 格式不兼容、需要用户重新配置的大重构

每次发版按顺序做完：

1. 改 `gradle.properties` 的 `pluginVersion`。这是**唯一版本来源**，`build.gradle` 的 `version` 和打包产物名都由它驱动。
2. 同步 `src/main/resources/META-INF/plugin.xml` 的 `<version>`。打包时 `patchPluginXml` 会覆盖它，但源码里也要一致，免得看着对不上。
3. 在 `plugin.xml` 的 `<change-notes>` 顶部加本版条目，中英文各一份，沿用现有格式。
4. 本地跑 `buildPlugin`，然后解包核对 jar 里的 `plugin.xml` 版本号、change-notes 和新增的 class 都在。
5. 删掉 `build/distributions/` 下的旧版本 zip，避免混淆。
6. 提交并 `git push origin master`。

要发 GitHub Release 就推 tag：`git tag v5.0.4 && git push origin v5.0.4`。`release.yml` 会校验 tag 去掉 `v` 之后必须等于 `pluginVersion`，不一致直接失败。上传 JetBrains Marketplace 需要 `JETBRAINS_MARKETPLACE_TOKEN` 环境变量，仓库里不存 token。

## 架构

### 数据流

```
DirectoryV3.xml（项目根目录）
   ↓ PluginStartupActivity.runActivity（postStartupActivity）
   ↓ XmlFileUtils.loadXmlFile → XmlStorage.parsing
XmlStorage.XML_STORAGE_LIST：每个 Project 一份 List<XmlEntity> 内存缓存
   ↓ TreesUtils.getMatchPath(virtualFile, project)
   ↓ TreesStyle.setStyle(presentation, entity, name)
PresentationData：图标 / locationString / tooltip / presentableText / 文字色 / 删除线 / 背景色
```

装饰有**两个入口**，都汇到 `TreesStyle.setStyle`：`TreeOnlyTextProvider`（`treeStructureProvider`）和 `IgnoreViewNodeDecorator`（`projectViewNodeDecorator`）。所以改渲染只需要改 `TreesStyle` 一个地方。

`XmlChangeListener` 挂了 PSI 树监听，XML 一变就重新 `parsing`，手改文件也能立刻生效。

### 匹配优先级（`TreesUtils.getMatchPath`）

一条 `<tree>` 有两种形态，命中优先级从高到低：

| 规则 | 写法 | 命中范围 |
|---|---|---|
| 路径规则 | `<tree path="/a/B.java" .../>` | 路径全等的那一个文件或目录，优先级最高 |
| 目录级类型规则 | `<tree path="/src/main/java" extension="java" .../>` | 该目录及各级子目录下的 `.java`；多条同时命中时 `path` 更长的赢 |
| 全项目类型规则 | `<tree extension="java" .../>` | 整个项目的 `.java`，只做兜底 |

扩展名规则只作用于文件，目录节点不参与。路径末尾多写的 `/` 由 `trimTrailingSlash` 归一化，只写 `/` 等于整个项目。

### 右键菜单

菜单类都在 `action/` 下，注册在 `plugin.xml` 的 `StudyAction.MyGroup` 里，挂到 `ProjectViewPopupMenu`。

针对单个节点的菜单统一用 `XmlFileUtils.runActionType(event, callback)` 模板：它把选中节点转成相对路径，查缓存决定走 `onModifyPath`（已有配置）还是 `onCreatePath`（还没有）。`ActionDescriptionText` 是最标准的例子，加新菜单直接照抄。

**`runActionType` 只匹配路径规则**（见 `isPathRule`）。带 `extension` 的类型规则一条管一批文件，不能被单节点菜单顺手改掉，所以 `ActionDescriptionExtension` 不走这个模板，自己读 `VIRTUAL_FILE_ARRAY`；也因此**类型规则的删除入口只在它自己内部**——「清除全部设置」是按路径匹配的，碰不到类型规则。

### 写 XML

所有属性写入都走 `XmlStorage.setAttributeIfNotEmpty`：值为空就把已存在的属性删掉（`XmlTag.setAttribute(name, null)` 是删除语义）。`XmlStorage.tree()` 解析时会把缺失属性归一成 `""`，直接回写就会在文件里堆出 `extension="" icon=""` 这类噪音，所以不要绕过这个方法。

新增一个可配置属性要同时改四处：`XmlEntity` 加字段、`XmlStorage` 加常量并在 `tree()` / `modify()` / `create()` 三处登记、`TreesStyle.setStyle` 应用到 `PresentationData`、最后加对应 action 并在 `plugin.xml` 注册。

### 回调注册表

`TreesStyle.ListenerStyle`、`XmlFileUtils.ListenerSave`、`PluginStartupActivity.ListenerRun` 都是 `ConcurrentHashMap<Object, Callback>` 静态注册表，用于配置变化时刷新工具窗口。它们**只 put 从不 remove**，key 一般传监听方自己的实例。

## 已知约束

- `plugin.xml` 声明 `since-build="223"`，和编译平台 2022.3.2（`gradle.properties` 的 `platformVersion`）对齐，所以下限不再是「谎报」。但**上限没有**：`build.gradle` 里 `updateSinceUntilBuild = false` 是刻意的，不能写 `until-build`，否则新版 IDE 装不上。代价是新 IDE 删掉的 API 只能在运行期暴露——反射拿到的 `AllIcons` 字段名就可能凭空消失（历史事故见上）。
- `sourceCompatibility = targetCompatibility = 17`。**不要提到 21**：`platformVersion=2022.3.2` 自带的 JBR 是 17，21 的字节码在 `runIde` 沙箱和用户机器上都加载不了；真要上 21 得先把 `platformVersion` 拉到 2025.x，那会一并撞上 `ContentFactory.SERVICE` 这类已标记删除的 API。
- `ContentFactory.SERVICE.getInstance()` 已废弃（`NoteTreeView`、`XmlEditorToolWindow` 各一处），为向下兼容刻意保留，编译告警可以忽略。
- 图标下拉框没有「不设置」选项（`IconsUtils.ICONS` 纯反射 `AllIcons` 生成），所以颜色/图标对话框一点确定就必然写入一个 `icon` 属性。这是既有行为。
- `XmlEditorToolWindow` 的「抽离路径前缀」还是空的 TODO；「格式化」和「清理空属性」是正则实现，属性值里出现 `<` / `>` 时不安全。
- `verifyPluginConfiguration` 会报 "The Kotlin configuration specifies apiVersion=1.7 but since-build='223' property requires apiVersion=1.7.0"，这是它按字符串比对的**误报**：真填 `1.7.0`，Kotlin 编译器直接报 `Unknown Kotlin version: 1.7.0`。这条告警忽略即可，`build.gradle` 里也写了注释。
- `XmlEntity` 是全项目唯一用 Lombok 的类（`@Data @Accessors(chain = true)`），**不要顺手改成 Kotlin**：Kotlin 的 `var` 生成 void setter，和同名的链式 `setXxx(): XmlEntity` 是 platform declaration clash，没法共存。要么照抄链式 setter（比 Lombok 还长），要么改掉 14 处调用点（`ActionDescriptionExtension` 和 `XmlStorage` 里都有一长串链式调用）。所以 Lombok 目前拿不掉。
