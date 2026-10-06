package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CommunicationResourcesTest {
    @Test fun overlappingLegsFailedRestoreAndCloseWaitForActualRelease() {
        val events = mutableListOf<String>()
        var restored = false
        val resources = CommunicationResources({ events.add("enter") }, { events.add("restore"); restored },
            { events.add("suspend") }, { events.add("recover") }, { events.add("finish") })
        val down = Any(); val up = Any(); val replacement = Any()
        resources.started(down); resources.started(up); resources.started(down)
        resources.released(up); resources.retry()
        assertEquals(listOf("suspend", "enter"), events)
        resources.released(down)
        assertEquals("restore", events.last())
        assertFalse("recover" in events)
        resources.started(replacement)
        restored = true
        resources.retry()
        assertFalse("recover" in events)
        resources.released(replacement)
        assertEquals("recover", events.last())
        val pending = Any()
        resources.started(pending); resources.close()
        assertFalse("finish" in events)
        resources.released(pending)
        assertEquals("finish", events.last())
        val count = events.size
        resources.started(Any()); resources.released(pending)
        assertEquals(count, events.size)
    }
    @Test fun diagnosticsSeparateRemainingLegsFromRestoreAndIgnoreBrokenSink() {
        val lines = mutableListOf<String>()
        var restored = false
        var recovered = 0
        val resources = CommunicationResources({}, { restored }, {}, { recovered++ }, {}, lines::add)
        val down = Any(); val up = Any()
        resources.started(down); resources.started(up)
        resources.released(down)
        assertTrue(lines.last().contains("stage=RELEASED active=1"))
        assertFalse(lines.any { "stage=restoreMode" in it })
        resources.released(up)
        assertTrue(lines.any { "stage=restoreMode" in it && "outcome=REJECTED" in it })
        restored = true; resources.retry()
        assertEquals(1, recovered)
        assertTrue(lines.any { "stage=recoverMedia" in it && "phase=AFTER" in it })
        val broken = CommunicationResources({}, { true }, {}, {}, {}, { throw IllegalStateException("private") })
        broken.started(down); broken.released(down); broken.close()
    }
}
