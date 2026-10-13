package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class SampledDiagnosticOperationTest {
    @Test fun thousandsOfSuccessfulCallsKeepBusinessResultsAndBoundLogs() {
        val lines = mutableListOf<String>()
        var now = 0L
        val trace = SampledDiagnosticOperation(lines::add, setOf("progress")) { now }
        repeat(5000) { index ->
            assertEquals(index, trace.run("progress") { now += 1_000_000; index })
        }
        assertEquals(2, lines.size)
        trace.flush()
        assertTrue(lines.last().contains("calls=5000 empty=0 maxDurationMs=1"))
        trace.run("progress") { 42 }
        assertEquals(5, lines.size)
        assertTrue(lines.last().contains("sampled=true"))
    }

    @Test fun unsampledFailureRejectionAndSlowCallAreImmediatelyVisible() {
        val lines = mutableListOf<String>()
        var now = 0L
        val trace = SampledDiagnosticOperation(lines::add, setOf("progress")) { now }
        trace.run("progress") { true }; lines.clear()
        trace.run("progress") { now += 250_000_000; true }
        assertTrue(lines.single().contains("durationMs=250 sampled=false"))
        assertFalse(trace.run("progress") { false })
        assertTrue(lines.last().contains("outcome=REJECTED"))
        val error = SecurityException("private-sdk-error")
        try { trace.run("progress") { throw error }; fail() }
        catch (actual: SecurityException) { assertSame(error, actual) }
        assertTrue(lines.last().contains("outcome=FAILED"))
        assertFalse(lines.joinToString().contains("private-sdk-error"))
    }

    @Test fun criticalOperationsRetainBeforeAnchorAndPrivateObjectsAreNotInspected() {
        val lines = mutableListOf<String>()
        val trace = SampledDiagnosticOperation(lines::add, setOf("progress"))
        val privateResult = object {
            override fun toString(): String = throw AssertionError("private result")
            override fun equals(other: Any?): Boolean = throw AssertionError("result comparison")
            override fun hashCode() = 0
        }
        assertSame(privateResult, trace.run("progress") { privateResult })
        assertSame(privateResult, trace.run("register") {
            assertTrue(lines.last().contains("stage=register") && lines.last().contains("phase=BEFORE"))
            privateResult
        })
        assertTrue(lines.last().contains("stage=register") && lines.last().contains("phase=AFTER"))
        assertTrue(lines.last().contains("operation=2"))
    }

    @Test fun sinkFailuresNeverChangeResultsAndEmptyReadinessIsSummarized() {
        assertEquals(42, SampledDiagnosticOperation({ throw IllegalStateException() }, setOf("ready")).run("ready") { 42 })
        val lines = mutableListOf<String>()
        val trace = SampledDiagnosticOperation(lines::add, setOf("ready")) { 0 }
        repeat(1000) { assertNull(trace.run("ready") { null }) }
        trace.flush()
        assertEquals(3, lines.size)
        assertTrue(lines.last().contains("calls=1000 empty=1000"))
    }
}
