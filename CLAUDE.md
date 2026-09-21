# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

沟通、注释与文档一律用中文。

## 项目概览

TreeInfoTip Notes 是一个 IntelliJ 平台插件，给项目目录树的节点加备注、颜色、图标、悬浮提示、删除线和自定义显示名。所有配置都存在**项目根目录的 `DirectoryV6.xml`** 里，不用 IDE 的持久化设置。

配置文件名 V6 / V3 两个都认（读取时 V6 优先、退回 V3）。**改名不在启动时做**，只在用户点「抽离」并保存的那一刻发生——细节和「文件名跟着内容走」这条不变量见 `docs/path-prefix.md`。

## 分册文档：先看这张表

细节按主题拆在 `docs/` 下。这些文件**不会自动进上下文**，动手前按下表自己 Read，不要凭印象改：

| 文件 | 什么时候必须读 |
|---|---|
| `docs/platform-constraints.md` | **改任何 Swing / PSI / 线程相关代码之前**。读锁、导航、图标、跨版本 API 差异，全是踩过的坑和历史事故 |
| `docs/build-and-release.md` | 要编译打包、跑 `runPluginVerifier`；动 Kotlin 代码；**发新版本**（版本号规则和 6 步流程在里面） |
| `docs/path-prefix.md` | 改 `PathPrefixes` / `PathPrefixEditor` / `XmlPrefixDialog`，或碰配置文件改名 |
| `docs/tool-window.md` | 改 `NoteTreeView` / `MemberTreeView` / `HelpView` / `PsiCommentUtils` |
| `docs/publishing.md` | 改 `plugin.xml` 的 id / name / description，或要上传 Marketplace |

维护这份文档时**不要用 `@docs/xxx.md` 这种 import 写法**引用它们：那个语法会把整个文件递归展开进上下文，拆分就白做了。保持普通的路径提及。

## 三条红线

1. **改完代码必须 `runIde` 实测。** `plugin.xml` 里 action 注册写错、或者引用了新版本已删除的 `AllIcons` 字段，都是启动期抛异常（历史事故：`AllIcons.Actions.Menu_paste` 在 2026.2 被移除，右键菜单整个不可用）。编译通过说明不了任何问题；另外目录树只在**重绘时**才会应用新样式。本机沙箱目前起不来的原因见 `docs/platform-constraints.md` 末尾。
2. **只要改动了功能代码，就必须升一个新版本、本地打包、推送远端仓库。** 纯文档、注释、CI 配置的改动不需要升版本。版本号规则和发版步骤见 `docs/build-and-release.md`。
3. **没有测试代码。** `src/test` 目录不存在，`gradle test` 会通过但什么都没跑。要验证纯算法逻辑（比如路径匹配优先级），可以把逻辑抄成临时的单文件 Java，用 `java Xxx.java` 跑断言，跑完删掉。

## 构建命令

仓库里**没有提交 `gradle/wrapper/gradle-wrapper.jar`**，所以 `./gradlew` 用不了，必须直接调本机 Gradle：

```bash
JAVA_HOME="D:/green/jdks/jdk-17.0.8" /d/green/Gradle/dists/gradle-7.6.4/bin/gradle <任务> --no-daemon --offline
```

- `--offline` 是必要的：联网时 gradle-intellij-plugin 会去 GitHub 查最新版本，国内网络下抛 `getHeaderField("Location") must not be null`。
- 必须用 JDK 17。本机 `GRADLE_USER_HOME` 是 `D:\green\Gradle\repository`，不是默认的 `~/.gradle`。

| 任务 | 用途 |
|---|---|
| `compileJava` | 只编译，最快的语法校验 |
| `buildPlugin` | 打包，产物在 `build/distributions/TreeInfoTip-Notes-<版本>.zip` |
| `verifyPlugin` | 校验 plugin.xml 配置 |
| `runIde` | 起沙箱 IDE 实测（沙箱目录是仓库根的 `idea-sandbox/`） |
| `runPluginVerifier` | 跨版本兼容性检查，**参数很多且不能加 `--offline`**，照 `docs/build-and-release.md` 来 |

换机器或清了缓存后 `--offline` 会先失败一次，处理办法同样在 `docs/build-and-release.md`。

## 架构

### 数据流

```
DirectoryV6.xml（项目根目录）
   ↓ PluginStartupActivity.runActivity（postStartupActivity）
   ↓ XmlFileUtils.loadXmlFile → XmlStorage.parsing（这一步把 prefix 展开成完整路径）
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

菜单类都在 `action/` 下，注册在 `plugin.xml` 的 `TreeInfotip.MenuGroup` 里，挂到 `ProjectViewPopupMenu`。

针对单个节点的菜单统一用 `XmlFileUtils.runActionType(event, callback)` 模板：它把选中节点转成相对路径，查缓存决定走 `onModifyPath`（已有配置）还是 `onCreatePath`（还没有）。`ActionDescriptionText` 是最标准的例子，加新菜单直接照抄。

**`runActionType` 只匹配路径规则**（见 `isPathRule`）。带 `extension` 的类型规则一条管一批文件，不能被单节点菜单顺手改掉，所以 `ActionDescriptionExtension` 不走这个模板，自己读 `VIRTUAL_FILE_ARRAY`；也因此**类型规则的删除入口只在它自己内部**——「清除全部设置」是按路径匹配的，碰不到类型规则。

### 写 XML

所有属性写入都走 `XmlStorage.setAttributeIfNotEmpty`：值为空就把已存在的属性删掉（`XmlTag.setAttribute(name, null)` 是删除语义）。`XmlStorage.tree()` 解析时会把缺失属性归一成 `""`，直接回写就会在文件里堆出 `extension="" icon=""` 这类噪音，所以不要绕过这个方法。

新增一个可配置属性要同时改四处：`XmlEntity` 加字段、`XmlStorage` 加常量并在 `tree()` / `modify()` / `create()` 三处登记、`TreesStyle.setStyle` 应用到 `PresentationData`、最后加对应 action 并在 `plugin.xml` 注册。**还有第五处**：`XmlFileUtils.XML_TEMPLATE` 的注释和 `HelpView.attributes()` 的参数列表都要补一条，否则用户看到的说明会缺项。

新项目第一次加备注时由 `XmlFileUtils.createXmlFile` 写出 `XML_TEMPLATE`，`<trees>` 下面带一段注释列全参数和命中优先级（5.6.0 起，之前是个空 `<trees/>`）——这文件躺在项目根目录，用户迟早点开它，而 `presentableText`、`tooltipTitle` 这些参数名单看名字猜不全。6.0.0 之前这段注释还得避开「格式化」按钮的正则（`<trees>`、带空格的 `<tree `、行尾 `>` 接行首 `<`），那个按钮已经随 XML 工具窗口一起去掉了，这条约束不再适用；**XML 注释里不能出现连续两个减号**这条仍然有效。

### 回调注册表

`TreesStyle.ListenerStyle`、`XmlFileUtils.ListenerSave`、`PluginStartupActivity.ListenerRun` 都是 `ConcurrentHashMap<Object, Callback>` 静态注册表，用于配置变化时刷新工具窗口。它们**只 put 从不 remove**，key 一般传监听方自己的实例。
