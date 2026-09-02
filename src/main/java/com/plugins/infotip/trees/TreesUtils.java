package com.plugins.infotip.trees;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.impl.ProjectViewImpl;
import com.intellij.ide.projectView.impl.nodes.AbstractPsiBasedNode;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.plugins.infotip.storage.XmlEntity;
import com.plugins.infotip.storage.XmlStorage;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * A <code>TreesUtils</code> Class
 *
 * @author lk
 * @version 1.0
 * <p><b>date: 2023/4/13 14:47</b></p>
 */
public class TreesUtils {

    /**
     * 匹配路径
     * <p>
     * 支持两类规则，优先级从高到低：
     * <ol>
     *     <li>路径规则 —— 只写 path，路径全等时命中，比任何扩展名规则都优先；</li>
     *     <li>目录级类型规则 —— path + extension，命中该目录（含各级子目录）下的同扩展名文件，
     *     多条同时命中时 path 更长（更靠里）的那条生效；</li>
     *     <li>全项目类型规则 —— 只写 extension，命中整个项目里的同扩展名文件。</li>
     * </ol>
     * 扩展名规则只作用于文件，目录节点不参与匹配。
     * </p>
     *
     * @param virtualFile 文件对象
     * @return 命中的规则，没有则返回 null
     */
    public static XmlEntity getMatchPath(VirtualFile virtualFile, Project project) {
        final List<XmlEntity> xml = XmlStorage.getXmlEntity(project);
        if (null == xml || null == virtualFile || null == project) {
            return null;
        }
        final String basePath = project.getPresentableUrl();
        final String canonicalPath = virtualFile.getCanonicalPath();
        if (null == basePath || null == canonicalPath || canonicalPath.length() < basePath.length()) {
            return null;
        }
        final String relativePath = canonicalPath.substring(basePath.length());
        final String fileExtension = virtualFile.isDirectory() ? null : virtualFile.getExtension();
        XmlEntity extensionMatch = null;
        //已命中扩展名规则的限定目录长度：越长表示范围越具体，-1 表示还没命中过
        int matchedScopeLength = -1;
        for (XmlEntity listTreeInfo : xml) {
            if (null == listTreeInfo) {
                continue;
            }
            final String rulePath = trimTrailingSlash(listTreeInfo.getPath());
            final String ruleExtension = listTreeInfo.getExtension();
            if (!isNotEmpty(ruleExtension)) {
                //路径规则：全等即命中，优先级最高，直接返回
                if (isNotEmpty(rulePath) && rulePath.equals(relativePath)) {
                    return listTreeInfo;
                }
                continue;
            }
            if (null == fileExtension || !ruleExtension.trim().equalsIgnoreCase(fileExtension)) {
                continue;
            }
            if (isNotEmpty(rulePath)) {
                //目录级：文件要落在这个目录之下
                if (relativePath.startsWith(rulePath + "/") && rulePath.length() > matchedScopeLength) {
                    matchedScopeLength = rulePath.length();
                    extensionMatch = listTreeInfo;
                }
            } else if (matchedScopeLength < 0) {
                //全项目：范围最宽，只在没有目录级规则命中时兜底
                matchedScopeLength = 0;
                extensionMatch = listTreeInfo;
            }
        }
        return extensionMatch;
    }

    private static boolean isNotEmpty(String value) {
        return null != value && !value.trim().isEmpty();
    }

    /**
     * 去掉手写路径末尾多余的斜杠，否则 /src/ 拼出来的 /src// 永远匹配不上。
     * 只写 "/" 的等于项目根目录，归一成空串后按「整个项目」处理。
     */
    private static String trimTrailingSlash(String path) {
        if (null == path) {
            return null;
        }
        String result = path.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public static void Navigation(Project project, String path) {
        //类型规则没有 path，跳转不到具体文件，直接忽略
        if (project != null && isNotEmpty(path)) {
            final ProjectViewImpl instance = (ProjectViewImpl) ProjectView.getInstance(project);
            final VirtualFile file = VfsUtil.findFile(new File(project.getBasePath() + path).toPath(), false);
            if (null != file) {
                final PsiManager instance1 = PsiManager.getInstance(project);
                if (file.isDirectory()) {
                    final PsiDirectory directory = instance1.findDirectory(file);
                    instance.selectPsiElement(directory, true);
                } else {
                    final PsiFile file1 = instance1.findFile(file);
                    instance.selectPsiElement(file1, true);
                }
            }
        }
    }

    /**
     * 获取 VirtualFile
     *
     * @param abstractTreeNode 对象
     */
    public static VirtualFile getVirtualFile(AbstractTreeNode<?> abstractTreeNode) {
        if (null != abstractTreeNode) {
            Method[] methods1 = abstractTreeNode.getClass().getMethods();
            Object value = abstractTreeNode.getValue();
            if (null != value) {
                Method[] methods2 = value.getClass().getMethods();
                VirtualFile virtualFile2 = getVirtualFile(methods2, value);
                if (null == virtualFile2) {
                    if (abstractTreeNode instanceof AbstractPsiBasedNode) {
                        final AbstractPsiBasedNode abstractTreeNode1 = (AbstractPsiBasedNode) abstractTreeNode;
                        Method[] methods3 = AbstractPsiBasedNode.class.getDeclaredMethods();
                        return getVirtualFileForValue(methods3, abstractTreeNode1);
                    }
                }
            }
            return getVirtualFile(methods1, abstractTreeNode);
        }
        return null;
    }

    /**
     * 获取到 VirtualFile
     *
     * @param methods 方法
     * @param o       对象
     * @return VirtualFile
     */
    private static VirtualFile getVirtualFile(Method[] methods, Object o) {
        for (Method method : methods) {
            if ("getVirtualFile".equals(method.getName())) {
                method.setAccessible(true);
                try {
                    Object invoke = method.invoke(o);
                    if (invoke instanceof VirtualFile) {
                        return (VirtualFile) invoke;
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    private static VirtualFile getVirtualFileForValue(Method[] methods, Object o) {
        for (Method method : methods) {
            if ("getVirtualFileForValue".equals(method.getName())) {
                method.setAccessible(true);
                try {
                    Object invoke = method.invoke(o);
                    if (invoke instanceof VirtualFile) {
                        return (VirtualFile) invoke;
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }
}
