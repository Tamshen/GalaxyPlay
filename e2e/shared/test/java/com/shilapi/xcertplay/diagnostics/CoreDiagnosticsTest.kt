package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class CoreDiagnosticsTest {
    private val component = DiagnosticEvent.Component.CONNECTION
    private val kind = DiagnosticEvent.Kind.STATE
    private val state = DiagnosticEvent.State.RUNNING

    @Test fun capturedMetricsAreImmutableAndEventsHaveSessionSequenceAndElapsedTime() {
        var now = 10_000_000L
        val events = mutableListOf<DiagnosticEvent>()
        val channel = DiagnosticChannel(7, DiagnosticSink(events::add)) { now }
        val metrics = linkedMapOf("run" to 2L)
        now += 25_000_000
        channel.emit(component, kind, state, metrics)
        metrics["run"] = 99
        channel.emit(component, kind, state)
        assertEquals(7L, events[0].session)
        assertEquals(1L, events[0].sequence)
        assertEquals(2L, events[1].sequence)
        assertEquals(25L, events[0].elapsedMillis)
        assertEquals(2L, events[0].metrics["run"])
        assertThrows(UnsupportedOperationException::class.java) {
            (events[0].metrics as MutableMap)["run"] = 100
        }
    }

    @Test fun failedSinkDoesNotBreakNextEventOrConnectionWork() {
        val events = mutableListOf<DiagnosticEvent>()
        var fail = true
        val channel = DiagnosticChannel(1, DiagnosticSink {
            if (fail) throw IllegalStateException("private exception text")
            events += it
        })
        channel.emit(component, kind, state)
        fail = false
        channel.emit(component, kind, state)
        assertEquals(2L, events.single().sequence)
    }

    @Test fun finishIsOneTerminalRequestAndLateReportsAreRejected() {
        val events = mutableListOf<DiagnosticEvent>()
        val channel = DiagnosticChannel(1, DiagnosticSink(events::add))
        channel.emit(component, kind, state)
        channel.finish()
        channel.finish()
        channel.emit(component, kind, DiagnosticEvent.State.FAILED)
        assertEquals(2, events.size)
        assertEquals(DiagnosticEvent.Kind.STOP, events.last().kind)
        assertEquals(DiagnosticEvent.State.REQUESTED, events.last().state)
    }

    @Test fun channelsKeepTheirOwnSinksAndClosingOneCannotRedirectAnother() {
        val old = mutableListOf<DiagnosticEvent>()
        val next = mutableListOf<DiagnosticEvent>()
        val first = DiagnosticChannel(1, DiagnosticSink(old::add))
        val second = DiagnosticChannel(2, DiagnosticSink(next::add))
        first.close()
        first.emit(component, kind, state)
        second.emit(component, kind, state)
        assertTrue(old.isEmpty())
        assertEquals(2L, next.single().session)
    }

    @Test fun terminalSinkReentryCannotRepeatStopOrAppendAStateAfterIt() {
        val events = mutableListOf<DiagnosticEvent>()
        lateinit var channel: DiagnosticChannel
        channel = DiagnosticChannel(1, DiagnosticSink {
            events += it
            channel.finish()
            channel.emit(component, kind, state)
        })
        channel.finish()
        assertEquals(1, events.size)
        assertEquals(DiagnosticEvent.Kind.STOP, events.single().kind)
    }

    @Test fun invalidOrOversizedMetricsCannotEnterTheLog() {
        val events = mutableListOf<DiagnosticEvent>()
        val channel = DiagnosticChannel(1, DiagnosticSink(events::add))
        channel.emit(component, kind, state, mapOf("payload=private" to 1))
        channel.emit(component, kind, state, (1..13).associate { "metric$it" to it.toLong() })
        channel.emit(component, kind, state, mapOf("bytes" to 48))
        assertEquals(mapOf("bytes" to 48L), events.single().metrics)
        assertEquals(1L, events.single().sequence)
    }

    @Test fun stopRequestRejectsLateBusinessButKeepsActualReleaseResultUntilClosed() {
        val events = mutableListOf<DiagnosticEvent>()
        val channel = DiagnosticChannel(8, DiagnosticSink(events::add))
        channel.stopRequested(); channel.stopRequested()
        channel.emit(component, kind, state)
        channel.emit(component, DiagnosticEvent.Kind.FAILURE, DiagnosticEvent.State.FAILED)
        channel.emit(component, DiagnosticEvent.Kind.RELEASE, DiagnosticEvent.State.UNKNOWN,
            mapOf("closeFailures" to 1L))
        channel.finish()
        channel.emit(component, DiagnosticEvent.Kind.RELEASE, DiagnosticEvent.State.ENDED)
        assertEquals(listOf(DiagnosticEvent.Kind.STOP, DiagnosticEvent.Kind.RELEASE), events.map { it.kind })
        assertEquals(DiagnosticEvent.State.UNKNOWN, events.last().state)
        assertEquals(8L, events.last().session)
        assertEquals(1L, events.last().metrics["closeFailures"])
    }
}
