package com.plugins.infotip.storage;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;

/**
 * A <code>XmlChangeListener</code> Class
 *
 * @author lk
 * @version 1.0
 * <p><b>date: 2023/4/13 15:11</b></p>
 */
public class XmlChangeListener {

    /**
     * 文件监听
     *
     * @param project 项目
     */
    public static void run(Project project) {
        PsiManager.getInstance(project).addPsiTreeChangeListener(new PsiTreeChangeListener() {
            @Override
            public void beforeChildAddition(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            @Override
            public void beforeChildRemoval(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            @Override
            public void beforeChildReplacement(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            @Override
            public void beforeChildMovement(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            //更新前
            @Override
            public void beforeChildrenChange(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
                if (XmlFileUtils.isFileName(psiTreeChangeEvent)) {
                    final PsiFile file = psiTreeChangeEvent.getFile();
                    if (file instanceof XmlFile) {
                        XmlFileUtils.loadXmlFile(project);
                        XmlStorage.parsing(project, (XmlFile) file);
                    }
                    //PyCharm 的模板 PSI 不是 XmlFile，这里先不动，交给 childrenChanged
                }
            }

            @Override
            public void beforePropertyChange(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            @Override
            public void childAdded(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            @Override
            public void childRemoved(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
                final PsiElement child = psiTreeChangeEvent.getChild();
                if (child instanceof PsiFile && XmlFileUtils.isFileName(((PsiFile) child).getName())) {
                    XmlStorage.clear(project);
                }
            }

            @Override
            public void childReplaced(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            @Override
            public void childrenChanged(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
                if (XmlFileUtils.isFileName(psiTreeChangeEvent)) {
                    final PsiFile file = psiTreeChangeEvent.getFile();
                    //事件里的 PSI 不一定是 XmlFile：PyCharm 的 Jinja2 / Django 会把 .xml 接管成
                    //模板 PSI，这时改用缓存里的 XML PSI 重新解析，否则内存里的规则列表永远是启动时那份
                    final XmlFile xmlFile = file instanceof XmlFile ? (XmlFile) file : currentXmlFile(project);
                    XmlStorage.parsing(project, xmlFile);
                }
            }

            @Override
            public void childMoved(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }

            @Override
            public void propertyChanged(@NotNull PsiTreeChangeEvent psiTreeChangeEvent) {
            }
        }, new Disposable() {
            @Override
            public void dispose() {
            }
        });
    }

    /**
     * 缓存里的 XML PSI；失效了就重新找一次
     *
     * <p>只会在事件文件不是 {@link XmlFile}（PyCharm 的模板 PSI）时用到。</p>
     */
    private static XmlFile currentXmlFile(Project project) {
        final XmlFile cached = XmlFileUtils.getXmlFile(project);
        if (null != cached && cached.isValid()) {
            return cached;
        }
        return XmlFileUtils.loadXmlFile(project);
    }

}
