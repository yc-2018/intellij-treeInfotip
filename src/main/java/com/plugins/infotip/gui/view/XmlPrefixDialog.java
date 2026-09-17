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
import com.intellij.ui.components.JBScrollPane;
import com.plugins.infotip.storage.PathPrefixEditor;
import com.plugins.infotip.storage.XmlFileUtils;
import com.plugins.infotip.storage.XmlStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;

/**
 * 路径前缀弹窗：直接显示配置文件正文，顶上三个按钮——抽离、还原、保存
 *
 * <p>
 * 抽离和还原都只改<b>文本框里的内容</b>，不落盘，用户看清楚了再点「保存」。所以「还原」是
 * 后悔按钮，连「不保存直接关掉」也是后悔的办法，抽离这件事没有不可逆的一步。
 * </p>
 * <p>
 * 这里的按钮都是纯 Swing 的 {@link JButton}，回调上<b>没有读锁</b>——平台只给 {@code AnAction}
 * 套那一层。所以碰 PSI 的活都推给 {@link PathPrefixEditor}（它自己包好了），写文件走
 * {@link WriteCommandAction}，重新解析那句单独包一层读操作。
 * </p>
 *
 * @author yc556&claude-fable-5
 * @version 1.0
 */
public class XmlPrefixDialog extends DialogWrapper {

    private final Project project;
    private final JTextArea textArea = new JTextArea();
    private final JLabel statusLabel = new JLabel();

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
        final JButton saveBtn = new JButton("保存");
        extractBtn.setToolTipText("把重复的长目录提到 prefixes 里，每条规则只留剩下那截");
        restoreBtn.setToolTipText("把 prefix 展开回完整路径，恢复成旧版插件也能读的写法");
        saveBtn.setToolTipText("把上面的内容写回配置文件");

        extractBtn.addActionListener(e -> doExtract());
        restoreBtn.addActionListener(e -> doRestore());
        saveBtn.addActionListener(e -> doSave());

        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(extractBtn);
        bar.add(restoreBtn);
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
     * 抽离：先问一句，再改文本框
     */
    private void doExtract() {
        if (PathPrefixEditor.isExtracted(project, textArea.getText())) {
            //已经抽过了还点，就是想按新配置重算一遍，不用再警告一次
            apply(PathPrefixEditor.extract(project, textArea.getText()), "已按当前配置重新抽离");
            return;
        }
        final int answer = Messages.showDialog(project,
                "抽离之后配置文件里会出现 prefixes 段和 prefix 属性，\n"
                        + "旧版插件（以及 5.x 的本插件）不认这两样，会把剩下那截当成完整路径，备注就全错位了。\n\n"
                        + "本插件 6.0.0 起读的是 " + XmlFileUtils.configFileName(project)
                        + "，和旧版各读各的文件，所以只影响还在用旧版的人。\n"
                        + "抽完先看一眼，不满意可以点「还原」，或者直接关掉不保存。",
                "抽离路径前缀",
                new String[]{"抽离", "取消"},
                //默认焦点给「取消」：这是会改用户项目里配置文件的动作
                1,
                Messages.getWarningIcon());
        if (0 != answer) {
            return;
        }
        apply(PathPrefixEditor.extract(project, textArea.getText()), "已抽离，确认无误后点「保存」");
    }

    private void doRestore() {
        if (!PathPrefixEditor.isExtracted(project, textArea.getText())) {
            statusLabel.setText("当前内容没有抽离过，不用还原");
            return;
        }
        apply(PathPrefixEditor.restore(project, textArea.getText()), "已还原，确认无误后点「保存」");
    }

    private void apply(String newText, String message) {
        if (newText.equals(textArea.getText())) {
            statusLabel.setText("没有可改的地方");
            return;
        }
        textArea.setText(newText);
        textArea.setCaretPosition(0);
        statusLabel.setText(message);
    }

    private void loadFromDisk() {
        final VirtualFile vf = XmlFileUtils.findConfigFile(project);
        if (null == vf) {
            textArea.setText("");
            statusLabel.setText("这个项目还没有配置文件，先去项目树右键加一条备注");
            return;
        }
        try {
            textArea.setText(new String(vf.contentsToByteArray(), vf.getCharset()));
            textArea.setCaretPosition(0);
            statusLabel.setText("已加载 " + vf.getName());
        } catch (IOException ex) {
            statusLabel.setText("读取失败: " + ex.getMessage());
        }
    }

    /**
     * 保存：走 VFS 不直接写磁盘，这样能撤销，也不会和编辑器里打开的同一个文件互相覆盖
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

        //重新解析一遍，项目树和侧边栏立刻跟上。parsing 要走 PSI，这里是 Swing 回调、没有读锁，
        //得自己包一层；loadXmlFile 不能一起包进来，它内部要做同步 VFS 刷新
        final XmlFile xmlFile = XmlFileUtils.loadXmlFile(project);
        if (null != xmlFile) {
            ApplicationManager.getApplication().runReadAction(() -> {
                XmlStorage.parsing(project, xmlFile);
            });
        }
        statusLabel.setText("保存成功");
    }
}
