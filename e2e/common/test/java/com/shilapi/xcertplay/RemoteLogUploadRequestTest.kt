package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class RemoteLogUploadRequestTest {
    @Test fun eachNewReportGetsItsOwnHashEvenWhenRemarksMatch() {
        val first = RemoteLogUploadRequest.create("复现连接失败")
        val second = RemoteLogUploadRequest.create("复现连接失败")
        assertTrue(first.id.matches(Regex("[a-f0-9]{32}")))
        assertNotEquals(first.id, second.id)
        assertEquals(first.remark, second.remark)
        assertTrue(first.created.endsWith("Z"))
    }

    @Test fun remarkIsOptionalBoundedSingleLineAndStillRedactsCredentials() {
        assertEquals("", RemoteLogUploadRequest.create().remark)
        val request = RemoteLogUploadRequest.create("  连接失败\n\t再试一次 token=private-test-secret  ")
        assertFalse(request.remark.contains("private-test-secret"))
        assertFalse(request.remark.any { it.code < 32 })
        assertTrue(request.remark.startsWith("连接失败 再试一次"))
        assertEquals(RemoteLogUploadRequest.MAX_REMARK, RemoteLogUploadRequest.create("中".repeat(300)).remark.length)
    }
}
