package com.plugins.infotip

import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 检测旧 id 的 TreeInfotip 是否还装着，装着就提示用户只保留一个。
 *
 * 本插件从 5.2.0 起换成独立的 id [com.github.yc556.treeinfotip]，所以旧 id
 * [OLD_PLUGIN_ID] 现在可能来自两处：Marketplace 上原作者的那个插件，或者用户
 * 自己装的本插件 5.1.x 及更早的构建。两种情况的处理方式一样——留一个就行。
 *
 * 两份插件不会写坏配置：`DirectoryV3.xml` 各自解析进自己类加载器里的
 * [com.plugins.infotip.storage.XmlStorage] 静态缓存，写入只发生在用户点菜单的时候，
 * 另一边靠 PSI 监听重新解析。真正的代价是每次重绘都算两遍，而且两个装饰入口的执行
 * 顺序不定，旧版跑在后面时会把新版才有的悬浮提示、覆盖显示名称等覆盖掉。
 *
 * @author yc-2018
 */
object OldPluginConflictNotifier {

    /** 原版插件的 id，也是本插件 5.1.x 及更早版本用的 id */
    private const val OLD_PLUGIN_ID = "com.linkkou.plugin.intellij.assistant"

    /** 对应 plugin.xml 里注册的 `<notificationGroup id="TreeInfotip"/>` */
    private const val NOTIFICATION_GROUP = "TreeInfotip"

    /** 一个 IDE 会话只提示一次，同时开着多个项目也不会弹好几遍 */
    private val notified = AtomicBoolean(false)

    private val log = Logger.getInstance(OldPluginConflictNotifier::class.java)

    /**
     * 在启动活动里调用。整段包了兜底：提示失败绝不能影响装饰功能本身。
     */
    @JvmStatic
    fun checkAndNotify(project: Project) {
        try {
            val oldId = PluginId.getId(OLD_PLUGIN_ID)
            // 装了并且没被停用才算冲突
            if (PluginManagerCore.getPlugin(oldId) == null || PluginManagerCore.isDisabled(oldId)) return
            if (!notified.compareAndSet(false, true)) return

            val manager = NotificationGroupManager.getInstance()
            if (!manager.isGroupRegistered(NOTIFICATION_GROUP)) {
                log.warn("通知分组 $NOTIFICATION_GROUP 没注册上，跳过旧版插件提示")
                return
            }
            // 留一条日志：用户回报"右键菜单出现两个目录备注"时，看日志就能确认是这个原因
            log.info("检测到旧 id 插件 $OLD_PLUGIN_ID 仍在启用，弹出冲突提示")

            manager.getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "检测到旧版 TreeInfotip",
                    "旧 id（$OLD_PLUGIN_ID）的 TreeInfotip 还在启用中。两边读的是同一个 DirectoryV3.xml，" +
                            "备注不会丢，但目录树会被装饰两遍、右键菜单里会出现两个「目录备注」，" +
                            "而且旧版跑在后面时会把悬浮提示、覆盖显示名称这些新设置覆盖掉。建议只保留一个。",
                    NotificationType.WARNING
                )
                .setImportant(true)
                .addAction(NotificationAction.createSimple("打开插件管理") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginManagerConfigurable::class.java)
                })
                .notify(project)
        } catch (e: Throwable) {
            log.warn("旧版插件冲突检测失败", e)
        }
    }
}
