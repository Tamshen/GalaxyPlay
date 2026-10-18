package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.lang.reflect.Method

/** 原厂公开目录接口独立于 MusicClient；目录不可用不能撤销已经有效的音乐注册。 */
internal object GalaxyOemSourceRegistration {
    fun ensure(context: Context, api: Any, apiType: Class<*>, source: Int, active: () -> Boolean): String = try {
        val access = apiType.getMethod("getEasMediaController")
        val sdk = Contract(apiType)
        val controller = access.invoke(api)
        when {
            controller == null -> "NO_CONTROLLER"
            !sdk.controller.isInstance(controller) -> "INCOMPATIBLE_CONTROLLER"
            else -> GalaxyOemSourceCatalog.ensure(context.packageName, source,
                { sdk.read(controller) }, { old -> sdk.create(context, source, old) },
                { sdk.write.invoke(controller, it) }, active)
        }
    } catch (_: ClassNotFoundException) { "UNAVAILABLE_CLASS" }
      catch (_: NoSuchMethodException) { "UNAVAILABLE_METHOD" }
      catch (_: SecurityException) { "UNAVAILABLE_ACCESS" }
      catch (_: Exception) { "UNAVAILABLE_CALL" }
      catch (_: LinkageError) { "UNAVAILABLE_LINKAGE" }

    private class Contract(apiType: Class<*>) {
        private val loader = apiType.classLoader
        val controller = Class.forName("ecarx.xsf.mediacenter.session.EasMediaController", false, loader)
        private val item = Class.forName("ecarx.xsf.mediacenter.session.AppSourceInfo", false, loader)
        private val builder = Class.forName("ecarx.xsf.mediacenter.session.AppSourceInfo\$Builder", false, loader)
        private val get = controller.getMethod("getAppSourceConfig")
        val write: Method = controller.getMethod("setAppSourceConfig", List::class.java)
        private val fields = listOf("getPackageName", "getAppName", "getIconPath", "getSourceTypeList",
            "getPriorityLevel", "isRunning", "isSupportMultipleOpen", "getIconRes").associateWith { item.getMethod(it) }

        fun read(target: Any): List<GalaxyOemSourceCatalog.Entry<Any>>? {
            val values = get.invoke(target) as? List<*> ?: return null
            // 服务会序列化整个列表；未核对类型及内嵌 Bitmap 不能无损写回。
            if (values.any { !item.isInstance(it) || fields.getValue("getIconRes").invoke(it) != null }) return null
            return values.map { snapshot(requireNotNull(it)) }
        }

        private fun snapshot(value: Any): GalaxyOemSourceCatalog.Entry<Any> {
            fun field(name: String) = fields.getValue(name).invoke(value)
            val data = GalaxyOemSourceCatalog.Fields(field("getPackageName") as String?, field("getAppName") as String?,
                field("getIconPath") as String?, (field("getSourceTypeList") as IntArray?)?.toList().orEmpty(),
                field("getPriorityLevel") as Int, field("isRunning") as Boolean, field("isSupportMultipleOpen") as Boolean)
            return GalaxyOemSourceCatalog.Entry(value, data)
        }

        fun create(context: Context, source: Int, old: GalaxyOemSourceCatalog.Entry<Any>?): GalaxyOemSourceCatalog.Entry<Any> {
            val value = builder.getConstructor().newInstance()
            fun set(name: String, type: Class<*>, argument: Any) { builder.getMethod(name, type).invoke(value, argument) }
            val desired = if (source == 0) listOf(0) else listOf(6, 13)
            val sources = (old?.fields?.sources.orEmpty() + desired).distinct().toIntArray()
            set("packageName", String::class.java, context.packageName)
            set("appName", String::class.java, context.getString(R.string.app_name))
            set("iconPath", String::class.java, "android.resource://${context.packageName}/${context.applicationInfo.icon}")
            set("sourceTypeList", IntArray::class.java, sources)
            set("priorityLevel", Int::class.javaPrimitiveType!!, old?.fields?.priority ?: 2)
            set("isRunning", Boolean::class.javaPrimitiveType!!, true)
            set("supportMultipleOpen", Boolean::class.javaPrimitiveType!!, old?.fields?.multipleOpen ?: false)
            return snapshot(requireNotNull(builder.getMethod("build").invoke(value)))
        }
    }
}
