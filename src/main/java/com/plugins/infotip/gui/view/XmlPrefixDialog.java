package com.plugins.infotip.gui.view;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.plugins.infotip.storage.PathPrefixEditor;
import com.plugins.infotip.storage.PrefixIssue;
import com.plugins.infotip.storage.XmlFileUtils;
import com.plugins.infotip.storage.XmlStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 路径前缀弹窗：直接显示配置文件正文，顶上四个按钮——抽离、还原、清理前缀、保存
 *
 * <p>
 * 四个按钮都只改<b>文本框里的内容</b>，只有「保存」落盘，用户看清楚了再存。所以「还原」是
 * 后悔按钮，连「不保存直接关掉」也是后悔的办法，抽离这件事没有不可逆的一步。
 * </p>
 * <p>
 * <b>配置文件改名一律推迟到保存那一刻</b>，抽离和还原时都不动文件名。这样文件名和内容永远
 * 是一致的：只要文件叫 {@code DirectoryV6.xml}，里面就确实抽离过、旧版读不了；反过来叫
 * {@code DirectoryV3.xml} 的就一定是旧版也能读的写法。中途反悔了文件名也没被动过。
 * </p>
 * <p>
 * 这里的按钮都是纯 Swing 的 {@link JButton}，回调上<b>没有读锁</b>——平台只给 {@code AnAction}
 * 套那一层。所以碰 PSI 的活都推给 {@link PathPrefixEditor}（它自己包好了），写文件走
 * {@link WriteCommandAction}，重新解析那句单独包一层读操作。
 * </p>
 *
 * @author yc556&claude-fable-5
 * @version 1.1
 */
public class XmlPrefixDialog extends DialogWrapper {

    /**
     * 路径已失效的前缀，标红
     */
    private static final Color DEAD_COLOR = new JBColor(new Color(0xFF, 0xC9, 0xC9), new Color(0x6E, 0x3B, 0x3B));

    /**
     * 没有规则引用的前缀，标黄
     */
    private static final Color UNUSED_COLOR = new JBColor(new Color(0xFF, 0xEE, 0xB0), new Color(0x6B, 0x5E, 0x2E));

    private final Project project;
    private final JTextArea textArea = new JTextArea();
    private final JLabel statusLabel = new JLabel();

    /**
     * 用户在「还原」时选了「配置名换回旧名字」，保存时才真的改名
     */
    private boolean renameToLegacyOnSave = false;

    public XmlPrefixDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;
        setTitle("路径前缀 · " + XmlFileUtils.configFileName(project));
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        init();
        loadFromDisk();
    }

    @Nullable
    @Override
    protected String getDimensionServiceKey() {
        //记住用户调过的窗口大小，这个弹窗大概率要拉大看
        return "TreeInfotip.XmlPrefixDialog";
    }

    @Override
    protected JComponent createNorthPanel() {
        final JButton extractBtn = new JButton("抽离");
        final JButton restoreBtn = new JButton("还原");
        final JButton cleanBtn = new JButton("清理前缀");
        final JButton saveBtn = new JButton("保存");
        extractBtn.setToolTipText("把重复的长目录提到 prefixes 里，每条规则只留剩下那截");
        restoreBtn.setToolTipText("把 prefix 展开回完整路径，恢复成旧版插件也能读的写法");
        cleanBtn.setToolTipText("删掉路径已失效（红）和没有规则引用（黄）的 prefix 声明");
        saveBtn.setToolTipText("把上面的内容写回配置文件");

        extractBtn.addActionListener(e -> doExtract());
        restoreBtn.addActionListener(e -> doRestore());
        cleanBtn.addActionListener(e -> doClean());
        saveBtn.addActionListener(e -> doSave());

        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(extractBtn);
        bar.add(restoreBtn);
        bar.add(cleanBtn);
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(saveBtn);
        bar.add(statusLabel);
        return bar;
    }

    @Override
    protected JComponent createCenterPanel() {
        final JBScrollPane scroll = new JBScrollPane(textArea);
        scroll.setPreferredSize(new Dimension(760, 520));
        return scroll;
    }

    @Override
    protected Action @NotNull [] createActions() {
        //保存是上面那个按钮的事，底下只留一个关闭，免得用户以为「确定」会存盘
        final Action close = getCancelAction();
        close.putValue(Action.NAME, "关闭");
        return new Action[]{close};
    }

    /**
     * 抽离：只有「旧名字第一次抽」才警告一次
     *
     * <p>
     * 警告说的是<b>兼容性</b>：文件从 {@code DirectoryV3.xml} 改名成 {@code DirectoryV6.xml}
     * 之后旧版插件就找不到它了。本来就叫 V6 的、或者已经抽过再点一次的，都没有这个变化，
     * 再弹一次只是噪音，直接按当前配置重算。
     * </p>
     */
    private void doExtract() {
        final boolean extracted = PathPrefixEditor.isExtracted(project, textArea.getText());
        if (!extracted && XmlFileUtils.isOnLegacyName(project) && !confirmIncompatible()) {
            return;
        }
        //抽离之后文件必须叫 V6，上一轮还原时记的「换回旧名字」作废
        renameToLegacyOnSave = false;
        apply(PathPrefixEditor.extract(project, textArea.getText()),
                extracted ? "已按当前配置重新抽离，确认无误后点「保存」" : "已抽离，确认无误后点「保存」");
    }

    /**
     * 第一次把旧文件抽离时问一句
     *
     * @return 用户点了「抽离」才是 true
     */
    private boolean confirmIncompatible() {
        final int answer = Messages.showDialog(project,
                "抽离之后配置文件里会出现 prefixes 段和 prefix 属性，旧版插件不认这两样，\n"
                        + "会把剩下那截当成完整路径，备注全部错位。\n\n"
                        + "所以保存时会把 " + XmlFileUtils.legacyFileName()
                        + " 改名为 " + XmlFileUtils.currentFileName() + "（内容一个字不动），\n"
                        + "新旧两版从此各读各的文件，互相读不坏。\n\n"
                        + "抽完先看一眼，不满意可以点「还原」，或者直接关掉不保存——\n"
                        + "不点「保存」的话文件名和内容都不会变。",
                "抽离路径前缀",
                new String[]{"抽离", "取消"},
                //默认焦点给「取消」：这是会改用户项目里配置文件的动作
                1,
                Messages.getWarningIcon());
        return 0 == answer;
    }

    /**
     * 还原：展开回完整路径，顺带问一句文件名要不要跟着换回去
     *
     * <p>
     * 还原之后内容旧版插件就能读了，但文件还叫 {@code DirectoryV6.xml}、旧版根本找不到它，
     * 所以要给个换回旧名字的机会。不是所有人都想换——只用本插件的人换回去反而多一次改名，
     * 于是三个选项都留着，默认焦点给「取消」。
     * </p>
     */
    private void doRestore() {
        if (!PathPrefixEditor.isExtracted(project, textArea.getText())) {
            setStatus("当前内容没有抽离过，不用还原");
            return;
        }
        if (XmlFileUtils.isOnLegacyName(project)) {
            //本来就是旧名字，没有可换的
            renameToLegacyOnSave = false;
            apply(PathPrefixEditor.restore(project, textArea.getText()), "已还原，确认无误后点「保存」");
            return;
        }
        final int answer = Messages.showDialog(project,
                "还原之后内容就是旧版插件也能读的写法了，但文件还叫 "
                        + XmlFileUtils.currentFileName() + "，旧版找的是 "
                        + XmlFileUtils.legacyFileName() + "、看不到它。\n\n"
                        + "要不要把文件名也换回去？只用本插件的话保留现在的名字就行，两个名字它都认。\n"
                        + "改名和还原都在点「保存」时才真的发生。",
                "还原路径前缀",
                new String[]{"换回 " + XmlFileUtils.legacyFileName(),
                        "保留 " + XmlFileUtils.currentFileName(), "取消"},
                //默认焦点给「取消」
                2,
                Messages.getQuestionIcon());
        if (0 != answer && 1 != answer) {
            return;
        }
        renameToLegacyOnSave = 0 == answer;
        apply(PathPrefixEditor.restore(project, textArea.getText()),
                renameToLegacyOnSave ? "已还原，保存时会把文件名换回 " + XmlFileUtils.legacyFileName()
                        : "已还原，确认无误后点「保存」");
    }

    /**
     * 清理前缀：把体检出来的问题列一遍，用户确认了再删
     */
    private void doClean() {
        final List<PrefixIssue> issues = PathPrefixEditor.diagnose(project, textArea.getText());
        final StringBuilder detail = new StringBuilder();
        int count = 0;
        for (PrefixIssue issue : issues) {
            if (issue.isProblem()) {
                count++;
                detail.append("\n    ").append(issue.describe());
            }
        }
        if (0 == count) {
            setStatus("没有失效或没人引用的前缀，不用清理");
            return;
        }
        final int answer = Messages.showDialog(project,
                "这 " + count + " 条 prefix 声明有问题：\n" + detail + "\n\n"
                        + "没人引用的只删声明本身，删了什么都不影响。\n"
                        + "路径已失效的会把引用它的规则一起删掉——只删声明的话那些规则\n"
                        + "会剩下半截相对路径、变成指向别处的错规则，比留着失效的更糟。\n\n"
                        + "同样是改文本框，点「保存」才落盘。",
                "清理前缀声明",
                new String[]{"清理", "取消"},
                //默认焦点给「取消」：这会连带删规则
                1,
                Messages.getWarningIcon());
        if (0 != answer) {
            return;
        }
        apply(PathPrefixEditor.cleanPrefixes(project, textArea.getText()), "已清理，确认无误后点「保存」");
    }

    /**
     * 把新文本放进文本框，顺带重新体检一遍
     */
    private void apply(String newText, String message) {
        if (newText.equals(textArea.getText())) {
            setStatus("没有可改的地方");
            return;
        }
        textArea.setText(newText);
        textArea.setCaretPosition(0);
        setStatus(message);
    }

    /**
     * 状态栏文字后面永远跟着前缀体检的结论
     */
    private void setStatus(String message) {
        statusLabel.setText(message + refreshHighlights());
    }

    /**
     * 给有问题的 {@code <prefix>} 声明上底色，并给出一句结论
     *
     * <p>
     * 失效的前缀在别处是看不出来的：「目录备注」列表标红的是规则，而一条前缀失效意味着底下
     * 一批规则<b>一起</b>失效，光看列表不知道根因在前缀上。所以在这里就地标出来——红色是
     * 路径已经不在了，黄色是没有任何规则引用它。
     * </p>
     * <p>
     * 偏移量来自 {@link PathPrefixEditor#diagnose}，它解析的就是文本框里这段文本，
     * 所以偏移量能直接用。之后用户手打字时 Swing 自己会跟着挪这些高亮。
     * </p>
     *
     * @return 给状态栏用的一句话，没问题就是空串
     */
    private String refreshHighlights() {
        final Highlighter highlighter = textArea.getHighlighter();
        highlighter.removeAllHighlights();
        final int length = textArea.getDocument().getLength();
        int dead = 0;
        int unused = 0;
        for (PrefixIssue issue : PathPrefixEditor.diagnose(project, textArea.getText())) {
            final Color color;
            if (issue.isDead()) {
                dead++;
                color = DEAD_COLOR;
            } else if (issue.isUnused()) {
                unused++;
                color = UNUSED_COLOR;
            } else {
                continue;
            }
            if (issue.getStartOffset() < 0 || issue.getEndOffset() > length) {
                continue;
            }
            try {
                highlighter.addHighlight(issue.getStartOffset(), issue.getEndOffset(),
                        new DefaultHighlighter.DefaultHighlightPainter(color));
            } catch (BadLocationException ignored) {
                //标不上就算了，标色只是提示，不该拦着别的事
            }
        }
        if (0 == dead && 0 == unused) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("　｜ ");
        if (dead > 0) {
            sb.append(dead).append(" 条前缀路径已失效（红）");
        }
        if (unused > 0) {
            sb.append(dead > 0 ? "，" : "").append(unused).append(" 条没有规则引用（黄）");
        }
        return sb.append("，可点「清理前缀」").toString();
    }

    /**
     * 读盘：行分隔符统一成 {@code \n}
     *
     * <p>
     * 不统一的话文本框里是 {@code \r\n}、解析出来的偏移量是按 {@code \n} 数的，高亮会整体错位。
     * 保存时本来也要 {@code convertLineSeparators}，这里先转等于提前对齐。
     * </p>
     */
    private void loadFromDisk() {
        final VirtualFile vf = XmlFileUtils.findConfigFile(project);
        if (null == vf) {
            textArea.setText("");
            statusLabel.setText("这个项目还没有配置文件，先去项目树右键加一条备注");
            return;
        }
        try {
            textArea.setText(StringUtil.convertLineSeparators(new String(vf.contentsToByteArray(), vf.getCharset())));
            textArea.setCaretPosition(0);
            setStatus("已加载 " + vf.getName());
        } catch (IOException ex) {
            statusLabel.setText("读取失败: " + ex.getMessage());
        }
    }

    /**
     * 保存：先写内容，再按内容决定文件名
     *
     * <p>
     * 走 VFS 不直接写磁盘，这样能撤销，也不会和编辑器里打开的同一个文件互相覆盖。
     * </p>
     * <p>
     * 改名放在写完之后，两个方向：内容抽离过而文件还叫旧名字，就改成 V6；内容没抽离过
     * 而用户在「还原」时选了换回去，就改成 V3。<b>顺序不能反</b>——先改名的话写文件那步
     * 找到的还是老的 {@link VirtualFile}，中途失败就会留下一个名字对内容不对的文件。
     * </p>
     */
    private void doSave() {
        final String basePath = project.getBasePath();
        if (null == basePath) {
            statusLabel.setText("保存失败: 拿不到项目根路径");
            return;
        }
        //查文件留在写操作外面：同步刷新自己要开写操作
        final VirtualFile existing = XmlFileUtils.findConfigFile(project);
        final VirtualFile parent = null != existing ? null
                : LocalFileSystem.getInstance().refreshAndFindFileByIoFile(new File(basePath));
        if (null == existing && null == parent) {
            statusLabel.setText("保存失败: 找不到项目根目录");
            return;
        }

        //Document 只收 \n，粘进文本框的内容里可能混着 \r
        final String text = StringUtil.convertLineSeparators(textArea.getText());
        final boolean extracted = PathPrefixEditor.isExtracted(project, text);
        final Document[] edited = new Document[1];
        final IOException[] failure = new IOException[1];
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                final VirtualFile vf = null != existing ? existing
                        : parent.createChildData(this, XmlFileUtils.configFileName(project));
                final Document document = FileDocumentManager.getInstance().getDocument(vf);
                if (null != document) {
                    document.setText(text);
                    edited[0] = document;
                } else {
                    VfsUtil.saveText(vf, text);
                }
            } catch (IOException ex) {
                failure[0] = ex;
            }
        });
        if (null != failure[0]) {
            statusLabel.setText("保存失败: " + failure[0].getMessage());
            return;
        }
        if (null != edited[0]) {
            FileDocumentManager.getInstance().saveDocument(edited[0]);
        }
        final String renamed = syncFileName(extracted);

        //重新解析一遍，项目树和侧边栏立刻跟上。parsing 要走 PSI，这里是 Swing 回调、没有读锁，
        //得自己包一层；loadXmlFile 不能一起包进来，它内部要做同步 VFS 刷新
        final XmlFile xmlFile = XmlFileUtils.loadXmlFile(project);
        if (null != xmlFile) {
            ApplicationManager.getApplication().runReadAction(() -> XmlStorage.parsing(project, xmlFile));
        }
        setTitle("路径前缀 · " + XmlFileUtils.configFileName(project));
        setStatus("保存成功" + renamed);
    }

    /**
     * 让文件名和内容对上
     *
     * @param extracted 刚存进去的内容是不是抽离过的
     * @return 给状态栏用的一句话，没改名就是空串
     */
    private String syncFileName(boolean extracted) {
        final String from = XmlFileUtils.configFileName(project);
        final String to;
        if (extracted && XmlFileUtils.isOnLegacyName(project)) {
            to = XmlFileUtils.currentFileName();
        } else if (!extracted && renameToLegacyOnSave && !XmlFileUtils.isOnLegacyName(project)) {
            to = XmlFileUtils.legacyFileName();
        } else {
            return "";
        }
        if (!XmlFileUtils.renameConfigFile(project, to)) {
            return "，但改名为 " + to + " 失败（同名文件已经存在？）";
        }
        renameToLegacyOnSave = false;
        //动的是会进版本库的文件，除了状态栏还要发条通知：用户下次提交会看到一删一增，得知道是谁干的
        XmlFileUtils.notifyRenamed(project, from, to);
        return "，并已改名为 " + to;
    }
}
