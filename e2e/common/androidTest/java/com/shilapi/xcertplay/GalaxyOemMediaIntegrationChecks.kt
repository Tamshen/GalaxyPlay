package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import com.ecarx.eas.sdk.mediacenter.MediaCenterAPI
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** 在 Android VM 调用实际产品适配器，SDK 服务替身仅检查契约与数据。 */
object GalaxyOemMediaIntegrationChecks {
    @JvmStatic fun run(base: Context) {
        for (model in listOf("l6", "l7")) {
            val context = GalaxyConfigurationContext(base, GalaxyProfile("synthetic", "synthetic", 1, 0,
                GalaxyConfigurationFields.factory(base, model)), runtimeOnly = true)
            val sdk = MediaCenterAPI.reset()
            val ready = mutableListOf<Boolean>()
            val commands = mutableListOf<Int>()
            val port = L7ReflectiveMediaCenter(context, sdkLoader = {
                L7VendorSdk.Loaded(MediaCenterAPI::class.java.classLoader!!, "SYNTHETIC_SDK")
            }, serviceEvidence = { 1 })
            port.initialize(ready::add, { commands.add(it); true }, {}, { it == port.source })
            check(ready == listOf(true))
            check(sdk.sdkContext === context)
            check(L7AudioTemplates.model(sdk.sdkContext).id == model)
            val value = CarPlayNowPlaying(title = "synthetic first", artist = "synthetic", album = "synthetic",
                playing = true, playbackKnown = true, durationMillis = 180123, elapsedMillis = 1234, artworkTransferId = 1)
            val cover = Uri.parse("content://${base.packageName}.synthetic/first")
            port.prepare(value, cover)
            check(port.register())
            check(sdk.packageName == context.packageName && sdk.mediaSessionPackage == context.packageName)
            check(port.sources(intArrayOf(port.source)))
            port.currentSource()
            check(sdk.source == 6 && sdk.sources.contentEquals(intArrayOf(6)))
            check(port.requestPlay() && port.focusClient() == context.packageName)
            check(port.update(value, cover))
            port.progress(5432)
            val first = sdk.client
            check(first.currentProgress == 5432L && sdk.progress == 5432L)
            val info = first.musicPlaybackInfo
            check(info === sdk.published && info.title == value.title && info.artwork == cover)
            check(info.duration == 180123L && info.playbackStatus == 1 && info.sourceType == 6)
            check(info.packageName == context.packageName && info.appIcon.startsWith("android.resource://${context.packageName}/"))
            check(info.launchIntent != null && info.playerIntent == info.launchIntent)
            check(info.vip == 0 && info.playingMediaListType == -1)
            check(!info.isSupportCollect && !info.isSupportDownload && !info.isSupportLoopModeSwitch)
            check(info.isSupportVrCtrlPlayStatus)
            check(first.onPlay() && first.onPause() && first.onNext() && first.onPrevious())
            check(commands == listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS))
            check(!first.onSourceChanged(6, "synthetic.previous") && commands.size == 4)
            check(first.onSourceSelected(6) && !first.onSourceSelected(12))
            val uuid = info.uuid
            port.prepare(value.copy(title = "synthetic second", artworkTransferId = null), null)
            val secondInfo = first.musicPlaybackInfo
            check(secondInfo.uuid != uuid && secondInfo.artwork == null)
            check(info.artwork == null && info.launchIntent == null)
            check(port.unregister())
            check(!first.onNext() && first.musicPlaybackInfo == null)
            port.prepare(value, cover)
            check(port.register())
            check(sdk.client !== first && !first.onPlay() && sdk.client.onPlay())
            port.invalidate()
            check(!sdk.client.onPlay() && sdk.client.musicPlaybackInfo == null)
            check(port.sources(intArrayOf()) && port.unregister())
            port.close()
            check(sdk.unregistrations == 2 && sdk.releases == 1)
            check(commands.size == 5)
        }
        invalidTokenIsRejected(base)
    }

    private fun invalidTokenIsRejected(context: Context) {
        val sdk = MediaCenterAPI.reset().apply { invalidToken = true }
        val port = L7ReflectiveMediaCenter(context, sdkLoader = {
            L7VendorSdk.Loaded(MediaCenterAPI::class.java.classLoader!!, "SYNTHETIC_SDK")
        }, serviceEvidence = { 1 })
        port.initialize({}, { error("INVALID_TOKEN_CONTROL") }, {}, { false })
        check(!port.register())
        check(!sdk.client.onPlay())
        port.close()
    }
}
