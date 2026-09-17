package com.plugins.infotip.storage;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A <code>XmlStorage</code> Class
 *
 * @author lk
 * @version 1.0
 * <p><b>date: 2023/4/13 13:17</b></p>
 */
public class XmlStorage {

    //region 节点常量
    final static String TREES = "trees";

    final static String TREE = "tree";

    final static String PATH = "path";

    private final static String TITLE = "title";

    private final static String EXTENSION = "extension";

    private final static String PRESENTABLE_TEXT = "presentableText";

    private final static String TOOLTIP_TITLE = "tooltipTitle";

    private final static String ICON = "icon";

    private final static String TEXT_COLOR = "textColor";

    private final static String BACKGROUND_COLOR = "backgroundColor";

    private final static String STRIKETHROUGH = "strikethrough";

    /**
     * 前缀表的容器标签，{@code <trees>} 的直接子节点
     */
    final static String PREFIXES = "prefixes";

    /**
     * 前缀表里的一条声明：{@code <prefix id="x" path="/a/b"/>}
     */
    final static String PREFIX = "prefix";

    /**
     * 前缀声明的 id，同时也是 {@code <tree>} 上引用它的属性名
     */
    final static String PREFIX_ID = "id";
    //endregion 节点常量

    private final static ConcurrentHashMap<Project, CopyOnWriteArrayList<XmlEntity>> XML_STORAGE_LIST = new ConcurrentHashMap<Project, CopyOnWriteArrayList<XmlEntity>>();


    /**
     * 解析XML
     *
     * <p>
     * 分两趟走：先把 {@code <prefixes>} 里的前缀表收齐，再解析 {@code <tree>} 并把
     * {@code prefix="id"} 展开成完整路径。<b>内存里存的一律是完整路径</b>，所以匹配、右键菜单、
     * 侧边栏那些地方完全不知道前缀这回事，也就不用跟着改。
     * </p>
     * <p>
     * 不靠遍历顺序：{@code <prefixes>} 写在文件末尾照样能用。
     * </p>
     *
     * @param project 项目
     * @param xmlFile xml文件
     */
    public static synchronized void parsing(Project project, XmlFile xmlFile) {
        if (null == xmlFile) {
            return;
        }
        XML_STORAGE_LIST.remove(project);
        final CopyOnWriteArrayList<XmlEntity> xmlEntities_clone = new CopyOnWriteArrayList<>();
        XML_STORAGE_LIST.put(project, xmlEntities_clone);
        final String presentableUrl = project.getPresentableUrl();
        if (presentableUrl == null) {
            return;
        }
        final Map<String, String> prefixes = readPrefixes(xmlFile);
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitElement(final @NotNull PsiElement element) {
                super.visitElement(element);
                if (element instanceof XmlTag) {
                    //针对节点执行不同的解析方案
                    XmlTag tag = (XmlTag) element;
                    if (TREE.equals(tag.getName())) {
                        XmlEntity tree = tree(tag, prefixes);
                        if (null != tree) {
                            xmlEntities_clone.add(tree);
                        }
                    }
                }
            }
        });
    }

    /**
     * 读出前缀表：id 到完整路径
     *
     * <p>
     * 同一个 id 声明了两次时按先到先得，和 {@code <tree>} 同优先级先到先得是一个道理。
     * 没有 {@code <prefixes>} 段（没抽离过的文件、以及老的 V3）就返回空表，后面一切照旧。
     * </p>
     *
     * @param xmlFile 配置文件
     * @return 不会返回 null
     */
    static Map<String, String> readPrefixes(XmlFile xmlFile) {
        final Map<String, String> prefixes = new LinkedHashMap<>();
        final XmlDocument document = xmlFile.getDocument();
        if (null == document) {
            return prefixes;
        }
        final XmlTag rootTag = document.getRootTag();
        if (null == rootTag || !TREES.equals(rootTag.getName())) {
            return prefixes;
        }
        for (XmlTag holder : rootTag.findSubTags(PREFIXES)) {
            for (XmlTag prefix : holder.findSubTags(PREFIX)) {
                final String id = trimToEmpty(prefix.getAttributeValue(PREFIX_ID));
                final String path = trimToEmpty(prefix.getAttributeValue(PATH));
                if (!id.isEmpty() && !path.isEmpty() && !prefixes.containsKey(id)) {
                    prefixes.put(id, path);
                }
            }
        }
        return prefixes;
    }


    public static void clear(Project project) {
        final List<XmlEntity> xmlEntities = XML_STORAGE_LIST.get(project);
        if (null != xmlEntities) {
            xmlEntities.clear();
        }
    }

    public static List<XmlEntity> getXmlEntity(Project project) {
        return XML_STORAGE_LIST.get(project);
    }

    /**
     * 修改路径
     *
     * @param project   项目
     * @param xmlEntity 目录对象
     */
    public static synchronized void modify(Project project, XmlFile fileDirectoryXml, XmlEntity xmlEntity) {
        final XmlFile xmlFile = XmlFileUtils.getXmlFile(project);
        if (null != xmlFile && null != xmlEntity) {
            final XmlTag childTag = xmlEntity.getTag();
            if (null == childTag) {
                create(project, fileDirectoryXml, xmlEntity);
            } else {
                final Map<String, String> prefixes = readPrefixes(xmlFile);
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    setPathAttribute(childTag, xmlEntity.getPath(), prefixes);
                    setAttributeIfNotEmpty(childTag, TITLE, xmlEntity.getTitle());
                    setAttributeIfNotEmpty(childTag, EXTENSION, xmlEntity.getExtension());
                    setAttributeIfNotEmpty(childTag, PRESENTABLE_TEXT, xmlEntity.getPresentableText());
                    setAttributeIfNotEmpty(childTag, TOOLTIP_TITLE, xmlEntity.getTooltipTitle());
                    setAttributeIfNotEmpty(childTag, ICON, xmlEntity.getIcon());
                    setAttributeIfNotEmpty(childTag, TEXT_COLOR, xmlEntity.getTextColor());
                    setAttributeIfNotEmpty(childTag, BACKGROUND_COLOR, xmlEntity.getBackgroundColor());
                    setAttributeIfNotEmpty(childTag, STRIKETHROUGH, xmlEntity.isStrikethroughEnabled() ? "true" : null);
                    XmlFileUtils.saveFileXml(project);
                });
            }
        }
    }


    public static synchronized void remove(XmlFile xmlFile, Project project, XmlEntity xmlEntity) {
        XmlDocument document = xmlFile.getDocument();
        if (null == document || null == xmlEntity) {
            return;
        }
        //比对用的是展开后的完整路径：抽离过的文件里标签上写的是相对那截，
        //不展开就和 XmlEntity 里的完整路径永远对不上，规则就删不掉了
        final Map<String, String> prefixes = readPrefixes(xmlFile);
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitElement(final @NotNull PsiElement element) {
                super.visitElement(element);
                if (element instanceof XmlTag) {
                    //针对节点执行不同的解析方案
                    XmlTag tag = (XmlTag) element;
                    if (TREE.equals(tag.getName())) {
                        XmlEntity tree = tree(tag, prefixes);
                        if (null != tree) {
                            //类型规则的 path 可能为空，要连 extension 一起比，才不会误删同目录的其他规则
                            if (trimToEmpty(xmlEntity.getPath()).equals(trimToEmpty(tree.getPath()))
                                    && trimToEmpty(xmlEntity.getExtension()).equals(trimToEmpty(tree.getExtension()))) {
                                WriteCommandAction.runWriteCommandAction(project, () -> {
                                    tag.delete();
                                    XmlFileUtils.saveFileXml(project);
                                });
                            }
                        }
                    }
                }
            }
        });
    }


    /**
     * 直接按 {@link XmlEntity#getTag()} 删除若干条规则
     * <p>
     * 和 {@link #remove(XmlFile, Project, XmlEntity)} 的区别：那个要重新遍历整个文件、按
     * path + extension 找回标签，一次只能删一条，批量调用会反复解析。这里用解析时就存下的
     * 标签本体，一个写操作删完所有的，只触发一次保存和一次重新解析。
     * </p>
     *
     * @return 实际删掉的条数；标签已失效（文件被外部改过、还没重新解析）的会被跳过
     */
    public static synchronized int removeByTag(Project project, List<XmlEntity> entities) {
        if (null == entities || entities.isEmpty()) {
            return 0;
        }
        final int[] removed = {0};
        WriteCommandAction.runWriteCommandAction(project, () -> {
            for (XmlEntity entity : entities) {
                if (null == entity) {
                    continue;
                }
                final XmlTag tag = entity.getTag();
                if (null != tag && tag.isValid()) {
                    tag.delete();
                    removed[0]++;
                }
            }
            if (removed[0] > 0) {
                XmlFileUtils.saveFileXml(project);
            }
        });
        return removed[0];
    }

    /**
     * 置顶：把这些 {@code <tree>} 整批移到 {@code <trees>} 的最前面，保持它们原来的相对顺序
     * <p>
     * 顺序在 XML 里是有意义的：{@link com.plugins.infotip.trees.TreesUtils#getMatchPath} 同优先级
     * 多条命中时先遍历到的赢，侧边栏列表也按文件顺序显示。所以置顶是真的改文件，不是只改视图。
     * </p>
     * <p>
     * PSI 没有「移动子节点」，只能先在头部插一份副本再删原件。{@code addSubTag(tag, true)} 的
     * {@code true} 是插到最前，它返回的是新插进去的那个标签，原 {@code tag} 仍指向老位置，
     * 所以两步的先后顺序不能颠倒。
     * </p>
     * <p>
     * 多条一起置顶时要<b>倒着遍历</b>：每一条都插到最前面，正着走会把这批规则整体翻个面。
     * 而且必须在<b>同一个写操作</b>里做完——每条各开一次写操作会各触发一次存盘和重新解析，
     * 后面那些 {@link XmlEntity} 上存的标签就都失效了，只有第一条能成功（5.5.1 的表现是
     * 多选只置顶了一条，不过那是因为当时只取了一条）。
     * </p>
     *
     * @return 实际挪动的条数；标签已失效（文件被外部改过、还没重新解析）的会被跳过
     */
    public static synchronized int moveToTop(Project project, List<XmlEntity> entities) {
        final XmlFile xmlFile = XmlFileUtils.getXmlFile(project);
        if (null == xmlFile || null == entities || entities.isEmpty()) {
            return 0;
        }
        final XmlDocument document = xmlFile.getDocument();
        if (null == document) {
            return 0;
        }
        final XmlTag rootTag = document.getRootTag();
        if (null == rootTag || !TREES.equals(rootTag.getName())) {
            return 0;
        }
        //已经整批贴在最前面且顺序没变，就不用动，省一次写操作和一次重新解析
        if (alreadyOnTop(rootTag, entities)) {
            return 0;
        }
        final int[] moved = {0};
        WriteCommandAction.runWriteCommandAction(project, () -> {
            for (int i = entities.size() - 1; i >= 0; i--) {
                final XmlEntity entity = entities.get(i);
                if (null == entity) {
                    continue;
                }
                final XmlTag tag = entity.getTag();
                if (null != tag && tag.isValid()) {
                    rootTag.addSubTag(tag, true);
                    tag.delete();
                    moved[0]++;
                }
            }
            if (moved[0] > 0) {
                XmlFileUtils.saveFileXml(project);
            }
        });
        return moved[0];
    }

    /**
     * 这批标签是不是已经就在最前面，而且顺序和给进来的一致
     */
    private static boolean alreadyOnTop(XmlTag rootTag, List<XmlEntity> entities) {
        final XmlTag[] subTags = rootTag.getSubTags();
        if (subTags.length < entities.size()) {
            return false;
        }
        for (int i = 0; i < entities.size(); i++) {
            final XmlEntity entity = entities.get(i);
            if (null == entity || subTags[i] != entity.getTag()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 创建新标签
     *
     * @param xmlFile   xml
     * @param project   项目
     * @param xmlEntity 类型
     */
    public static synchronized void create(Project project, XmlFile xmlFile, XmlEntity xmlEntity) {
        XmlDocument document = xmlFile.getDocument();
        if (null == document) {
            return;
        }
        XmlTag rootTag = document.getRootTag();
        if (null != rootTag) {
            if (TREES.equals(rootTag.getName())) {
                XmlTag childTag = rootTag.createChildTag(TREE, rootTag.getNamespace(), null, false);
                setAttributeIfNotEmpty(childTag, PATH, xmlEntity.getPath());
                setAttributeIfNotEmpty(childTag, TITLE, xmlEntity.getTitle());
                setAttributeIfNotEmpty(childTag, EXTENSION, xmlEntity.getExtension());
                setAttributeIfNotEmpty(childTag, PRESENTABLE_TEXT, xmlEntity.getPresentableText());
                setAttributeIfNotEmpty(childTag, TOOLTIP_TITLE, xmlEntity.getTooltipTitle());
                setAttributeIfNotEmpty(childTag, ICON, xmlEntity.getIcon());
                setAttributeIfNotEmpty(childTag, TEXT_COLOR, xmlEntity.getTextColor());
                setAttributeIfNotEmpty(childTag, BACKGROUND_COLOR, xmlEntity.getBackgroundColor());
                setAttributeIfNotEmpty(childTag, STRIKETHROUGH, xmlEntity.isStrikethroughEnabled() ? "true" : null);
                WriteCommandAction.runWriteCommandAction(project, new Runnable() {
                    @Override
                    public void run() {
                        rootTag.addSubTag(childTag, false);
                        XmlFileUtils.saveFileXml(project);
                    }
                });
            }
        }
    }

    /**
     * 写入属性：值非空时写入，为空时把已存在的属性删掉。
     * <p>
     * {@link #tree(XmlTag)} 解析时会把缺失的属性归一化成 ""，若直接回写就会在文件里留下
     * extension=""、icon="" 这类无意义的空属性，因此写入前统一在这里过滤一次。
     * </p>
     */
    private static void setAttributeIfNotEmpty(XmlTag tag, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            tag.setAttribute(name, value);
        } else if (null != tag.getAttribute(name)) {
            //传 null 会移除该属性；仅在属性确实存在时调用，新建标签时不做无用操作
            tag.setAttribute(name, null);
        }
    }

    /**
     * 写 {@code path} 属性，标签引用了前缀时只写剩下那截
     *
     * <p>
     * 内存里的 {@code XmlEntity.path} 一律是完整路径，直接写回一个带 {@code prefix} 的标签
     * 会变成「前缀 + 完整路径」，路径当场就废了。所以这里要减掉前缀再写。
     * </p>
     * <p>
     * 完整路径不在原前缀底下时（用户手改了 path、或者前缀 id 拼错了）就<b>把 prefix 属性摘掉</b>、
     * 写完整路径：宁可这一条不省字符，也不能写出一条错路径。
     * </p>
     */
    private static void setPathAttribute(XmlTag tag, String fullPath, Map<String, String> prefixes) {
        final XmlAttribute prefixAttr = tag.getAttribute(PREFIX);
        if (null == prefixAttr) {
            setAttributeIfNotEmpty(tag, PATH, fullPath);
            return;
        }
        final String base = prefixes.get(trimToEmpty(prefixAttr.getValue()));
        final String rest = null == base ? null : PathPrefixes.stripPrefix(fullPath, base);
        if (null == rest) {
            tag.setAttribute(PREFIX, null);
            setAttributeIfNotEmpty(tag, PATH, fullPath);
            return;
        }
        //前缀目录本身那一条 rest 是空串，属性留空即可，不能当成「没有 path」删掉
        if (rest.isEmpty()) {
            tag.setAttribute(PATH, "");
        } else {
            tag.setAttribute(PATH, rest);
        }
    }

    private static XmlEntity tree(XmlTag tag, Map<String, String> prefixes) {
        XmlEntity xmlEntity = new XmlEntity();
        XmlAttribute xml_path = tag.getAttribute(PATH);
        XmlAttribute xml_title = tag.getAttribute(TITLE);
        XmlAttribute xml_extension = tag.getAttribute(EXTENSION);
        XmlAttribute xml_presentable_text = tag.getAttribute(PRESENTABLE_TEXT);
        XmlAttribute xml_tooltip_title = tag.getAttribute(TOOLTIP_TITLE);
        XmlAttribute xml_icons = tag.getAttribute(ICON);
        XmlAttribute xml_text_color = tag.getAttribute(TEXT_COLOR);
        XmlAttribute xml_background_color = tag.getAttribute(BACKGROUND_COLOR);
        XmlAttribute xml_strikethrough = tag.getAttribute(STRIKETHROUGH);
        final XmlAttribute xml_prefix = tag.getAttribute(PREFIX);
        //只写 extension 的是「全项目按类型」规则，没有 path 也算有效；
        //引用了前缀的即使 path 为空也算（那是前缀目录本身那一条）
        if (xml_path != null || xml_extension != null || xml_prefix != null) {
            final String path = expandPath(xml_prefix, xml_path, prefixes);
            xmlEntity.setPath(path).setTitle(xml_title == null ? "" : xml_title.getValue()).setExtension(xml_extension == null ? "" : xml_extension.getValue()).setPresentableText(xml_presentable_text == null ? "" : xml_presentable_text.getValue()).setTooltipTitle(xml_tooltip_title == null ? "" : xml_tooltip_title.getValue()).setIcon(xml_icons == null ? "" : xml_icons.getValue()).setTextColor(xml_text_color == null ? "" : xml_text_color.getValue()).setBackgroundColor(xml_background_color == null ? "" : xml_background_color.getValue()).setStrikethrough(xml_strikethrough == null ? null : xml_strikethrough.getValue()).setTag(tag);
            return xmlEntity;
        }
        return null;
    }

    /**
     * 把 {@code prefix} + {@code path} 拼成完整路径
     *
     * <p>
     * 引用了一个查不到的 id 时按「只有 path」处理，不静默把这条规则丢掉：文件是用户手改的，
     * 拼错 id 也该让他在侧边栏里看到这条规则还在，而不是凭空消失。
     * </p>
     *
     * @return 两个都没有内容时返回 null，调用方按「没有 path」处理
     */
    private static String expandPath(XmlAttribute prefixAttr, XmlAttribute pathAttr, Map<String, String> prefixes) {
        final String rest = null == pathAttr ? null : pathAttr.getValue();
        if (null == prefixAttr) {
            return rest;
        }
        final String base = prefixes.get(trimToEmpty(prefixAttr.getValue()));
        if (null == base) {
            return rest;
        }
        return null == rest ? base : base + rest;
    }

    private static String trimToEmpty(String value) {
        return null == value ? "" : value.trim();
    }

}
