package com.plugins.infotip.storage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.XmlElementFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 路径前缀的抽离与还原：改 XML 那一半
 *
 * <p>
 * 输入输出都是<b>文本</b>，中间借一个内存里的 {@link XmlFile} 来改。这么绕一趟是为了不写正则：
 * 属性值里出现 {@code <} {@code >} 时正则会切错，而 PSI 改完 {@code getText()} 拿回来，
 * 没动到的地方——包括模板里那段参数说明注释和原有缩进——全都原样保留。
 * </p>
 * <p>
 * 用的是 {@code createFileFromText} 建的非物理文件，改它不碰磁盘，所以弹窗里可以先抽给用户看，
 * 他点了「保存」才落盘。
 * </p>
 *
 * @author yc556&claude-fable-5
 * @version 1.0
 */
public final class PathPrefixEditor {

    private PathPrefixEditor() {
    }

    /**
     * 这段文本是不是已经抽离过了
     *
     * @param project 项目
     * @param xmlText 配置文件文本
     * @return 有 {@code <prefixes>} 段且里面有声明就算
     */
    public static boolean isExtracted(Project project, String xmlText) {
        final XmlFile file = parse(project, xmlText);
        if (null == file) {
            return false;
        }
        return !XmlStorage.readPrefixes(file).isEmpty();
    }

    /**
     * 抽离：把重复的长目录提到 {@code <prefixes>} 里，每条规则只留剩下那截
     *
     * <p>
     * 先还原一遍再抽，所以重复点不会把前缀叠起来，也能在改过配置之后重新算一次最优前缀。
     * </p>
     *
     * @param project 项目
     * @param xmlText 配置文件文本
     * @return 抽离后的文本；没什么可抽的就把原文本原样还回去
     */
    public static String extract(Project project, String xmlText) {
        final XmlFile file = parse(project, xmlText);
        final XmlTag rootTag = rootTag(file);
        if (null == rootTag) {
            return xmlText;
        }
        final String[] result = new String[1];
        ApplicationManager.getApplication().runWriteAction(() -> {
            //先在同一棵 PSI 上把已有前缀展开，再按当前配置重算一遍最优前缀。
            //所以重复点「抽离」不会把前缀叠起来，改过配置之后也能重新抽
            flatten(file, rootTag);

            final List<XmlTag> trees = collectTrees(rootTag);
            final List<String> paths = new ArrayList<>();
            for (XmlTag tree : trees) {
                paths.add(tree.getAttributeValue(XmlStorage.PATH));
            }
            final PathPrefixes.Plan plan = PathPrefixes.plan(paths);
            if (!plan.isEmpty()) {
                //前缀表拼成带换行的文本再建标签：一条 prefix 一行，用户打开文件第一眼就看清 id 对应什么。
                //光靠 setAttribute 建出来的会全挤在一行
                final StringBuilder text = new StringBuilder("<").append(XmlStorage.PREFIXES).append(">\n");
                for (Map.Entry<String, String> entry : plan.getPrefixes().entrySet()) {
                    text.append("<").append(XmlStorage.PREFIX)
                            .append(" ").append(XmlStorage.PREFIX_ID).append("=\"").append(entry.getKey()).append("\"")
                            .append(" ").append(XmlStorage.PATH).append("=\"").append(entry.getValue()).append("\"/>\n");
                }
                text.append("</").append(XmlStorage.PREFIXES).append(">");
                rootTag.addSubTag(XmlElementFactory.getInstance(project).createTagFromText(text), true);

                for (int i = 0; i < trees.size(); i++) {
                    final String id = plan.ownerOf(i);
                    if (null == id) {
                        continue;
                    }
                    final String rest = PathPrefixes.stripPrefix(paths.get(i), plan.pathOf(id));
                    if (null == rest) {
                        continue;
                    }
                    final XmlTag tree = trees.get(i);
                    tree.setAttribute(XmlStorage.PREFIX, id);
                    //前缀目录自己那一条剩下空串，属性要留着写成空，删掉就变成「没有 path」了
                    tree.setAttribute(XmlStorage.PATH, rest);
                }
            }
            //插进去的标签和它后面那条 tree 之间没有换行、缩进也对不齐，交给 IDE 自己的 XML 风格收拾。
            //只整理 <trees> 里面，外面那段参数说明注释碰不到
            CodeStyleManager.getInstance(project).reformat(rootTag);
            result[0] = file.getText();
        });
        return null == result[0] ? xmlText : result[0];
    }

    /**
     * 还原：把 {@code prefix} 展开回完整路径，去掉 {@code <prefixes>} 段
     *
     * <p>
     * 还原后的文件老版插件也能读，所以这就是「抽离」的后悔按钮。
     * </p>
     *
     * @param project 项目
     * @param xmlText 配置文件文本
     * @return 还原后的文本；本来就没抽离过就把原文本原样还回去
     */
    public static String restore(Project project, String xmlText) {
        final XmlFile file = parse(project, xmlText);
        final XmlTag rootTag = rootTag(file);
        if (null == rootTag) {
            return xmlText;
        }
        if (XmlStorage.readPrefixes(file).isEmpty()) {
            return xmlText;
        }
        final String[] result = new String[1];
        ApplicationManager.getApplication().runWriteAction(() -> {
            flatten(file, rootTag);
            //抽离时 reformat 过，还原时同样收拾一次，来回切格式才是稳定的
            CodeStyleManager.getInstance(project).reformat(rootTag);
            result[0] = file.getText();
        });
        return null == result[0] ? xmlText : result[0];
    }

    /**
     * 就地把 {@code prefix} 展开回完整路径，并删掉 {@code <prefixes>} 段
     *
     * <p>
     * 抽离和还原共用这一步。抽离必须在<b>同一棵 PSI</b> 上先展开再重算，不能走
     * 「还原成文本、再解析一遍」——那样会 reformat 两次，空白会漂，重复点「抽离」得到的结果就不一致了。
     * </p>
     * <p>
     * 调用方负责开写操作。
     * </p>
     */
    private static void flatten(XmlFile file, XmlTag rootTag) {
        final Map<String, String> prefixes = XmlStorage.readPrefixes(file);
        for (XmlTag tree : collectTrees(rootTag)) {
            final String id = tree.getAttributeValue(XmlStorage.PREFIX);
            if (null == id) {
                continue;
            }
            final String base = prefixes.get(id.trim());
            tree.setAttribute(XmlStorage.PREFIX, null);
            if (null == base) {
                //id 查不到，剩下那截就当完整路径，和解析时的兜底保持一致
                continue;
            }
            final String rest = tree.getAttributeValue(XmlStorage.PATH);
            tree.setAttribute(XmlStorage.PATH, null == rest || rest.isEmpty() ? base : base + rest);
        }
        for (XmlTag holder : rootTag.findSubTags(XmlStorage.PREFIXES)) {
            holder.delete();
        }
    }

    /**
     * 把文本解析成一个内存里的 XmlFile，不落盘
     */
    private static XmlFile parse(Project project, String xmlText) {
        if (null == project || null == xmlText) {
            return null;
        }
        final LanguageFileType xml = (LanguageFileType) FileTypeManager.getInstance().getStdFileType("XML");
        final PsiFile[] holder = new PsiFile[1];
        ApplicationManager.getApplication().runReadAction(() -> {
            holder[0] = PsiFileFactory.getInstance(project).createFileFromText("DirectoryV6.xml", xml, xmlText);
        });
        return holder[0] instanceof XmlFile ? (XmlFile) holder[0] : null;
    }

    private static XmlTag rootTag(XmlFile file) {
        if (null == file) {
            return null;
        }
        final XmlDocument document = file.getDocument();
        if (null == document) {
            return null;
        }
        final XmlTag rootTag = document.getRootTag();
        return null != rootTag && XmlStorage.TREES.equals(rootTag.getName()) ? rootTag : null;
    }

    /**
     * 取直接挂在 {@code <trees>} 下的那些 {@code <tree>}
     */
    private static List<XmlTag> collectTrees(XmlTag rootTag) {
        final List<XmlTag> trees = new ArrayList<>();
        for (XmlTag tag : rootTag.findSubTags(XmlStorage.TREE)) {
            trees.add(tag);
        }
        return trees;
    }
}
