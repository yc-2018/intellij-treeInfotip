package com.plugins.infotip.gui.view;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.components.JBScrollPane;
import com.plugins.infotip.storage.XmlFileUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;

/**
 * DirectoryV3.xml 文件管理工具窗口
 *
 * @author yc556&claude-opus-5
 * @version 1.0
 */
public class XmlEditorToolWindow implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        XmlEditorPanel editorPanel = new XmlEditorPanel(project);
        final ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(editorPanel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * 主面板
     */
    static class XmlEditorPanel extends JPanel {
        private static final String XML_FILE_NAME = "DirectoryV3.xml";

        private final Project project;
        private final JTextArea textArea;
        private final JLabel statusLabel;

        public XmlEditorPanel(Project project) {
            this.project = project;
            setLayout(new BorderLayout());

            // 工具栏
            JToolBar toolBar = new JToolBar();
            toolBar.setFloatable(false);

            JButton reloadBtn = new JButton("刷新");
            JButton validateBtn = new JButton("检查失效路径");
            JButton extractPrefixBtn = new JButton("抽离路径前缀");
            JButton formatBtn = new JButton("格式化");
            JButton cleanEmptyBtn = new JButton("清理空属性");
            JButton saveBtn = new JButton("保存");

            toolBar.add(reloadBtn);
            toolBar.addSeparator();
            toolBar.add(validateBtn);
            toolBar.add(extractPrefixBtn);
            toolBar.add(formatBtn);
            toolBar.add(cleanEmptyBtn);
            toolBar.addSeparator();
            toolBar.add(saveBtn);

            // 文本编辑区
            textArea = new JTextArea();
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
            JBScrollPane scrollPane = new JBScrollPane(textArea);

            // 状态栏
            statusLabel = new JLabel("就绪");
            statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

            add(toolBar, BorderLayout.NORTH);
            add(scrollPane, BorderLayout.CENTER);
            add(statusLabel, BorderLayout.SOUTH);

            // 事件绑定
            reloadBtn.addActionListener(e -> loadXmlContent());
            validateBtn.addActionListener(e -> validatePaths());
            extractPrefixBtn.addActionListener(e -> extractPathPrefix());
            formatBtn.addActionListener(e -> formatXml());
            cleanEmptyBtn.addActionListener(e -> cleanEmptyAttributes());
            saveBtn.addActionListener(e -> saveXmlContent());

            // 初始加载
            loadXmlContent();
        }

        private void loadXmlContent() {
            try {
                File xmlFile = new File(project.getBasePath() + File.separator + XML_FILE_NAME);
                if (!xmlFile.exists()) {
                    textArea.setText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<trees>\n</trees>");
                    statusLabel.setText("文件不存在，已创建空模板");
                    return;
                }

                VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(xmlFile);
                if (vf != null) {
                    String content = new String(vf.contentsToByteArray(), vf.getCharset());
                    textArea.setText(content);
                    statusLabel.setText("已加载: " + xmlFile.getName());
                }
            } catch (Exception ex) {
                statusLabel.setText("加载失败: " + ex.getMessage());
                ex.printStackTrace();
            }
        }

        private void validatePaths() {
            statusLabel.setText("正在检查路径...");
            String content = textArea.getText();
            StringBuilder result = new StringBuilder();
            String[] lines = content.split("\n");
            int invalidCount = 0;

            String basePath = project.getBasePath();
            if (basePath == null) {
                statusLabel.setText("无法获取项目根路径");
                return;
            }

            for (String line : lines) {
                if (line.contains("path=\"")) {
                    int start = line.indexOf("path=\"") + 6;
                    int end = line.indexOf("\"", start);
                    if (end > start) {
                        String path = line.substring(start, end);
                        File file = new File(basePath + path);
                        if (!file.exists()) {
                            // 标记失效路径（用注释标注）
                            line = line.trim() + " <!-- 路径失效 -->";
                            invalidCount++;
                        }
                    }
                }
                result.append(line).append("\n");
            }

            textArea.setText(result.toString());
            statusLabel.setText("检查完成，发现 " + invalidCount + " 个失效路径");
        }

        private void extractPathPrefix() {
            statusLabel.setText("功能开发中...");
            // TODO: 实现路径前缀提取功能
        }

        private void formatXml() {
            try {
                String content = textArea.getText();
                // 简单格式化：规范化缩进
                String formatted = content
                        .replaceAll(">\\s*<", ">\n<")
                        .replaceAll("<trees>", "<trees>\n")
                        .replaceAll("</trees>", "\n</trees>")
                        .replaceAll("<tree ", "    <tree ");

                textArea.setText(formatted);
                statusLabel.setText("格式化完成");
            } catch (Exception ex) {
                statusLabel.setText("格式化失败: " + ex.getMessage());
            }
        }

        private void cleanEmptyAttributes() {
            String content = textArea.getText();
            // 移除空属性
            String cleaned = content
                    .replaceAll("\\s+extension=\"\"", "")
                    .replaceAll("\\s+presentableText=\"\"", "")
                    .replaceAll("\\s+tooltipTitle=\"\"", "")
                    .replaceAll("\\s+icon=\"\"", "")
                    .replaceAll("\\s+textColor=\"\"", "")
                    .replaceAll("\\s+backgroundColor=\"\"", "")
                    .replaceAll("\\s+strikethrough=\"\"", "");

            textArea.setText(cleaned);
            statusLabel.setText("已清理空属性");
        }

        private void saveXmlContent() {
            final String basePath = project.getBasePath();
            if (basePath == null) {
                statusLabel.setText("无法获取项目根路径");
                return;
            }

            // 查文件要留在下面的写操作外面：同步刷新自己就要开写操作，套进去是死路
            final VirtualFile existing = LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(new File(basePath + File.separator + XML_FILE_NAME));
            final VirtualFile parent = null != existing ? null
                    : LocalFileSystem.getInstance().refreshAndFindFileByIoFile(new File(basePath));
            if (null == existing && null == parent) {
                statusLabel.setText("保存失败: 找不到项目根目录");
                return;
            }

            // Document 只收 \n，粘进文本框的内容里可能混着 \r
            final String text = StringUtil.convertLineSeparators(textArea.getText());
            final Document[] edited = new Document[1];
            final IOException[] failure = new IOException[1];
            WriteCommandAction.runWriteCommandAction(project, () -> {
                try {
                    final VirtualFile vf = null != existing ? existing : parent.createChildData(this, XML_FILE_NAME);
                    // 这个文件正在编辑器里开着时改它的 Document，免得和用户手上的改动互相覆盖；
                    // 走 VFS 而不是直接写磁盘，才进得了 Local History、PSI 也才会跟着更新
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
                failure[0].printStackTrace();
                return;
            }
            if (null != edited[0]) {
                FileDocumentManager.getInstance().saveDocument(edited[0]);
            }

            // 触发重新加载
            XmlFileUtils.loadXmlFile(project);

            statusLabel.setText("保存成功");
        }
    }
}
