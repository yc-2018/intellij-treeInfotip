package com.plugins.infotip.storage;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.psi.xml.XmlFile;
import org.javatuples.Pair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE_ARRAY;

/**
 * A <code>XmlUtils</code> Class
 *
 * @author lk
 * @version 1.0
 * <p><b>date: 2023/4/13 12:39</b></p>
 */
public class XmlFileUtils {
    /**
     * 现在用的配置文件名。
     *
     * <p>
     * 5.x 一直叫 {@code DirectoryV3.xml}，6.0.0 跟着大版本号改成 V6：抽离过路径前缀的文件旧版插件读不了
     * （它不认 {@code prefix} 属性，会把相对部分当成完整路径），换个名字就让新旧两版各找各的文件，
     * 不会互相读坏。老文件<b>不在启动时改名</b>——没抽离过的文件旧版读得好好的，开个 IDE 就把用户
     * 项目里一个会进版本库的文件改名太唐突。改名只在用户点了「抽离」并保存时才由
     * {@link #renameConfigFile} 做，见 {@code XmlPrefixDialog}。
     * </p>
     */
    private static final String XMLFileName = "DirectoryV6.xml";

    /**
     * 现在用的文件名，给界面文案用
     */
    public static String currentFileName() {
        return XMLFileName;
    }

    /**
     * 老的文件名，给界面文案用
     */
    public static String legacyFileName() {
        return LEGACY_XML_FILE_NAME;
    }

    /**
     * 5.x 及之前的文件名，只在还没有 V6 时读它，读到就改名
     */
    private static final String LEGACY_XML_FILE_NAME = "DirectoryV3.xml";

    /**
     * 通知分组 id，必须和 {@code plugin.xml} 里注册的那个一致
     */
    private static final String NOTIFICATION_GROUP = "TreeInfoTip Notes";

    private final static ConcurrentHashMap<Project, XmlFile> XML_STORAGE_File = new ConcurrentHashMap<Project, XmlFile>();

    private static final Map<Object, SaveCallback> callbackList = new ConcurrentHashMap<Object, SaveCallback>();

    public interface Callback {
        /**
         * 修改路径
         *
         * @param asBasePathOrExtension 绝对路径
         * @param x                     对象
         * @param fileDirectoryXml      对象
         * @param project               对象
         */
        void onModifyPath(List<Pair<String, String>> asBasePathOrExtension, List<XmlEntity> x, XmlFile fileDirectoryXml, Project project);

        /**
         * 创建路径
         *
         * @param asBasePathOrExtension 绝对路径
         * @param fileDirectoryXml      对象
         * @param project               对象
         */
        void onCreatePath(List<Pair<String, String>> asBasePathOrExtension, XmlFile fileDirectoryXml, Project project);
    }

    public interface SaveCallback {
        /**
         * 运行
         */
        void run();
    }

    /**
     * 获取基础路径
     *
     * @param anActionEvent 对象
     * @param callback      回调
     */
    public static void runActionType(AnActionEvent anActionEvent, Callback callback) {
        final Project project = anActionEvent.getProject();
        if (null == project) {
            return;
        }
        //获取文件、文件夹等对象
        //VirtualFile file = VIRTUAL_FILE.getData(anActionEvent.getDataContext());
        final VirtualFile[] files = VIRTUAL_FILE_ARRAY.getData(anActionEvent.getDataContext());
        if (null != files) {
            XmlFile fileXml = loadXmlFile(project);
            //使用相对路径
            String basePath = project.getPresentableUrl();
            if (null != basePath && basePath.length() > 0) {
                //改为安长度去除
                //此处改进
                List<Pair<String, String>> pathInfo = new ArrayList<Pair<String, String>>();
                for (VirtualFile file : files) {
                    String presentableUrl = file.getCanonicalPath();
                    if (presentableUrl.length() < basePath.length()) {
                        Messages.showMessageDialog(project, "无法获取该文件的根路径", "获取路径失败", Messages.getErrorIcon());
                        break;
                    }
                    String asBasePath = presentableUrl.substring(basePath.length(), presentableUrl.length());
                    String extension = file.getExtension();
                    pathInfo.add(Pair.with(asBasePath, extension));
                }
                if (null == fileXml) {
                    final XmlFile xmlFile = createXmlFile(project);
                    callback.onCreatePath(pathInfo, xmlFile, project);
                } else {
                    final List<XmlEntity> xmlEntitys = XmlStorage.getXmlEntity(project);
                    if (null == xmlEntitys) {
                        XmlStorage.parsing(project, fileXml);
                    }
                    final ArrayList<XmlEntity> newXmlEntity = new ArrayList<XmlEntity>();
                    if (null != xmlEntitys) {
                        if (xmlEntitys.size() == 0) {
                            for (Pair<String, String> path : pathInfo) {
                                newXmlEntity.add(new XmlEntity().setPath(path.getValue0()));
                            }
                        } else {
                            for (Pair<String, String> pair : pathInfo) {
                                boolean find = false;
                                for (XmlEntity x : xmlEntitys) {
                                    if (isPathRule(x) && pair.getValue0().equals(x.getPath())) {
                                        find = true;
                                        newXmlEntity.add(x);
                                    }
                                }
                                if (!find) {
                                    newXmlEntity.add(new XmlEntity().setPath(pair.getValue0()));
                                }
                            }
                        }
                        callback.onModifyPath(pathInfo, newXmlEntity, fileXml, project);
                    }
                }
            }
        } else {
            Messages.showMessageDialog(project, "无法获取项目的根路径", "获取路径失败", Messages.getErrorIcon());
        }
    }

    public static void ListenerSave(Object id, SaveCallback callback) {
        callbackList.put(id, callback);
    }

    /**
     * 是否为「路径规则」，即只绑定单个文件或目录的那种。
     * <p>
     * 带 extension 的是「类型规则」，一条会命中一批同扩展名的文件。针对单个节点的菜单
     * 不能顺手把它改掉，否则改一个文件的备注会连带影响整批文件，所以匹配时要排除。
     * </p>
     */
    private static boolean isPathRule(XmlEntity xmlEntity) {
        final String extension = xmlEntity.getExtension();
        return null == extension || extension.trim().isEmpty();
    }

    /**
     * 新项目第一次配置时写出去的 {@code DirectoryV6.xml} 模板
     * <p>
     * {@code <trees>} 下面那段注释是给手改文件的人看的。这个文件躺在项目根目录，用户迟早会点开它，
     * 而 {@code presentableText}、{@code tooltipTitle} 这些参数名单看名字猜不全，更猜不出命中优先级。
     * </p>
     * <p>
     * 6.0.0 之前这段注释还要避开「格式化」按钮的正则（{@code <trees>}、带空格的 {@code <tree }、
     * 行尾 {@code >} 接行首 {@code <}），那个按钮已经随 XML 工具窗口一起去掉了，约束不再适用。
     * XML 注释本身不允许出现连续两个减号，这条仍然有效。
     * </p>
     */
    private static final String XML_TEMPLATE = String.join("\r\n",
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
      "<!-- JetBrains 安装插件【TreeInfoTip Notes / 目录树备注】 安装后就能看到目录后面的备注信息 -->",
      "<trees>",
      "</trees>",
      "<!--",
      "  TreeInfoTip Notes 的配置文件：一条 <tree> 就是一条规则，存盘立刻生效，不用重启 IDE。",
      "  平时不用手写，右键项目树上的文件或目录，走「目录备注」菜单加就行。",
      "",
      "  参数全是可选的，按需要写几个：",
      "    path             相对项目根目录的路径，以 / 开头；只写 / 表示整个项目",
      "    extension        扩展名，不带点，只作用于文件；和 path 一起写表示该目录连子目录下的这类文件",
      "    title            备注文字，灰色跟在节点名后面",
      "    presentableText  覆盖节点显示的名字",
      "    tooltipTitle     鼠标悬浮时的提示，可以写多行",
      "    icon             换图标，填 AllIcons 里的字段路径，例如 Nodes.Folder",
      "    textColor        文字颜色，十进制 r,g,b，例如 255,0,0",
      "    backgroundColor  背景色，写法同上",
      "    strikethrough    填 true 给节点加删除线",
      "    prefix           引用下面 prefixes 里声明的某个 id，path 只写剩下的那截",
      "",
      "  路径前缀可以抽出来，重复的长目录只写一遍：先在 trees 里加一个 prefixes 段，",
      "  里面一条 prefix 声明一个 id 和它的完整路径，再让 tree 用 prefix= 指过去。",
      "  这件事不用手写，侧边栏「目录备注」工具栏上的「抽离或还原路径前缀」按钮来回切。",
      "  抽不抽看净收益：条数 x (前缀长 - id长 - 10) - (前缀长 + id长 + 25) 为正才抽，",
      "  没有「几条起步」的门槛，长目录 2 条就够本，/src 这种再多条也抽不出来。",
      "  id 可以手改成看得懂的名字（中文也行），重新抽离时会留着不动。",
      "",
      "  命中优先级：path 全等的最高，其次 path 加 extension（path 更长的赢），",
      "  最后是只写 extension 的全项目规则。同优先级时写在前面的那条赢，所以侧边栏",
      "  「目录备注」里的「置顶」是真的把标签挪到文件最前面。",
      "-->"
      );

    /**
     * 创建文件
     *
     * @param project 项目
     * @return XmlFile
     */
    public static XmlFile createXmlFile(Project project) {
        if (project == null) {
            return null;
        }
        LanguageFileType xml = (LanguageFileType) FileTypeManager.getInstance().getStdFileType("XML");
        PsiFile pf = PsiFileFactory.getInstance(project).createFileFromText(XMLFileName, xml, XML_TEMPLATE);
        //新建一律用 V6，老名字只在迁移时出现
        return loadSaveFileXml(project, pf.getText(), XMLFileName);
    }

    /**
     * 加载文件
     *
     * @param project 项目
     * @return XmlFile
     */
    public static XmlFile loadXmlFile(Project project) {
        if (project == null) {
            return null;
        }
        final VirtualFile virtualFile = findConfigFile(project);
        if (null == virtualFile) {
            return null;
        }
        final XmlFile xmlFile = findXmlPsi(project, virtualFile);
        if (null != xmlFile) {
            XML_STORAGE_File.put(project, xmlFile);
        }
        return xmlFile;
    }

    /**
     * 找配置文件：V6 优先，没有就退回老的 V3
     *
     * <p>
     * 这里只读不改名。改名是 {@link #renameConfigFile} 的事，它要开写操作，
     * 而本方法的调用点里有在写操作和 PSI 事件回调里的，不能在那些地方再嵌一层。
     * </p>
     *
     * @param project 项目
     * @return 找不到返回 null
     */
    public static VirtualFile findConfigFile(Project project) {
        if (project == null || project.getBasePath() == null) {
            return null;
        }
        final LocalFileSystem lfs = LocalFileSystem.getInstance();
        final VirtualFile current = lfs.refreshAndFindFileByIoFile(
                new File(project.getBasePath() + File.separator + XMLFileName));
        if (null != current) {
            return current;
        }
        return lfs.refreshAndFindFileByIoFile(
                new File(project.getBasePath() + File.separator + LEGACY_XML_FILE_NAME));
    }

    /**
     * 这个项目当前在用哪个配置文件名
     *
     * <p>
     * 给界面文案用。已经迁移过或者是新项目就是 V6，还没迁移的老项目还是 V3，
     * 说明文字要跟着变，不然用户按提示去找文件会找不到。
     * </p>
     *
     * @param project 项目
     * @return 文件名，一个都没有时给 V6
     */
    public static String configFileName(Project project) {
        final VirtualFile file = findConfigFile(project);
        return null == file ? XMLFileName : file.getName();
    }

    /**
     * 当前用的是不是老的 V3 文件名
     *
     * @param project 项目
     * @return 只有在确实读到 V3 时才是 true
     */
    public static boolean isOnLegacyName(Project project) {
        return LEGACY_XML_FILE_NAME.equals(configFileName(project));
    }

    /**
     * 改名之后发一条通知
     *
     * <p>
     * 动的是用户项目里会进版本库的文件，不能一声不响：他下次提交会看到一个删除加一个新增，
     * 得知道是插件干的、为什么干的。
     * </p>
     *
     * @param project 项目
     * @param from    原文件名
     * @param to      新文件名
     */
    public static void notifyRenamed(Project project, String from, String to) {
        final NotificationGroupManager manager = NotificationGroupManager.getInstance();
        if (!manager.isGroupRegistered(NOTIFICATION_GROUP)) {
            return;
        }
        final String why = XMLFileName.equals(to)
                ? "抽离过路径前缀的文件旧版插件读不了（它不认 prefix 属性），换个名字新旧两版就各读各的。"
                : "文件里已经没有 prefix 了，改回旧名字旧版插件就能重新读到它。";
        manager.getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("配置文件已改名",
                        from + " 已经改名为 " + to + "，内容一个字没动。" + why,
                        NotificationType.INFORMATION)
                .notify(project);
    }

    /**
     * 把配置文件改成另一个名字
     *
     * <p>
     * 两个方向都走这里：抽离保存时 V3 → V6，还原时用户选了「换回 V3」就 V6 → V3。
     * 目标名字已经存在就不动——那种情况用户自己清楚在做什么，插件不替他决定丢哪份。
     * </p>
     * <p>
     * 它要开写操作，别在写操作或 PSI 事件回调里调。
     * </p>
     *
     * @param project    项目
     * @param targetName 目标文件名，只接受本类认识的那两个
     * @return 真的改名了返回 true
     */
    public static boolean renameConfigFile(Project project, String targetName) {
        if (project == null || project.getBasePath() == null) {
            return false;
        }
        if (!XMLFileName.equals(targetName) && !LEGACY_XML_FILE_NAME.equals(targetName)) {
            return false;
        }
        final String currentName = XMLFileName.equals(targetName) ? LEGACY_XML_FILE_NAME : XMLFileName;
        final LocalFileSystem lfs = LocalFileSystem.getInstance();
        //目标已经在了就不动，免得把用户的另一份覆盖掉
        if (null != lfs.refreshAndFindFileByIoFile(new File(project.getBasePath() + File.separator + targetName))) {
            return false;
        }
        final VirtualFile current = lfs.refreshAndFindFileByIoFile(
                new File(project.getBasePath() + File.separator + currentName));
        if (null == current) {
            return false;
        }
        final boolean[] renamed = new boolean[1];
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                current.rename(XmlFileUtils.class, targetName);
                renamed[0] = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        return renamed[0];
    }

    /**
     * 把 {@link VirtualFile} 转成 PSI
     *
     * <p>
     * 读 PSI 要读锁，而新版平台的 EDT 不再隐式持有它，从 Swing 监听器调过来就会抛
     * {@code Read access is allowed from inside read-action only}。所以在这里自己包一层读操作，
     * 每个调用点就不用各自记得包了。
     * </p>
     * <p>
     * VFS 刷新必须留在这个方法<b>外面</b>：同步刷新自己要开写操作，套进读操作里是换一个错。
     * </p>
     *
     * @param project     项目
     * @param virtualFile 文件
     * @return XmlFile，不是 XML 就返回 null
     */
    private static XmlFile findXmlPsi(Project project, VirtualFile virtualFile) {
        final PsiFile[] holder = new PsiFile[1];
        ApplicationManager.getApplication().runReadAction(() -> {
            holder[0] = PsiManager.getInstance(project).findFile(virtualFile);
        });
        return holder[0] instanceof XmlFile ? (XmlFile) holder[0] : null;
    }

    /**
     * 获取文件
     *
     * @param project 项目
     * @return XmlFile
     */
    public static XmlFile getXmlFile(Project project) {
        return XML_STORAGE_File.get(project);
    }

    /**
     * 获取文件
     *
     * <p>
     * 落盘用的是缓存里那个 PsiFile 自己的文件名，<b>不能</b>硬写 V6：项目还没迁移过来时
     * 缓存里是 V3，硬写会凭空造出第二份配置，两边内容从此各走各的。
     * </p>
     *
     * @param project 项目
     * @return XmlFile
     */
    public static XmlFile saveFileXml(Project project) {
        final XmlFile xmlFile = XML_STORAGE_File.get(project);
        if (null == xmlFile) {
            return null;
        }
        final VirtualFile virtualFile = xmlFile.getVirtualFile();
        final String fileName = null == virtualFile ? XMLFileName : virtualFile.getName();
        return loadSaveFileXml(project, xmlFile.getText(), fileName);
    }

    /**
     * 是否为指定的文件
     *
     * @param psiTreeChangeEvent 对象
     * @return boolean
     */
    public static boolean isFileName(PsiTreeChangeEvent psiTreeChangeEvent) {
        final PsiFile file = psiTreeChangeEvent.getFile();
        if (null != file) {
            final VirtualFile virtualFile = file.getVirtualFile();
            if (null != virtualFile) {
                return isFileName(virtualFile.getName());
            }
        }
        return false;
    }

    /**
     * 是否为指定的文件
     *
     * <p>老的 V3 也要认：还没迁移过来的项目读的就是它，改动同样要触发重新解析。</p>
     *
     * @param name 名称
     * @return boolean
     */
    public static boolean isFileName(String name) {
        return XMLFileName.equals(name) || LEGACY_XML_FILE_NAME.equals(name);
    }


    /**
     * 保存文件
     *
     * @param project  项目
     * @param text     文件不存在时写进去的内容
     * @param fileName 要落盘的文件名，见 {@link #saveFileXml} 和 {@link #createXmlFile} 的区别
     */
    private static synchronized XmlFile loadSaveFileXml(Project project, String text, String fileName) {
        File f = new File(project.getBasePath() + File.separator + fileName);
        if (!f.exists()) {
            try {
                boolean newFile = f.createNewFile();
                if (newFile) {
                    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8))) {
                        writer.write(text);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
        if (null != virtualFile) {
            virtualFile.refresh(false, true);
            final XmlFile xmlFile = findXmlPsi(project, virtualFile);
            if (null != xmlFile) {
                XML_STORAGE_File.put(project, xmlFile);
                for (Map.Entry<Object, SaveCallback> objectSaveCallbackEntry : callbackList.entrySet()) {
                    objectSaveCallbackEntry.getValue().run();
                }
                return xmlFile;
            }
        }
        return null;
    }
}
