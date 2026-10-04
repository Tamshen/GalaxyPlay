package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ProbeRunnerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (L7ProbeRunner.busy && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("工作线程应在预算内释放", L7ProbeRunner.busy)
    }
    @After fun cleanup() { L7ProbeRunner.stop(); awaitIdle() }

    @Test fun loadingHistoryDoesNotStartScanningOrUpload() {
        val upload = RemoteLogUpload.status
        L7ProbeRunner.load(app)
        awaitIdle()
        assertNull(L7ProbeRunner.current)
        assertEquals(upload, RemoteLogUpload.status)
    }

    @Test fun missingAgreementPreventsAnyNewRun() {
        L7Agreement.revoke(app)
        assertFalse(L7ProbeRunner.start(app, emptyMap()))
        assertNull(L7ProbeRunner.current)
    }

    @Test fun deadlineMarksPartialRunWithoutAcceptingTheBlockedQueryResult() {
        L7Agreement.accept(app)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): android.content.pm.PackageManager {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.getPackageManager()
            }
        }
        try {
            assertTrue(L7ProbeRunner.start(context, emptyMap(), "ENV-SYSTEM"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertEquals(L7ProbePhase.TIMED_OUT, L7ProbeRunner.current!!.phase)
            assertTrue(L7ProbeRunner.busy)
            release.countDown()
            awaitIdle()
            assertTrue(L7ProbeRunner.current!!.items.isEmpty())
        } finally { release.countDown() }
    }

    @Test fun singleCheckProducesOnlyTheSelectedEvidenceAndNeverUploads() {
        L7Agreement.accept(app)
        val upload = RemoteLogUpload.status
        assertTrue(L7ProbeRunner.start(app, emptyMap(), "ENV-SYSTEM"))
        awaitIdle()
        val report = L7ProbeRunner.current!!
        assertEquals(L7ProbePhase.COMPLETED, report.phase)
        assertEquals(listOf("ENV-SYSTEM"), report.items.map { it.id })
        assertEquals(1, report.expected)
        assertEquals(upload, RemoteLogUpload.status)
        assertTrue(L7ProbeRunner.history.any { it.id == report.id })
    }

    @Test fun cancellationRejectsLateResultsAndBlocksAnotherWorkerUntilQueryReturns() {
        L7Agreement.accept(app)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageManager(): android.content.pm.PackageManager {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.getPackageManager()
            }
        }
        try {
            assertTrue(L7ProbeRunner.start(context, emptyMap(), "ENV-SYSTEM"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val id = L7ProbeRunner.current!!.id
            L7ProbeRunner.stop()
            assertFalse(L7ProbeRunner.start(app, emptyMap()))
            release.countDown()
            awaitIdle()
            assertEquals(id, L7ProbeRunner.current!!.id)
            assertEquals(L7ProbePhase.CANCELLED, L7ProbeRunner.current!!.phase)
            assertTrue(L7ProbeRunner.current!!.items.isEmpty())
            assertTrue(L7ProbeRunner.start(app, emptyMap(), "ENV-SYSTEM"))
            awaitIdle()
            assertNotEquals(id, L7ProbeRunner.current!!.id)
            assertEquals(L7ProbePhase.COMPLETED, L7ProbeRunner.current!!.phase)
        } finally { release.countDown() }
    }
}
