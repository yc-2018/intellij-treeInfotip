# 路径前缀的抽离与还原（6.0.0 起）

> 本文从 CLAUDE.md 抽离。**改 `PathPrefixes` / `PathPrefixEditor` / `XmlPrefixDialog`、或碰配置文件改名逻辑时必读。**

## 配置文件名 V3 / V6

文件名 6.0.0 之前叫 `DirectoryV3.xml`。**改名不在启动时做**——没抽离过的文件旧版读得好好的，开个 IDE 就把用户项目里一个会进版本库的文件改名太唐突。改名只在用户点了「抽离」并**保存**时才由 `XmlFileUtils.renameConfigFile` 做（还原时选「换回旧名字」则是反方向），改完发一条通知。`findConfigFile` 读取时 V6 优先、退回 V3，所以两个名字都认，没抽离过的项目一直叫 V3 也没问题。改名的原因和「文件名跟着内容走」这条不变量见本文后面关于 `doSave` 的那几条。

## 前缀表

重复的长目录可以提到一张前缀表里只写一遍：

```xml
<trees>
    <prefixes>
        <prefix id="recruitReg" path="/src/pages/carrierManagement/CarrierRecruitReg"/>
    </prefixes>
    <tree prefix="recruitReg" path="/index.tsx" title="招募报名审核工作台"/>
    <tree prefix="recruitReg" path="" title="报名招募数据管理"/>
</trees>
```

**运行时语义完全不变**：`XmlStorage.parsing` 分两趟走，先 `readPrefixes` 收齐前缀表，再解析 `<tree>` 时把 `prefix` 展开成完整路径，**内存里的 `XmlEntity.path` 一律是完整路径**。所以匹配、右键菜单、侧边栏那些地方根本不知道有前缀这回事，也就不用跟着改。`readPrefixes` 不靠遍历顺序，`<prefixes>` 写在文件末尾照样能用。

代码分两半：`PathPrefixes` 是纯算法（只碰字符串、无平台依赖，可以照 CLAUDE.md 说的抄成单文件 Java 跑断言），`PathPrefixEditor` 负责改 XML。

算法这边有两个反直觉的点，改之前先看清楚：

- **`stripPrefix` 必须卡在 `/` 上**，不能用裸的 `startsWith`。真实数据里 `/a/CarrierRecruit` 和 `/a/CarrierRecruitReg` 就是字符串前缀关系，裸比会把后者的条目全归到前者名下，路径当场错掉。返回值三态：剩下那截 / 空串（正好等于前缀本身）/ `null`（不在前缀下面）。
- **id 要掐掉和父目录重复的头部**（`CarrierRecruit` 挂在 `carrierManagement` 下，留 `recruit`）。这不是为了好看：id 每条规则都要写一遍，用全名 `carrierRecruit` 时这批 4 条规则的净收益是 **-3**，刚好抽不出来；掐头后变成 +32。判据是净收益为正：`条数 × (前缀长 - id长 - 属性开销) - 声明开销`，这个式子自动把 `/src` 这类短前缀滤掉。

改 XML 那边：

- **文本进文本出，中间借一个 `createFileFromText` 建的内存 `XmlFile` 改**，不写正则——属性值里出现 `<` `>` 时正则会切错，而 PSI 改完 `getText()` 拿回来，没动到的地方（包括模板那段参数说明注释和原有缩进）全都原样保留。内存文件不碰磁盘，所以弹窗里可以先抽给用户看，他点「保存」才落盘。
- **抽离必须在同一棵 PSI 上先 `flatten` 再重算，只 `reformat` 一次**。写成「先 `restore` 成文本、再解析一遍来抽」会 reformat 两次，空白会漂，**重复点「抽离」得到的结果就不一致**（实测踩过）。
- 插入 `<prefixes>` 用 `XmlElementFactory.createTagFromText` 拼带换行的文本，光靠 `setAttribute` 建出来的会全挤在一行；插进去之后还要 `CodeStyleManager.reformat(rootTag)` 收拾，否则它和后面第一条 `<tree>` 会挤在同一行。还原时同样 reformat 一次，来回切格式才稳定。
- 写回时 `XmlStorage.setPathAttribute` 会按标签上的 `prefix` 减掉前缀再写。**内存里是完整路径，直接写回带 `prefix` 的标签会变成「前缀 + 完整路径」**；减不掉时（用户手改了 path、或者 id 拼错）就摘掉 `prefix` 属性、写完整路径，宁可这条不省字符也不能写出错路径。
- `XmlStorage.remove` 的路径比对也要用展开后的完整路径，否则抽离过的文件里规则删不掉。

入口是侧边栏「目录备注」工具栏上的「抽离或还原路径前缀」，开 `XmlPrefixDialog`，四个按钮：抽离 | 还原 | 清理前缀 | 保存。前三个**只改弹窗里的文本、不落盘**，所以「还原」和「关掉不保存」是两道后悔门。

- **文件名跟着内容走，改名一律推迟到 `doSave`**（6.1.0 起）。抽离时不改名、还原时也不改名，只在保存那一刻按刚写进去的内容决定：内容抽离过而文件还叫 V3 就改成 V6；内容没抽离过且用户在「还原」时选了换回去（`renameToLegacyOnSave` 标记）就改成 V3。**顺序是先写内容再改名**——反过来的话写文件那步拿到的还是老的 `VirtualFile`，中途失败会留下一个名字对内容不对的文件。这条不变量的好处是：只要文件叫 V6，里面就确实抽离过、旧版读不了；中途反悔了文件名也没被动过。
- **「抽离」的确认只在真要改名时弹**（默认焦点给「取消」）。判据就是 `XmlFileUtils.isOnLegacyName(project)`：本来就叫 V6 的、或者已经抽过再点一次的（按当前配置重算）都没有兼容性变化，再弹一次只是噪音。
- **「还原」是三选项**：换回 V3 / 保留 V6 / 取消（默认焦点给「取消」，`Messages.showDialog` 关窗返回 -1 要一并当取消处理）。只用本插件的人换回去反而多一次改名，所以不替他决定。文件本来就叫 V3 时不问，没有可换的。
- **手改过的 prefix id 要留着**。`PathPrefixes.plan(paths, keepIds)` 的 `keepIds` 是「前缀路径 → id」，由 `PathPrefixEditor.extract` 在 **`flatten` 之前**从 `readPrefixes` 反转得到——`flatten` 会把 `<prefixes>` 整段删掉，之后再读就什么都没有了。用户把 `carrier5` 改成 `承运商基础包` 是有意为之，重新抽离打回自动名等于白改。
- **前缀体检和清理**：`PathPrefixEditor.diagnose` 给每条声明返回一个 `PrefixIssue`（id、路径、**在文本里的起止偏移量**、被引用次数、路径是否失效），弹窗拿偏移量直接喂 `Highlighter.addHighlight`，红色是失效、黄色是没人引用。失效的前缀在别处看不出来——「目录备注」列表标红的是规则，而一条前缀失效意味着底下一批规则**一起**失效，光看列表不知道根因在前缀上。`cleanPrefixes` 里**失效的要连引用它的规则一起删**，只删声明会让那些规则剩下半截相对路径、变成指向别处的错规则，比留着失效的更糟；没人引用的只删声明。
- **偏移量能直接用的前提是行分隔符已经归一**。`loadFromDisk` 要 `StringUtil.convertLineSeparators`：文本框里是 `\r\n`、`diagnose` 解析出来的偏移量按 `\n` 数，高亮会整体错位（`XML_TEMPLATE` 写盘用的就是 `\r\n`）。`doSave` 本来也要转，提前转等于对齐。

**「为什么这两条没抽出来」是高频疑问**，判据在 `PathPrefixes.gain`：`条数 × (前缀长 - id长 - 10) - (前缀长 + id长 + 25)`，**没有「几条起步」的门槛**。实测 `.../datax/executor/controller`（62 字符）下面 2 条规则净收益是 -16、抽不出来，而 72 字符的前缀 2 条就够本。这个解释在三处都要有：`HelpView.prefixes()`、`XmlFileUtils.XML_TEMPLATE` 的注释、以及 `plan` 的 javadoc。

