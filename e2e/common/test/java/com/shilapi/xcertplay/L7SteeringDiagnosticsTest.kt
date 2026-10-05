package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.*
import org.junit.Test

class L7SteeringDiagnosticsTest {
    private var time = 100L
    private val logs = mutableListOf<String>()
    private val store = L7SteeringTraceStore({ time }, logs::add, 8)

    @Test fun delayedDeliveryRetainsInputIdentityAndMeasuresWaitAndQueueSeparately() {
        var owner: Any? = Any()
        lateinit var waiting: () -> Unit
        val trace = store.begin("window-key", CarPlayMediaButton.PLAY)
        val dispatcher = L7MediaCommandDispatcher({ owner }, { true }, { _, _ -> true },
            { _, action -> waiting = action }, { false }, { _, _ -> true }, {}, {},
            traceSend = { _, _, ticket ->
                assertSame(trace, ticket)
                ticket.step("QUEUE_EXECUTE")
                time += 12
                ticket.step("CHANNEL_WRITTEN")
                true
            })
        dispatcher.dispatch(CarPlayMediaButton.PLAY, "window-key", trace)
        time += 3000
        waiting()
        val events = store.snapshot().events
        assertTrue(events.all { it.id == trace.id })
        assertEquals(3000L, events.single { it.stage == "WAIT_END" }.elapsedMs)
        assertEquals(3012L, events.single { it.stage == "CHANNEL_WRITTEN" }.elapsedMs)
        assertTrue(logs.all { "STEERING_TRACE" in it && "trace=${trace.id}" in it })
    }

    @Test fun rejectedInputsAndStaleWaitHaveExplicitReasonsWithoutSending() {
        var owner: Any? = null
        var sent = 0
        val waits = mutableListOf<() -> Unit>()
        val dispatcher = L7MediaCommandDispatcher({ owner }, { true }, { _, source -> source != "duplicate" },
            { _, action -> waits += action }, { false }, { _, _ -> sent++; true }, {}, {})
        dispatcher.dispatch(1, "window-key", store.begin("window-key", 1))
        owner = Any()
        dispatcher.dispatch(1, "duplicate", store.begin("duplicate", 1))
        dispatcher.dispatch(1, "window-key", store.begin("window-key", 1))
        owner = Any()
        waits.single()()
        assertEquals(0, sent)
        assertEquals(listOf("NO_SESSION", "CROSS_ORIGIN_DUPLICATE", "STALE_SESSION"),
            store.snapshot().events.filter { it.stage == "DROP" }.map { it.detail })
    }

    @Test fun boundedRecordsClearWithoutReusingTraceIdsAndSessionGenerationsAdvance() {
        store.connection(true)
        val old = store.begin("media-session-key", 1)
        repeat(40) { old.step("QUEUE_EXECUTE") }
        assertEquals(8, store.snapshot().events.size)
        store.clear()
        assertTrue(store.snapshot().events.isEmpty())
        store.connection(false)
        store.connection(true)
        val next = store.begin("window-key", 1)
        assertTrue(next.id > old.id)
        assertTrue(next.generation > old.generation)
        assertTrue(store.snapshot().connected)
    }

    @Test fun cancelledWaitRecordsTerminalAnchorWithoutQueueing() {
        val trace = store.begin("mediacenter", 1)
        val dispatcher = L7MediaCommandDispatcher({ Any() }, { true }, { _, _ -> true },
            { _, _ -> fail("应使用带取消通知的保护器") }, { false }, { _, _ -> fail("取消后不能发送"); false }, {}, {},
            traceBefore = { _, _, dropped -> time += 25; dropped("CANCELLED_BY_PAUSE") })
        dispatcher.dispatch(1, "mediacenter", trace)
        val last = store.snapshot().events.last()
        assertEquals("DROP", last.stage)
        assertEquals("CANCELLED_BY_PAUSE", last.detail)
        assertEquals(25L, last.elapsedMs)
    }
}
