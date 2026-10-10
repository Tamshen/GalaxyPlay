package com.shilapi.xcertplay

import android.app.PendingIntent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

/** 公开签名替身只用于验证重载选择，不包含或运行反编译 SDK。 */
class GalaxyOemMediaContractTest {
    open class Info {
        open fun getTitle(): String? = null
        open fun getArtist(): String? = null
        open fun getAlbum(): String? = null
        open fun getAppIcon(): String? = null
        open fun getAppName(): String? = null
        open fun getPackageName(): String? = null
        open fun getUuid(): String? = null
        open fun getDuration(): Long = 0
        open fun getArtwork(): Uri? = null
        open fun getSourceType(): Int = 6
        open fun getPlaybackStatus(): Int = 0
        open fun getLaunchIntent(): PendingIntent? = null
        open fun getPlayerIntent(): PendingIntent? = null
    }
    open class Client {
        open fun onPlay(): Boolean = false
        open fun onPause(): Boolean = false
        open fun onNext(): Boolean = false
        open fun onPrevious(): Boolean = false
        open fun onSourceSelected(source: Int): Boolean = false
        open fun onMediaCenterFocusChanged(name: String?) {}
        open fun getCurrentSourceType(): Int = 0
        open fun getMediaSourceTypeList(): IntArray = intArrayOf()
        open fun getCurrentProgress(): Long = 0
        open fun getMusicPlaybackInfo(): Info? = null
    }
    open class Legacy {
        var packageName: String? = null
        var callback: Client? = null
        open fun registerMusic(name: String, client: Client): Any? {
            packageName = name; callback = client; return client
        }
        fun registerMusic(client: Runnable): Any? = error("占位重载不能执行")
        fun requestPlay(token: Any): Boolean = true
        fun unregister(token: Any): Boolean = true
        fun updateMusicPlaybackState(token: Any, info: Info): Boolean = true
        fun updateMediaSourceTypeList(token: Any, values: IntArray): Boolean = true
        fun updateCurrentProgress(token: Any, value: Long) {}
        fun updateCurrentSourceType(token: Any, value: Int) {}
        fun queryCurrentFocusClient(token: Any): String? = packageName
    }
    class Modern : Legacy() {
        var sessionPackage: String? = null
        fun registerMusic(name: String, client: Client, session: String): Any? {
            sessionPackage = session; return super.registerMusic(name, client)
        }
    }
    class FinalCallback : Client() { final override fun onNext(): Boolean = false }
    @Test fun modernRegistrationLinksOwnSessionAndNeverCallsPlaceholderOverload() {
        val contract = GalaxyOemMediaContract(Modern::class.java, Client::class.java, Info::class.java)
        val api = Modern(); val client = Client()
        assertTrue(contract.linkedSession)
        assertSame(client, contract.register(api, "synthetic.own", client))
        assertEquals("synthetic.own", api.packageName)
        assertEquals("synthetic.own", api.sessionPackage)
        assertSame(client, api.callback)
        assertFalse(contract.clientMethods.any { it.name == "onCustomAction" })
    }
    @Test fun verifiedOlderOverloadRetainsActualIdentityAndClient() {
        val contract = GalaxyOemMediaContract(Legacy::class.java, Client::class.java, Info::class.java)
        val api = Legacy(); val client = Client()
        assertFalse(contract.linkedSession)
        assertSame(client, contract.register(api, "synthetic.own", client))
        assertEquals("synthetic.own", api.packageName)
    }
    @Test fun incompatibleRuntimeClientFailsBeforeRegistration() {
        // 该类型不能匹配注册重载，不能退回无包名的占位方法。
        assertTrue(runCatching { GalaxyOemMediaContract(Legacy::class.java, FinalCallback::class.java, Info::class.java) }.isFailure)
        assertTrue(runCatching { GalaxyOemMediaContract(Legacy::class.java, Runnable::class.java, Info::class.java) }.isFailure)
    }
}
