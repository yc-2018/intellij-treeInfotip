package com.plugins.infotip;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.psi.xml.XmlFile;
import com.plugins.infotip.storage.XmlChangeListener;
import com.plugins.infotip.storage.XmlFileUtils;
import com.plugins.infotip.storage.XmlStorage;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目启动的时候执行
 *
 * @author LK
 * @date 2018-04-07 1:18
 */
public class PluginStartupActivity implements StartupActivity {

    private static final Map<Object, RunCallback> callbackList = new ConcurrentHashMap<Object, RunCallback>();

    public interface RunCallback {
        /**
         * 运行
         */
        void run();
    }

    public static void ListenerRun(Object id, RunCallback callback) {
        callbackList.put(id, callback);
    }

    @Override
    public void runActivity(@NotNull Project project) {
        //这里不动配置文件的名字。改名只在用户点了「抽离」并保存时才发生——开个 IDE 就把
        //用户项目里一个会进版本库的文件改名，太唐突了，而且没抽离过的文件旧版读得好好的
        final XmlFile xmlFile = XmlFileUtils.loadXmlFile(project);
        XmlStorage.parsing(project, xmlFile);
        for (Map.Entry<Object, RunCallback> objectRunCallbackEntry : callbackList.entrySet()) {
            objectRunCallbackEntry.getValue().run();
        }
        XmlChangeListener.run(project);
        // 放在最后：提示只是锦上添花，装饰功能优先跑完
        OldPluginConflictNotifier.checkAndNotify(project);
    }

}