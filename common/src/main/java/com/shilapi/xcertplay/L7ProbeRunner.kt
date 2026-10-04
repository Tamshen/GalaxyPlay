package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** 单工作线程有界执行；超时停止接收结果，未返回的系统查询不会引发额外并发线程。 */
internal object L7ProbeRunner {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "l7-basic-probe").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile var current: L7ProbeReport? = null
        private set
    @Volatile var history: List<L7ProbeReport> = emptyList()
        private set
    @Volatile var busy = false
        private set
    @Volatile var storageFailed = false
        private set
    @Volatile var logFailed = false
        private set
    @Volatile var environment: String? = null
        private set
    @Volatile var revision = 0L
        private set
    private var initialized = false

    @Synchronized fun load(context: Context) {
        if (initialized || busy) return
        initialized = true
        busy = true
        val app = context.applicationContext
        worker.execute {
            try { history = store(app).load(); environment = L7ProbeEnvironment.generation(app) }
            catch (_: Exception) { storageFailed = true }
            finally { busy = false; revision++ }
        }
    }

    @Synchronized fun start(context: Context, window: Map<String, String?>, onlyId: String? = null): Boolean {
        if (busy || !L7Agreement.accepted(context) || L7AppExit.exiting) return false
        val app = context.applicationContext
        val id = UUID.randomUUID().toString()
        busy = true
        storageFailed = false
        logFailed = false
        current = L7ProbeReport(id, "pending", "pending", System.currentTimeMillis(), L7ProbePhase.RUNNING)
        revision++
        val timeout = Runnable { if (current?.id == id) stop(L7ProbePhase.TIMED_OUT) }
        main.postDelayed(timeout, 15_000)
        worker.execute {
            try {
                persist(app)
                val permissions = L7PermissionProbe(app)
                val queries = L7ProbeEnvironment.queries(app, window).filter { onlyId == null || it.first == onlyId }
                val names = permissions.names.filter { onlyId == null || "PERM:$it" == onlyId }
                val generation = L7ProbeEnvironment.generation(app)
                val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
                synchronized(this) {
                    if (accepts(id)) {
                        current = current!!.copy(environment = generation, version = version,
                            expected = queries.size + names.size,
                            items = queries.map { L7ProbeItem(it.first, it.first, "ENVIRONMENT", L7ProbeOutcome.SKIPPED, "NOT_RUN") } +
                                names.map { L7ProbeItem("PERM:$it", it, "PERMISSION", L7ProbeOutcome.SKIPPED, "NOT_RUN") })
                        environment = generation
                    }
                }
                persist(app)
                for ((key, query) in queries) {
                    if (!accepts(id)) break
                    val item = runCatching { L7ProbeItem(key, key, "ENVIRONMENT", L7ProbeOutcome.VERIFIED, "QUERY_ONLY", query()) }
                        .getOrElse { L7ProbeItem(key, key, "ENVIRONMENT",
                            if (it is SecurityException) L7ProbeOutcome.DENIED else L7ProbeOutcome.UNKNOWN,
                            "QUERY_FAILED", mapOf("exceptionType" to it.javaClass.simpleName)) }
                    publish(id, item)
                }
                for (name in names) {
                    if (!accepts(id)) break
                    publish(id, permissions.inspect(name))
                    if (current!!.items.count { it.reason != "NOT_RUN" } % 25 == 0) persist(app)
                }
                synchronized(this) {
                    if (accepts(id)) current = current!!.copy(phase = L7ProbePhase.COMPLETED, finished = System.currentTimeMillis())
                }
            } catch (_: Exception) {
                synchronized(this) {
                    if (accepts(id)) current = current!!.copy(phase = L7ProbePhase.INTERRUPTED, finished = System.currentTimeMillis())
                }
            } finally {
                main.removeCallbacks(timeout)
                persist(app)
                current?.takeIf { it.id == id }?.let { report ->
                    runCatching { L7ProbeLog.write(app, report) }.onFailure { logFailed = true }
                }
                busy = false
                revision++
            }
        }
        return true
    }

    @Synchronized fun refreshEnvironment(context: Context) {
        if (!initialized || busy) return
        val app = context.applicationContext
        busy = true
        worker.execute {
            runCatching { environment = L7ProbeEnvironment.generation(app) }
            busy = false
            revision++
        }
    }

    @Synchronized fun stop(phase: L7ProbePhase = L7ProbePhase.CANCELLED) {
        val run = current ?: return
        if (run.phase != L7ProbePhase.RUNNING) return
        current = run.copy(phase = phase, finished = System.currentTimeMillis())
        revision++
    }

    @Synchronized private fun publish(id: String, item: L7ProbeItem) {
        if (!accepts(id)) return
        current = current!!.copy(items = current!!.items.map { if (it.id == item.id) item else it })
        revision++
    }

    private fun accepts(id: String) = current?.let { it.id == id && it.phase == L7ProbePhase.RUNNING } == true
    private fun persist(context: Context) {
        runCatching {
            current?.let { report ->
                val saved = store(context).save(report)
                history = (listOf(saved) + history.filter { it.id != saved.id }).take(L7ProbeStore.MAX_REPORTS)
            }
        }.onFailure { storageFailed = true }
    }

    @Synchronized fun delete(context: Context, id: String): Boolean {
        if (busy || current?.let { it.id == id && it.phase == L7ProbePhase.RUNNING } == true) return false
        busy = true
        val app = context.applicationContext
        worker.execute {
            runCatching {
                store(app).delete(id)
                history = history.filter { it.id != id }
                if (current?.id == id) current = null
                storageFailed = false
            }.onFailure { storageFailed = true }
            busy = false
            revision++
        }
        return true
    }

    private fun store(context: Context) = L7ProbeStore(File(context.filesDir, "probe-reports"))
}
