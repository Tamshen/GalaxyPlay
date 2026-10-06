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
}
