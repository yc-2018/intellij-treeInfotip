package com.plugins.infotip.gui.view;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import com.intellij.ui.treeStructure.Tree;
import com.plugins.infotip.PluginStartupActivity;
import com.plugins.infotip.gui.compone.MyTreeNode;
import com.plugins.infotip.storage.XmlEntity;
import com.plugins.infotip.storage.XmlFileUtils;
import com.plugins.infotip.storage.XmlStorage;
import com.plugins.infotip.trees.TreesStyle;
import com.plugins.infotip.trees.TreesUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.tree.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Enumeration;
import java.util.List;

/**
 * A <code>NoteTreeView</code> Class
 *
 * @author lk
 * @version 1.0
 * <p><b>date: 2023/4/13 21:03</b></p>
 */
public class NoteTreeView extends Tree implements ToolWindowFactory {

    public NoteTreeView() {
        super(new DefaultMutableTreeNode("备注列表"));
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        final NoteTreeView noteTreeView = new NoteTreeView();
        XmlFileUtils.SaveCallback saveCallback = () -> {
            DefaultTreeModel model = (DefaultTreeModel) noteTreeView.getModel();
            final DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
            root.removeAllChildren();
            model.reload();
            final List<XmlEntity> xmlEntity = XmlStorage.getXmlEntity(project);
            if (null != xmlEntity) {
                for (XmlEntity entity : xmlEntity) {
                    final MyTreeNode defaultMutableTreeNode = new MyTreeNode(label(entity));
                    defaultMutableTreeNode.setUserEntity(entity);
                    root.add(defaultMutableTreeNode);
                }
                for (int i = 0; i <= root.getChildCount(); i++) {
                    noteTreeView.expandRow(i);
                }
            }
        };
        PluginStartupActivity.RunCallback runCallback = () -> {
            saveCallback.run();
        };
        noteTreeView.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    final Object component = noteTreeView.getLastSelectedPathComponent();
                    if (component instanceof MyTreeNode) {
                        final MyTreeNode lastSelectedPathComponent = (MyTreeNode) noteTreeView.getLastSelectedPathComponent();
                        if (null != lastSelectedPathComponent) {
                            final XmlEntity userEntity = (XmlEntity) lastSelectedPathComponent.getUserEntity();
                            if (null != userEntity) {
                                TreesUtils.Navigation(project, userEntity.getPath());
                            }
                        }
                    } else {
                        saveCallback.run();
                    }
                }
            }
        });
        PluginStartupActivity.ListenerRun(project, runCallback);
        XmlFileUtils.ListenerSave(project, saveCallback);
        saveCallback.run();
        final ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
        Content content = contentFactory.createContent(noteTreeView, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * 列表里显示的文字
     * <p>
     * 类型规则不绑定单个文件，只显示备注看不出它管的是什么，所以补上扩展名和生效范围。
     * </p>
     */
    private static String label(XmlEntity entity) {
        final String title = null == entity.getTitle() ? "" : entity.getTitle();
        final String extension = entity.getExtension();
        if (null == extension || extension.trim().isEmpty()) {
            return title;
        }
        final String path = entity.getPath();
        final String scope = null == path || path.trim().isEmpty() ? "整个项目" : path;
        final String suffix = "*." + extension.trim() + " @ " + scope;
        return title.isEmpty() ? suffix : title + "  [" + suffix + "]";
    }

}
