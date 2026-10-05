package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.*
import org.junit.Test

class L7MediaCommandDispatcherTest {
    private var owner: Any? = Any()
    private var current = true
    private var resumes = 0
    private var localCalls = 0
    private val sent = mutableListOf<Pair<Int, Any>>()
    private val waiting = mutableListOf<() -> Unit>()
    private val logs = mutableListOf<String>()
    private val dispatcher = L7MediaCommandDispatcher({ owner }, { current }, { _, _ -> true },
        { _, action -> waiting += action }, { localCalls++; false }, { index, expected -> sent += index to expected; true },
        { resumes++ }, logs::add)

    @Test fun delayedPlayCannotResumeOrSendToReconnectedPhoneOnSameController() {
        dispatcher.dispatch(CarPlayMediaButton.PLAY, "mediacenter")
        owner = Any()
        waiting.single()()
        assertEquals(0, resumes)
        assertEquals(0, localCalls)
        assertTrue(sent.isEmpty())
        assertTrue(logs.any { "drop=STALE_SESSION" in it })
    }
    @Test fun currentSessionStillReceivesExplicitPlayAfterBluetoothWait() {
        val expected = owner!!
        dispatcher.dispatch(CarPlayMediaButton.PLAY, "mediacenter")
        waiting.single()()
        assertEquals(1, resumes)
        assertEquals(listOf(CarPlayMediaButton.PLAY to expected), sent)
    }
    @Test fun disconnectedOrReplacedControllerCannotConsumeLocalVideoCommand() {
        dispatcher.dispatch(CarPlayMediaButton.PAUSE, "window-key")
        current = false
        waiting.single()()
        assertEquals(0, localCalls)
        assertTrue(sent.isEmpty())
        dispatcher.dispatch(CarPlayMediaButton.PLAY, "mediacenter")
        assertEquals(1, waiting.size)
    }
}
