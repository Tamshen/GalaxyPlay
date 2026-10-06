package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticOperationTest {
    @Test fun beginIsVisibleInsideBlockingOperationAndEndPreservesResultAndTiming() {
        val lines = mutableListOf<String>()
        var time = 10_000_000L
        val trace = DiagnosticOperation(lines::add) { time }
        val secret = object {
            override fun equals(other: Any?): Boolean = throw AssertionError("diagnostics must not inspect SDK results")
            override fun hashCode() = 0
            override fun toString(): String = throw AssertionError("private result")
        }
        val result = trace.run("register") {
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("operation=1 phase=BEFORE"))
            time += 7_000_000L
            secret
        }
        assertSame(secret, result)
        assertTrue(lines.last().contains("operation=1 phase=AFTER outcome=RETURNED durationMs=7"))
        assertFalse(lines.joinToString().contains("private result"))
    }
    @Test fun diagnosisFailureDoesNotChangeBusinessAndExceptionOriginalIsNeverLogged() {
        assertEquals(42, DiagnosticOperation({ throw IllegalStateException("sink-secret") }).run("open") { 42 })
        val lines = mutableListOf<String>()
        val error = SecurityException("private-driver-error")
        try { DiagnosticOperation(lines::add).run("close") { throw error }; fail() }
        catch (actual: SecurityException) { assertSame(error, actual) }
        assertTrue(lines.last().contains("exceptionType=SecurityException"))
        assertFalse(lines.joinToString().contains("private-driver-error"))
    }
}
