package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 公开签名替身，不携带原厂代码，也不把反射调用通过当作实车注册成功。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class GalaxyNavigationSdkTest {
    interface Callback { fun onAPIReady(value: Boolean) }
    interface BaseClient
    interface Client : BaseClient {
        fun setMapEventListener(value: Event)
        fun getGuideInfo(request: Request, callback: Reply)
        fun cancelNavi(request: Request, callback: Reply)
        fun invokeMapAPI(request: Base): Base
    }
    interface Event { fun onMapStatusChanged(value: Int); fun onGuideInfoChanged(value: Guide) }
    interface Reply { fun onSuccess(value: Base); fun onFail(value: Error) }
    open class Base {
        var packageName = ""
        var mapVendor = 0
    }
    class Info : Base()
    class Request : Base()
    class Error(val request: Base?) : Base() { var errorCode = 0 }
    class Guide : Base() {
        var guideType = 0
        var roadType = 0
        var turnIconId = 0
        var nextTurnDistance = 0
        var remainDistance = 0
        var remainTime = 0
        var currentRoadName = ""
        var nextRoadName = ""
    }
    open class Api {
        open fun init(context: Context, callback: Callback) {}
        open fun release() {}
        open fun registerMapClient(info: Info, vararg clients: BaseClient): Base? = null
        open fun unregisterMapClient(vararg clients: BaseClient) {}
        companion object {
            lateinit var instance: Api
            @JvmStatic fun get(context: Context): Api = instance
        }
    }
    class Service : Api() {
        lateinit var context: Context
        lateinit var ready: Callback
        lateinit var client: Client
        lateinit var info: Info
        val states = mutableListOf<Int>()
        val models = mutableListOf<Guide>()
        var result: Base? = Base().apply { mapVendor = 72 }
        var unregisterFailure = false
        var releases = 0
        var unregisters = 0
        override fun init(context: Context, callback: Callback) { this.context = context; ready = callback }
        override fun registerMapClient(info: Info, vararg clients: BaseClient): Base? {
            this.info = info
            client = clients.single() as Client
            client.setMapEventListener(object : Event {
                override fun onMapStatusChanged(value: Int) { states += value }
                override fun onGuideInfoChanged(value: Guide) { models += value }
            })
            return result
        }
        override fun unregisterMapClient(vararg clients: BaseClient) {
            assertSame(client, clients.single())
            unregisters++
            if (unregisterFailure) throw SecurityException()
        }
        override fun release() { releases++ }
    }
    private val types = mapOf(
        "MapAPI" to Api::class.java, "ECarXApiClient\$Callback" to Callback::class.java,
        "IMapClient" to Client::class.java, "IBaseMapClient" to BaseClient::class.java,
        "MapClientInfo" to Info::class.java, "GuidingInfoModel" to Guide::class.java,
        "IMapEventListener" to Event::class.java, "NaviBaseModel" to Base::class.java,
        "NaviErrorModel" to Error::class.java, "IMapAPICallback" to Reply::class.java,
        "GetGuideInfoRequest" to Request::class.java,
    )
    private val contract = GalaxyNavigationContract(javaClass.classLoader!!) { types.getValue(it.substringAfterLast('.')) }
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var service: Service
    private var route: CarPlayNavigationSnapshot? = CarPlayNavigationSnapshot(true, "next-road", "current-road", 1,
        segmentRemainingMeters = 300, routeRemainingMeters = 5000, routeRemainingSeconds = 600,
        arrivalEpochSeconds = 1_800_000_000)
    @Before fun setup() { service = Service(); Api.instance = service }

    @Test fun realReflectionPathRegistersOwnIdentityAndUsesSdkListenerAndModels() {
        val sdk = GalaxyNavigationSdk(app, { route }, contract)
        var ready = false
        sdk.initialize { ready = it }
        assertSame(app, service.context)
        assertFalse(ready); service.ready.onAPIReady(true); assertTrue(ready)
        lateinit var listener: GalaxyNavigationListener
        val vendor = sdk.register { listener = it }
        assertEquals(app.packageName, service.info.packageName)
        listener.status(16); listener.guidance(route!!, vendor)
        val model = service.models.single()
        assertEquals(72, model.mapVendor)
        assertEquals(app.packageName, model.packageName)
        assertEquals(102, model.turnIconId)
        assertEquals(300, model.nextTurnDistance)
        assertEquals(5000, model.remainDistance)
        assertEquals(600, model.remainTime)
        assertEquals("current-road", model.currentRoadName)
        assertEquals("next-road", model.nextRoadName)
        listener.status(17); sdk.close()
        assertEquals(listOf(16, 17), service.states)
        assertEquals(1, service.unregisters); assertEquals(1, service.releases)
    }
    @Test fun systemQueriesSeeLatestRouteAndUnsupportedControlReturnsFailure() {
        val sdk = GalaxyNavigationSdk(app, { route }, contract)
        sdk.initialize {}; sdk.register {}
        var success: Base? = null
        var failure: Error? = null
        val reply = object : Reply {
            override fun onSuccess(value: Base) { success = value }
            override fun onFail(value: Error) { failure = value }
        }
        route = route!!.copy(segmentRemainingMeters = 5)
        service.client.getGuideInfo(Request(), reply)
        assertEquals(5, (success as Guide).nextTurnDistance)
        service.client.cancelNavi(Request(), reply)
        assertEquals(10028, failure!!.errorCode)
        assertEquals(10028, (service.client.invokeMapAPI(Request()) as Error).errorCode)
        route = null
        service.client.getGuideInfo(Request(), reply)
        assertEquals(10004, failure!!.errorCode)
        sdk.close()
    }
    @Test fun nullAndErrorRegistrationNeverCountAsAccepted() {
        for (result in listOf(null, Error(null).apply { errorCode = 10024 })) {
            setup(); service.result = result
            val sdk = GalaxyNavigationSdk(app, { route }, contract)
            sdk.initialize {}
            val failure = runCatching { sdk.register {} }.exceptionOrNull()
            assertNotNull(failure)
            if (result != null) assertEquals(10024, (failure as GalaxyNavigationRegistrationException).code)
            sdk.close()
        }
    }
    @Test fun releaseStillRunsAfterUnregisterFailureAndLateReadyIsIgnored() {
        val sdk = GalaxyNavigationSdk(app, { route }, contract)
        var callbacks = 0
        sdk.initialize { callbacks++ }; sdk.register {}
        service.unregisterFailure = true
        assertTrue(runCatching { sdk.close() }.isFailure)
        service.ready.onAPIReady(true)
        sdk.close()
        assertEquals(0, callbacks); assertEquals(1, service.releases)
    }
    @Test fun missingValuesDoNotBecomeArrivalAndLargeNumbersDoNotOverflow() {
        val model = contract.model(CarPlayNavigationSnapshot(active = true, routeRemainingMeters = Long.MAX_VALUE),
            app.packageName, 5) as Guide
        assertEquals(-1, model.turnIconId)
        assertEquals(-1, model.nextTurnDistance)
        assertEquals(-1, model.remainTime)
        assertEquals(Int.MAX_VALUE, model.remainDistance)
    }
    @Test fun sdkPlaceholderIsRejectedBeforeCallingInit() {
        Api.instance = Api()
        val sdk = GalaxyNavigationSdk(app, { route }, contract)
        assertTrue(runCatching { sdk.initialize {} }.isFailure)
        sdk.close()
    }
    @Test fun sdkReadyCallbackIsReusedAndOldDetachCannotRemoveCurrentOwner() {
        var oldCalls = 0
        var newCalls = 0
        val old = GalaxyNavigationReadyCallback.bind(Callback::class.java) { oldCalls++ }
        val next = GalaxyNavigationReadyCallback.bind(Callback::class.java) { newCalls++ }
        assertSame(old.callback, next.callback)
        old.close()
        (next.callback as Callback).onAPIReady(true)
        assertEquals(0, oldCalls); assertEquals(1, newCalls)
        next.close()
        (next.callback as Callback).onAPIReady(true)
        assertEquals(1, newCalls)
    }
    @Test fun turnsUseOemIdsAndDrivingSideWithoutGuessingUnknown() {
        assertEquals(102, GalaxyNavigationIcons.fromCarPlay(1, 0))
        assertEquals(103, GalaxyNavigationIcons.fromCarPlay(2, 0))
        assertEquals(108, GalaxyNavigationIcons.fromCarPlay(4, 0))
        assertEquals(119, GalaxyNavigationIcons.fromCarPlay(4, 1))
        assertEquals(121, GalaxyNavigationIcons.fromCarPlay(28, 0))
        assertEquals(131, GalaxyNavigationIcons.fromCarPlay(28, 1))
        assertEquals(130, GalaxyNavigationIcons.fromCarPlay(37, 0))
        assertEquals(-1, GalaxyNavigationIcons.fromCarPlay(255, 0))
    }
}
