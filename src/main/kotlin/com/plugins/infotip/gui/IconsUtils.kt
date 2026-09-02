package com.plugins.infotip.gui

import com.intellij.openapi.util.IconLoader
import com.plugins.infotip.gui.entity.IconEntity
import javax.swing.Icon

/**
 * 反射 `com.intellij.icons.AllIcons`，把 IDE 内置的全部图标摊平成一个列表，
 * 供颜色/图标对话框的下拉框使用。图标名（如 `Actions.Edit`）会原样写进 XML。
 *
 * 注意这是全项目最脆弱的一块：JetBrains 删掉或改名任何一个图标字段，
 * 老配置里的名字就静默匹配不上（[com.plugins.infotip.trees.TreesStyle] 里查不到就不设图标）。
 *
 * @author lk
 */
object IconsUtils {

    private const val CLASS_NAME = "com.intellij.icons.AllIcons"

    /** Java 侧按 `IconsUtils.MyBatisIcon` 访问，所以必须是 `@JvmField`（否则只有 getter） */
    @JvmField
    val MyBatisIcon: Icon = IconLoader.getIcon("/icons/mybatis.png", IconsUtils::class.java)

    //必须声明在下面 init 块之前：object 的属性和 init 按书写顺序执行
    private val ICONS = ArrayList<IconEntity>()

    init {
        //用 runCatching 而不是 catch (Exception)：它连 Throwable 一起兜住。
        //静态初始化里漏出去的异常会变成 ExceptionInInitializerError，
        //而 AllIcons 在新版 IDE 上整个换掉时抛的正是 NoClassDefFoundError 这类 Error，
        //兜不住就是插件直接加载失败。兜住了最坏也只是"没有图标可选"。
        runCatching {
            for (clazz in flattenNested(Class.forName(CLASS_NAME).classes)) {
                //类名去掉 AllIcons 前缀再去掉 $，得到 "Actions." / "General." 这样的前缀
                val prefix = clazz.name.removePrefix(CLASS_NAME).replace("$", "")
                for (field in clazz.fields) {
                    if (field.type != Icon::class.java) continue
                    val icon = field.get(null) as? Icon ?: continue
                    ICONS += IconEntity(icon, prefix + field.name)
                }
            }
        }.onFailure { it.printStackTrace() }
    }

    /** 返回的是内部列表本身（沿用原行为，调用方只读） */
    @JvmStatic
    fun getAllIcons(): ArrayList<IconEntity> = ICONS

    /**
     * 递归摊平 AllIcons 下的嵌套类（Actions / General / Nodes …，还有更深一层的）。
     * 先递归再放自己，和原 Java 版的后序顺序一致，下拉框里的排列不变。
     */
    private fun flattenNested(classes: Array<Class<*>>): List<Class<*>> =
        classes.flatMap { flattenNested(it.classes) + it }
}
