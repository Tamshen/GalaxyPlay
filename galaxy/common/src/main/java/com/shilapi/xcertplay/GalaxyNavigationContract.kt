package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot

/** 原厂 MapAPI 公开契约；类型和模型均来自同一个已安装 SDK，不自行拼 Binder 事务。 */
internal class GalaxyNavigationContract(loader: ClassLoader,
    private val resolve: (String) -> Class<*> = { Class.forName(it, false, loader) },
) {
    private fun type(name: String) = resolve(name)
    val api = type("com.ecarx.eas.framework.sdk.navi.MapAPI")
    val callback = type("com.ecarx.eas.sdk.ECarXApiClient\$Callback")
    val client = type("com.ecarx.eas.mapsdk.interfaces.IMapClient")
    val baseClient = type("com.ecarx.eas.mapsdk.interfaces.IBaseMapClient")
    val clientInfo = type("com.ecarx.eas.mapsdk.model.MapClientInfo")
    val guide = type("com.ecarx.eas.mapsdk.model.response.GuidingInfoModel")
    val event = type("com.ecarx.eas.mapsdk.interfaces.IMapEventListener")
    val base = type("com.ecarx.sdk.navi.model.base.NaviBaseModel")
    val error = type("com.ecarx.sdk.navi.model.base.NaviErrorModel")
    val reply = type("com.ecarx.eas.mapsdk.interfaces.IMapAPICallback")
    private val clients = java.lang.reflect.Array.newInstance(baseClient, 0).javaClass
    val register = api.getMethod("registerMapClient", clientInfo, clients)
    val unregister = api.getMethod("unregisterMapClient", clients)
    val initialize = api.getMethod("init", Context::class.java, callback)
    val release = api.getMethod("release")
    val get = api.getMethod("get", Context::class.java)
    val status = event.getMethod("onMapStatusChanged", Int::class.javaPrimitiveType)
    val guidance = event.getMethod("onGuideInfoChanged", guide)
    val success = reply.getMethod("onSuccess", base)
    val fail = reply.getMethod("onFail", error)
    private val setPackage = base.getMethod("setPackageName", String::class.java)
    private val setVendor = base.getMethod("setMapVendor", Int::class.javaPrimitiveType)
    val vendor = base.getMethod("getMapVendor")
    val errorCode = error.getMethod("getErrorCode")
    private val setError = error.getMethod("setErrorCode", Int::class.javaPrimitiveType)
    private val setters = (listOf("GuideType", "RoadType", "TurnIconId", "NextTurnDistance", "RemainDistance", "RemainTime")
        .associateWith { guide.getMethod("set$it", Int::class.javaPrimitiveType) } +
        listOf("CurrentRoadName", "NextRoadName").associateWith { guide.getMethod("set$it", String::class.java) })

    init {
        require(client.isInterface && callback.isInterface && baseClient.isAssignableFrom(client))
        require(register.returnType == base && api.isAssignableFrom(get.returnType))
        client.getMethod("setMapEventListener", event)
        client.getMethod("getGuideInfo", type("com.ecarx.eas.mapsdk.model.request.GetGuideInfoRequest"), reply)
        clientInfo.getConstructor()
        guide.getConstructor()
        error.getConstructor(base)
    }

    fun clientArray(value: Any): Any = java.lang.reflect.Array.newInstance(baseClient, 1).also {
        java.lang.reflect.Array.set(it, 0, value)
    }
    fun registrationInfo(packageName: String): Any = clientInfo.getConstructor().newInstance().also {
        setPackage.invoke(it, packageName)
    }
    fun failure(request: Any?, code: Int): Any = error.getConstructor(base)
        .newInstance(request?.takeIf(base::isInstance)).also { setError.invoke(it, code) }

    fun model(value: CarPlayNavigationSnapshot, packageName: String, mapVendor: Int): Any =
        guide.getConstructor().newInstance().also { model ->
            setPackage.invoke(model, packageName)
            setVendor.invoke(model, mapVendor)
            val fields = mapOf(
                "GuideType" to 0, "RoadType" to 7,
                "TurnIconId" to GalaxyNavigationIcons.fromCarPlay(value.maneuverType, value.drivingSide),
                "CurrentRoadName" to value.currentRoad.orEmpty(),
                "NextRoadName" to (value.nextRoad ?: value.road).orEmpty(),
                "NextTurnDistance" to number(value.segmentRemainingMeters),
                "RemainDistance" to number(value.routeRemainingMeters),
                "RemainTime" to number(value.routeRemainingSeconds),
            )
            fields.forEach { (name, field) -> setters.getValue(name).invoke(model, field) }
        }

    // 缺少距离不能冒充到达；使用未知值，避免 Long 转 Int 溢出变成负距离。
    private fun number(value: Long?): Int = value?.takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1
}

/** 按原厂 CarPlay tbt.cfg 的语义映射；与地图 SDK 的 102 左转、103 右转编号一致。 */
internal object GalaxyNavigationIcons {
    fun fromCarPlay(type: Int?, drivingSide: Int): Int {
        val leftTraffic = drivingSide == 1
        return when (type) {
            0, 3 -> 109
            1, 20 -> 102
            2, 21 -> 103
            4, 18, 26 -> if (leftTraffic) 119 else 108
            5 -> 120
            6 -> if (leftTraffic) 117 else 111
            7 -> if (leftTraffic) 118 else 112
            8 -> 189
            9 -> 190
            10 -> 191
            11 -> 192
            12 -> 193
            13, 49 -> 104
            14, 50 -> 105
            15 -> 184
            16 -> 194
            17 -> 195
            19 -> 196
            22 -> 197
            23 -> 198
            24 -> 199
            25 -> 200
            27 -> 201
            in 28..37 -> (if (leftTraffic) 131 else 121) + (type!! - 28)
            in 38..46 -> 183
            47 -> 202
            48 -> 203
            51 -> 204
            52 -> 205
            53 -> 206
            else -> -1
        }
    }
}
