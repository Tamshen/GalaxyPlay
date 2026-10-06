package com.shilapi.xcertplay

import android.net.Uri
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class L7MediaCenterSessionTest {
    private class Queue : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Port : L7MediaCenterPort {
        lateinit var ready: (Boolean) -> Unit
        lateinit var command: (Int) -> Boolean
        lateinit var focus: (String?) -> Unit
        lateinit var selected: (Int) -> Boolean
        override var source = L7MediaCenterPort.CARPLAY_SOURCE
        val calls = mutableListOf<String>()
        val updates = mutableListOf<CarPlayNowPlaying>()
        val artworks = mutableListOf<Uri?>()
        var token = true
        var acceptSource = true
        var failState = false
        var acceptState = true
        var acceptPlay = true
        var failPlay = false
        var duringRequest: () -> Unit = {}
        var duringPrepare: () -> Unit = {}
        var failUnregister = false
        var currentFocus: String? = null
        var duringRegister: () -> Unit = {}
        override fun initialize(ready: (Boolean) -> Unit, command: (Int) -> Boolean, focus: (String?) -> Unit, selected: (Int) -> Boolean) {
            this.ready = ready; this.command = command; this.focus = focus; this.selected = selected; calls += "init"
        }
        override fun prepare(value: CarPlayNowPlaying, artwork: Uri?) { calls += "prepare"; duringPrepare() }
        override fun register(): Boolean { calls += "register"; duringRegister(); return token }
        override fun sources(values: IntArray): Boolean { calls += "sources:${values.joinToString()}"; return acceptSource }
        override fun currentSource() { calls += "current" }
        override fun requestPlay(): Boolean {
            calls += "request"
            duringRequest()
            if (failPlay) throw SecurityException("private-play-error")
            return acceptPlay
        }
        override fun focusClient() = currentFocus
        override fun update(value: CarPlayNowPlaying, artwork: Uri?): Boolean {
            calls += "state"; updates += value; artworks += artwork
            if (failState) throw SecurityException("private-error")
            return acceptState
        }
        override fun progress(milliseconds: Long) { calls += "progress:$milliseconds" }
        override fun invalidate() { calls += "invalidate" }
        override fun unregister(): Boolean { calls += "unregister"; if (failUnregister) throw SecurityException(); return true }
        override fun close() { calls += "close" }
    }
    private val port = Port()
    private val worker = Queue()
    private val main = Queue()
    private var current = true
    private val sent = mutableListOf<Int>()
    private val logs = mutableListOf<String>()
    private val retries = java.util.ArrayDeque<Runnable>()
    private val session = L7MediaCenterSession(port, "own", { current }, { index, _ -> sent += index }, worker, main, logs::add, retry = { retries.add(it) })
    @Test fun diagnosticTraceStartsBeforeOemFilteringAndRejectsLateMainDelivery() {
        val store = L7SteeringDiagnostics.store
        store.clear()
        var delivered = 0
        val traced = L7MediaCenterSession(port, "own", { current }, { _, _ -> delivered++ }, worker, main, logs::add,
            traceSend = { _, _, _ -> delivered++ })
        traced.start(); worker.drain(); port.ready(true); worker.drain()
        assertTrue(port.command(CarPlayMediaButton.NEXT))
        val ticket = store.snapshot().events.last { it.stage == "OEM_MAIN_QUEUED" }.id
        traced.close()
        main.drain()
        assertEquals(0, delivered)
        assertEquals("LATE_OEM_CALLBACK", store.snapshot().events.last { it.id == ticket }.detail)
        worker.drain()
    }

    @Test fun bluetoothReportsOwnMetadataWithoutRequestingSourceOrForwardingControls() {
        val passive = L7MediaCenterSession(port, "own", { current }, { index, _ -> sent += index },
            worker, main, logs::add, localPlayback = false, retry = { retries.add(it) })
        passive.start(); worker.drain(); port.ready(true); worker.drain()
        passive.update(playing(300)); worker.drain()
        assertTrue(port.calls.contains("state"))
        assertTrue(port.calls.contains("progress:300"))
        for (index in listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT))
            assertFalse(port.command(index))
        assertFalse(port.selected(port.source))
        port.focus("own"); worker.drain()
        port.focus("vehicle-bluetooth"); worker.drain(); main.drain()
        assertTrue(sent.isEmpty())
        assertFalse(port.calls.contains("request"))
        assertFalse(port.calls.contains("current"))
        port.ready(false); worker.drain(); port.ready(true); worker.drain()
        assertEquals(2, port.calls.count { it == "register" })
        passive.close(); worker.drain()
        assertFalse(port.command(CarPlayMediaButton.PLAY))
        assertTrue(port.calls.contains("unregister"))
    }

    @Test fun bluetoothTokenAndSourceRejectionNeverRequestsPlay() {
        val passive = L7MediaCenterSession(port, "own", { current }, { index, _ -> sent += index },
            worker, main, logs::add, localPlayback = false, retry = { retries.add(it) })
        port.token = false
        passive.start(); worker.drain(); port.ready(true); worker.drain()
        passive.update(playing()); worker.drain()
        assertFalse(port.command(CarPlayMediaButton.PLAY))
        port.token = true; port.acceptSource = false
        retries.removeFirst().run(); worker.drain()
        assertTrue(port.calls.contains("unregister"))
        assertFalse(port.calls.contains("request"))
        assertFalse(port.calls.contains("state"))
        passive.close(); worker.drain()
    }

    private fun connect() { session.start(); worker.drain(); port.ready(true); worker.drain() }
    private fun playing(elapsed: Long = 0) = CarPlayNowPlaying(title = "private-title", playing = true, playbackKnown = true, elapsedMillis = elapsed)

    @Test fun aNewTransferDoesNotReusePreviousArtworkWhileProgressKeepsCurrentArtwork() {
        connect()
        val cover = Uri.parse("content://own/first.jpg")
        session.update(playing().copy(artworkTransferId = 1), cover); worker.drain()
        session.update(playing(100).copy(artworkTransferId = 1)); worker.drain()
        assertEquals(cover, port.artworks.last())
        session.update(playing().copy(title = "next", artworkTransferId = 2)); worker.drain()
        assertNull(port.artworks.last())
        session.update(playing().copy(title = "next", artworkTransferId = null)); worker.drain()
        assertNull(port.artworks.last())
    }

    @Test fun delayedRetryRecoversPausedMetadataWithoutAnotherPhoneEvent() {
        connect(); port.acceptState = false
        session.update(playing().copy(playing = false)); worker.drain()
        port.acceptState = true; retries.removeFirst().run(); worker.drain()
        assertEquals(2, port.calls.count { it == "state" })
        assertFalse(port.calls.contains("request"))
    }
    @Test fun delayedRegistrationAndStateRetriesAreBoundedAndClosedSessionIgnoresThem() {
        port.token = false; connect()
        repeat(2) { retries.removeFirst().run(); worker.drain() }
        assertTrue(retries.isEmpty())
        assertEquals(3, port.calls.count { it == "register" })
        session.close(); worker.drain()
        port.ready(true); worker.drain()
        assertEquals(3, port.calls.count { it == "register" })
    }
    @Test fun pendingRetryPublishesLatestPauseAndCannotReplayAfterClose() {
        connect(); port.acceptState = false; session.update(playing()); worker.drain()
        session.update(playing().copy(playing = false)); worker.drain()
        port.acceptState = true; retries.removeFirst().run(); worker.drain()
        assertFalse(port.updates.last().playing)
        port.acceptState = false; session.update(playing().copy(title = "next")); worker.drain()
        val count = port.updates.size; session.close(); worker.drain()
        retries.removeFirst().run(); worker.drain(); assertEquals(count, port.updates.size)
    }

    @Test fun snapshotExistsBeforeRequestAndRejectedUpdateRetriesWithoutRequestLoop() {
        connect(); port.acceptState = false
        session.update(playing()); worker.drain()
        assertTrue(port.calls.lastIndexOf("prepare") < port.calls.indexOf("request"))
        port.acceptState = true
        session.update(playing()); worker.drain(); session.update(playing()); worker.drain()
        assertEquals(2, port.calls.count { it == "state" })
        assertEquals(1, port.calls.count { it == "request" })
    }
    @Test fun unchangedRejectedStateHasBoundedRetriesButNewSongCanPublish() {
        connect(); port.acceptState = false
        repeat(10) { session.update(playing(it.toLong())); worker.drain() }
        assertEquals(3, port.calls.count { it == "state" })
        port.acceptState = true
        session.update(playing().copy(title = "next-song")); worker.drain()
        assertEquals("next-song", port.updates.last().title)
        assertEquals(4, port.calls.count { it == "state" })
        assertEquals(1, port.calls.count { it == "request" })
    }
    @Test fun failedReadyCanRetryAndRuntimeSourceDrivesRegistrationAndSelection() {
        port.source = 6; port.token = false; connect()
        port.token = true; port.ready(true); worker.drain()
        assertEquals(2, port.calls.count { it == "register" })
        assertTrue(port.calls.contains("sources:6"))
        assertFalse(port.selected(13)); assertTrue(port.selected(6)); worker.drain()
    }
    @Test fun stateExceptionDoesNotCacheSongOrLoseLatestArtworkRetry() {
        connect(); port.failState = true
        session.update(playing(), Uri.parse("content://test/cover")); worker.drain()
        port.failState = false; session.update(playing(), Uri.parse("content://test/cover")); worker.drain()
        assertEquals(2, port.calls.count { it == "state" })
        session.update(playing().copy(title = "next-song")); worker.drain()
        assertEquals("next-song", port.updates.last().title)
    }
    @Test fun initialForeignFocusStillRequestsAndPublishesCurrentSong() {
        port.currentFocus = "other"; connect(); session.update(playing()); worker.drain()
        assertEquals(1, port.calls.count { it == "request" })
        assertTrue(port.calls.indexOf("request") < port.calls.indexOf("current"))
        assertTrue(port.calls.indexOf("current") < port.calls.indexOf("state"))
        assertTrue(port.calls.contains("progress:0"))
        assertEquals(1, port.calls.count { it == "state" })
        port.focus("own"); worker.drain()
        assertEquals(2, port.calls.count { it == "state" })
        assertEquals(1, port.calls.count { it == "request" })
    }

    @Test fun registrationDoesNotWaitForAudioAndDuplicateReadyDoesNotRegisterAgain() {
        connect(); session.start(); port.ready(true); worker.drain()
        assertEquals(1, port.calls.count { it == "register" })
        assertFalse(port.calls.contains("request"))
        assertFalse(port.calls.contains("state"))
        assertTrue(port.command(CarPlayMediaButton.PLAY)); main.drain()
        assertEquals(listOf(CarPlayMediaButton.PLAY), sent)
    }
    @Test fun nullTokenAndRejectedSourceCannotForwardCommands() {
        port.token = false; connect()
        assertFalse(port.command(CarPlayMediaButton.NEXT))
        port.token = true; port.ready(false); port.ready(true); port.acceptSource = false; worker.drain()
        assertFalse(port.command(CarPlayMediaButton.PLAY))
        assertTrue(port.calls.contains("unregister"))
    }
    @Test fun progressCoalescesWithoutRepeatedPlayRequestsOrMetadata() {
        connect(); session.update(playing()); worker.drain()
        session.update(playing(10)); session.update(playing(20)); worker.drain()
        assertEquals(1, port.calls.count { it == "request" })
        assertEquals(1, port.calls.count { it == "state" })
        assertFalse(port.calls.contains("progress:10"))
        assertTrue(port.calls.contains("progress:20"))
        assertFalse(logs.any { "private-title" in it })
    }
    @Test fun stateFailureDoesNotSuppressIndependentProgressAndDoesNotLeakExceptionMessage() {
        connect(); port.failState = true; session.update(playing(1200)); worker.drain()
        assertTrue(port.calls.contains("progress:1200"))
        assertTrue(logs.any { "exceptionType=SecurityException" in it })
        assertFalse(logs.any { "private-error" in it })
    }
    @Test fun closeRejectsQueuedAndLateCallbacksAndAlwaysAttemptsRelease() {
        connect(); assertTrue(port.command(CarPlayMediaButton.NEXT))
        port.failUnregister = true; session.close(); session.close(); main.drain(); worker.drain()
        assertTrue(sent.isEmpty())
        assertFalse(port.command(CarPlayMediaButton.PLAY))
        assertEquals(1, port.calls.count { it == "invalidate" })
        assertEquals(1, port.calls.count { it == "close" })
        assertTrue(port.calls.contains("sources:"))
    }
    @Test fun disconnectDuringBlockedRegistrationCannotPublishOldSource() {
        session.start(); worker.drain(); port.duringRegister = { session.close() }
        port.ready(true); worker.drain()
        assertFalse(port.calls.contains("sources:13"))
        assertTrue(port.calls.contains("close"))
    }
    @Test fun replacedOwnerRejectsQueuedCommandsEvenBeforeCloseRuns() {
        connect(); assertTrue(port.command(CarPlayMediaButton.NEXT)); current = false
        main.drain(); session.update(playing()); worker.drain()
        assertTrue(sent.isEmpty()); assertFalse(port.calls.contains("state"))
    }
    @Test fun serviceRecoveryIsLimitedAndCannotLoopRegistration() {
        connect()
        repeat(6) { port.ready(false); port.ready(true) }; worker.drain()
        assertEquals(3, port.calls.count { it == "register" })
        assertTrue(logs.any { "registerLimit" in it })
    }
    @Test fun firstForeignFocusDoesNotPausePhoneButOwnToForeignTransitionDoes() {
        port.currentFocus = "other"; connect(); session.update(playing()); worker.drain(); main.drain()
        assertTrue(sent.isEmpty()); assertEquals(1, port.calls.count { it == "request" })
        port.focus("own"); worker.drain(); port.focus("other"); worker.drain(); main.drain()
        assertEquals(listOf(CarPlayMediaButton.PAUSE), sent)
        assertFalse(port.command(CarPlayMediaButton.NEXT))
        session.update(playing(100).copy(title = "next-song")); worker.drain()
        assertEquals("next-song", port.updates.last().title)
        assertTrue(port.calls.contains("progress:100"))
        assertEquals(1, port.calls.count { it == "request" })
    }

    @Test fun pausedSongPublishesUnderForeignFocusWithoutClaimingPlayback() {
        port.currentFocus = "other"; connect()
        session.update(playing(900).copy(playing = false)); worker.drain()
        assertFalse(port.calls.contains("request"))
        assertFalse(port.updates.single().playing)
        assertTrue(port.calls.contains("progress:900"))
    }

    @Test fun rejectedPlaybackRetriesWithoutAnotherPhoneUpdateAndSelectsOnlyAfterAcceptance() {
        port.currentFocus = "other"; port.acceptPlay = false; connect()
        session.update(playing(500)); worker.drain()
        assertFalse(port.calls.contains("current"))
        assertEquals(1, port.updates.size)
        assertTrue(port.calls.contains("progress:500"))
        repeat(5) { session.update(playing(600L + it)); worker.drain() }
        assertEquals(1, port.calls.count { it == "request" })
        assertEquals(1, retries.size)
        port.acceptPlay = true; port.currentFocus = "own"
        retries.removeFirst().run(); worker.drain()
        assertEquals(2, port.calls.count { it == "request" })
        assertEquals(1, port.calls.count { it == "current" })
        assertTrue(port.command(CarPlayMediaButton.NEXT)); main.drain()
        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
    }

    @Test fun playRequestLimitSurvivesSongChangesAndNewPlayingTransitionCanRetry() {
        port.acceptPlay = false; connect(); session.update(playing()); worker.drain()
        repeat(2) { retries.removeFirst().run(); worker.drain() }
        assertTrue(retries.isEmpty())
        repeat(5) { session.update(playing(it.toLong()).copy(title = "song-$it")); worker.drain() }
        assertEquals(3, port.calls.count { it == "request" })
        assertEquals("song-4", port.updates.last().title)
        session.update(playing().copy(playing = false)); worker.drain()
        port.acceptPlay = true; session.update(playing()); worker.drain()
        assertEquals(4, port.calls.count { it == "request" })
        assertTrue(port.calls.contains("current"))
    }

    @Test fun requestExceptionDoesNotBlockMetadataOrLeakPrivateMessage() {
        port.failPlay = true; connect(); session.update(playing(400)); worker.drain()
        assertEquals(1, port.updates.size)
        assertTrue(port.calls.contains("progress:400"))
        assertTrue(logs.any { "requestPlay exceptionType=SecurityException" in it })
        assertFalse(logs.any { "private-play-error" in it })
    }

    @Test fun pauseOrCloseCancelsDelayedPlaybackClaim() {
        port.acceptPlay = false; connect(); session.update(playing()); worker.drain()
        session.update(playing().copy(playing = false)); worker.drain()
        retries.removeFirst().run(); worker.drain()
        assertEquals(1, port.calls.count { it == "request" })
        session.update(playing()); worker.drain(); session.close(); worker.drain()
        retries.removeFirst().run(); worker.drain()
        assertEquals(2, port.calls.count { it == "request" })
    }

    @Test fun unknownPlaybackCancelsOldRetryAndCannotClaimFromStalePlayingState() {
        port.acceptPlay = false; connect(); session.update(playing()); worker.drain()
        session.update(CarPlayNowPlaying()); worker.drain()
        retries.removeFirst().run(); worker.drain()
        assertEquals(1, port.calls.count { it == "request" })
        port.acceptPlay = true; session.update(playing()); worker.drain()
        assertEquals(2, port.calls.count { it == "request" })
    }

    @Test fun pauseDuringRequestCannotAnnounceOldPlayingSource() {
        connect()
        port.duringRequest = { session.update(playing().copy(playing = false)) }
        session.update(playing()); worker.drain()
        assertEquals(1, port.calls.count { it == "request" })
        assertFalse(port.calls.contains("current"))
        assertFalse(port.updates.last().playing)
        assertTrue(retries.isEmpty())
    }

    @Test fun pauseDuringSnapshotPreparationCannotStartStalePlaybackRequest() {
        connect()
        port.duringPrepare = {
            port.duringPrepare = {}
            session.update(playing().copy(playing = false))
        }
        session.update(playing()); worker.drain()
        assertFalse(port.calls.contains("request"))
        assertFalse(port.updates.single().playing)
    }

    @Test fun actualFocusLossCancelsDeniedRequestRetryWithoutReclaimLoop() {
        connect(); port.focus("own"); worker.drain(); port.acceptPlay = false
        session.update(playing()); worker.drain()
        port.focus("other"); worker.drain(); main.drain()
        retries.removeFirst().run(); worker.drain()
        assertEquals(1, port.calls.count { it == "request" })
        assertEquals(listOf(CarPlayMediaButton.PAUSE), sent)
    }

    @Test fun sourceSelectionRequestsBeforeSourceAndResumesPausedPhoneWithoutInventingFocus() {
        port.source = 6; port.currentFocus = "other"; connect()
        session.update(playing().copy(playing = false)); worker.drain()
        assertFalse(port.selected(13)); assertTrue(port.selected(6)); worker.drain(); main.drain()
        assertEquals(1, port.calls.count { it == "request" })
        assertTrue(port.calls.indexOf("request") < port.calls.indexOf("current"))
        assertEquals(listOf(CarPlayMediaButton.PLAY), sent)
        assertFalse(port.updates.last().playing)
        assertFalse(port.command(CarPlayMediaButton.NEXT))
        session.update(playing()); worker.drain()
        assertEquals(1, port.calls.count { it == "request" })
    }

    @Test fun explicitPlayCallbackCanReclaimFromForeignFocus() {
        port.currentFocus = "other"; connect()
        session.update(playing().copy(playing = false)); worker.drain()
        assertTrue(port.command(CarPlayMediaButton.PLAY)); main.drain(); worker.drain()
        assertEquals(listOf(CarPlayMediaButton.PLAY), sent)
        assertEquals(1, port.calls.count { it == "request" })
    }

    @Test fun replacedSessionCannotCompleteRequestOrPublishOldMetadata() {
        connect(); port.duringRequest = { current = false }
        session.update(playing()); worker.drain()
        assertFalse(port.calls.contains("current"))
        assertTrue(port.updates.isEmpty())
        assertTrue(retries.isEmpty())
    }
    @Test fun pauseArrivingBeforePublishCancelsQueuedPlayRequest() {
        connect(); session.update(playing()); session.update(playing().copy(playing = false)); worker.drain()
        assertFalse(port.calls.contains("request"))
        assertFalse(port.updates.single().playing)
    }
    @Test fun registrationBeginPrecedesSdkAndBrokenDiagnosticSinkDoesNotChangeRegistration() {
        port.duringRegister = { assertTrue(logs.any { "stage=register" in it && "phase=BEFORE" in it }) }
        connect()
        assertTrue(logs.any { "stage=register" in it && "phase=AFTER" in it && "durationMs=" in it })
        val other = Port()
        val safe = L7MediaCenterSession(other, "own", { true }, { _, _ -> }, worker, main,
            { throw IllegalStateException("private-log-error") })
        safe.start(); worker.drain(); other.ready(true); worker.drain()
        assertTrue("register" in other.calls)
        assertTrue(other.calls.any { it.startsWith("sources:") })
        safe.close(); worker.drain()
    }
}
