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
            check(sdk.sourceController.writes == 0 && sdk.sourceController.reads == 0)
            check(sdk.packageName == context.packageName && sdk.mediaSessionPackage == context.packageName)
            check(port.sources(intArrayOf(port.source)))
            awaitFact(port, "catalogPresent", 1L)
            check(sdk.sourceController.writes == 1)
            check(sdk.sourceDeclarations.get() == 2) // 首次声明在前，目录修复后仅重申一次。
            check(port.diagnosticFacts()["runtimeSourcePresent"] == 0L)
            val catalog = sdk.sourceController.configuration
            check(catalog.first().packageName == "synthetic.foreign")
            check(catalog.last().packageName == context.packageName)
            check(catalog.last().appName == context.getString(com.shilapi.xcertplay.host.R.string.app_name))
            check(catalog.last().sourceTypeList.contentEquals(intArrayOf(6, 13)))
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
            // 应用主页与当前会话播放入口使用独立 action／请求码，不能复用同一个 PendingIntent。
            check(info.launchIntent != null && info.playerIntent != null && info.playerIntent != info.launchIntent)
            check(info.launchIntent.creatorPackage == context.packageName && info.playerIntent.creatorPackage == context.packageName)
            check(info.vip == 0 && info.playingMediaListType == -1)
            check(!info.isSupportCollect && !info.isSupportDownload && !info.isSupportLoopModeSwitch)
            check(info.isSupportVrCtrlPlayStatus)
            check(first.onPlay() && first.onPause() && first.onNext() && first.onPrevious())
            check(commands == listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS))
            check(!first.onSourceChanged(6, "synthetic.previous") && commands.size == 4)
            check(first.onSourceSelected(6) && !first.onSourceSelected(12))
            val uuid = info.uuid
            checkLyricUpdates(port, sdk, value, cover, uuid)
            port.prepare(value.copy(title = "synthetic second", artworkTransferId = null), null)
            val secondInfo = first.musicPlaybackInfo
            check(secondInfo.uuid != uuid && secondInfo.artwork == null)
            check(info.artwork == null && info.launchIntent == null)
            check(port.unregister())
            check(!first.onNext() && first.musicPlaybackInfo == null)
            port.prepare(value, cover)
            check(port.register())
            check(port.sources(intArrayOf(port.source)))
            awaitFact(port, "catalogPresent", 1L)
            check(sdk.sourceDeclarations.get() == 3) // 目录已存在，不重复写入或重申。
            check(sdk.sourceController.writes == 1 && sdk.sourceController.configuration.size == 2)
            check(sdk.client !== first && !first.onPlay() && sdk.client.onPlay())
            port.invalidate()
            check(!sdk.client.onPlay() && sdk.client.musicPlaybackInfo == null)
            check(port.sources(intArrayOf()) && port.unregister())
            port.close()
            check(sdk.unregistrations == 2 && sdk.releases == 1)
            check(commands.size == 5)
        }
        invalidTokenIsRejected(base)
        unavailableCatalogKeepsMusicClient(base)
    }

    private fun checkLyricUpdates(port: L7ReflectiveMediaCenter, sdk: MediaCenterAPI,
                                 song: CarPlayNowPlaying, cover: Uri, uuid: String) {
        // 检查实际适配器生成的 SDK 快照，防止测试预先传入固定 UUID 而绕过曲目标识生成。
        for ((index, title) in listOf("合成歌词第一句", "合成歌词第二句", "合成歌词第三句").withIndex()) {
            val lyric = song.copy(title = title, elapsedMillis = 6000L + index * 3000L)
            port.prepare(lyric, cover)
            check(port.update(lyric, cover))
            check(sdk.published.uuid == uuid) { "歌词更新不能更换曲目 UUID" }
            check(sdk.published.title == title && sdk.published.artwork == cover)
            port.progress(lyric.elapsedMillis!! + 1000)
            check(sdk.client.musicPlaybackInfo.uuid == uuid)
        }
        // 同一歌手的下一首仍须区分；作者、专辑、时长和封面传输编号继续参与曲目标识。
        for (next in listOf(song.copy(artist = "另一位合成歌手"), song.copy(album = "另一张合成专辑"),
                song.copy(durationMillis = 240000), song.copy(artworkTransferId = 2))) {
            port.prepare(song, cover)
            val previous = sdk.client.musicPlaybackInfo.uuid
            port.prepare(next, cover)
            check(port.update(next, cover))
            check(sdk.published.uuid != previous) { "歌曲信息变化仍须更新曲目 UUID" }
        }
        port.prepare(song, cover)
    }

    private fun invalidTokenIsRejected(context: Context) {
        val sdk = MediaCenterAPI.reset().apply { invalidToken = true }
        val port = L7ReflectiveMediaCenter(context, sdkLoader = {
            L7VendorSdk.Loaded(MediaCenterAPI::class.java.classLoader!!, "SYNTHETIC_SDK")
        }, serviceEvidence = { 1 })
        port.initialize({}, { error("INVALID_TOKEN_CONTROL") }, {}, { false })
        check(!port.register())
        check(sdk.sourceController.writes == 0 && sdk.sourceController.reads == 0)
        check(!sdk.client.onPlay())
        port.close()
    }

    private fun unavailableCatalogKeepsMusicClient(context: Context) {
        for (missing in listOf(true, false)) {
            val sdk = MediaCenterAPI.reset().apply {
                unavailableCatalog = missing
                sourceController.rejectWrite = !missing
            }
            val port = L7ReflectiveMediaCenter(context, sdkLoader = {
                L7VendorSdk.Loaded(MediaCenterAPI::class.java.classLoader!!, "SYNTHETIC_SDK")
            }, serviceEvidence = { 1 })
            port.initialize({}, { true }, {}, { true })
            port.prepare(CarPlayNowPlaying(title = "synthetic", playing = true, playbackKnown = true), null)
            check(port.register() && port.sources(intArrayOf(6)))
            awaitFact(port, "catalog", GalaxyMediaReportEvidence.catalogResults.indexOf(
                if (missing) "NO_CONTROLLER" else "UNAVAILABLE_CALL").toLong())
            check(port.update(CarPlayNowPlaying(), null))
            check(sdk.published.title == "synthetic")
            check(port.unregister())
            port.close()
        }
    }

    private fun awaitFact(port: L7ReflectiveMediaCenter, key: String, expected: Long) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000L
        while (port.diagnosticFacts()[key] != expected && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
        }
        check(port.diagnosticFacts()[key] == expected) { "$key expected=$expected actual=${port.diagnosticFacts()}" }
    }
}
