package com.shilapi.xcertplay

import java.util.TreeSet

/** 上传原文与 JSON 两个预算阶段共用；优先保留阶段锚点，事件编号相同且正文相同才合并副本。 */
internal class RemoteLogWindow<T>(private val maxCount: Int, private val maxCost: Int,
                                 private val deduplicate: Boolean = true) {
    data class Item<T>(val index: Int, val source: String, val text: String, val value: T, val cost: Int,
                       val event: DiagnosticLogIdentity.Event?)
    private val order = compareBy<Item<T>>({ it.event?.millis ?: 0L }, { it.event?.sequence ?: it.index.toLong() }, { it.index })
    private val buckets = Array(3) { TreeSet(order) }
    private val identities = mutableMapOf<String, MutableList<Item<T>>>()
    var cost = 0
        private set
    var size = 0
        private set
    var duplicateCopies = 0
        private set

    fun add(index: Int, source: String, text: String, value: T, cost: Int): Int {
        val event = DiagnosticLogIdentity.read(text)
        if (deduplicate && event != null && identities[event.id]?.any { it.text == text } == true) {
            duplicateCopies++; return 0
        }
        val item = Item(index, source, text, value, cost, event)
        buckets[priority(source, text)].add(item)
        if (deduplicate && event != null) identities.getOrPut(event.id) { mutableListOf() }.add(item)
        this.cost += cost; size++
        var removed = 0
        while (size > maxCount || this.cost > maxCost) { removeOne(); removed++ }
        return removed
    }

    fun ordered(): List<Item<T>> = buckets.flatMap { it }.sortedWith(order)

    fun removeOne(): Item<T> {
        val item = buckets.first { it.isNotEmpty() }.pollFirst()!!
        cost -= item.cost; size--
        item.event?.let { event -> identities[event.id]?.let { list ->
            list.remove(item)
            if (list.isEmpty()) identities.remove(event.id)
        } }
        return item
    }

    private fun priority(source: String, text: String): Int = when {
        source == "wired-startup" || source == "process-exits" || ANCHOR.containsMatchIn(text) -> 2
        ERROR.containsMatchIn(text) -> 1
        else -> 0
    }

    private companion object {
        val ANCHOR = Regex("CORE_TRACE .*kind=(START|STOP|RELEASE)|CORE_TRACE .*state=(FAILED|UNKNOWN)|" +
            "CONNECTION_DIAGNOSTIC .* (start |stage=|teardown)|Connection stage=|STEP (mfi|bt|wifi|iap2|handoff)/|" +
            "IAP2 (READY|CLOSE)|iap2 (authentication accepted|identification accepted|tx=0x4301|rx=0x5702|tx=0x5703)|" +
            "wireless startup .* (first TCP|StartSession|session established)|wireless startup .*tcpAccepted=|" +
            "wireless snapshot(?: cached=true)? (interfaceState=|receiveCounters)|" +
            "Wi-Fi P2P (credentials |configuration revalidated|ready mode=)|Video: first frame rendered|" +
            "AirPlay session (active|ended)|Host started")
        val ERROR = Regex("(?i)\\b(error|failed|timeout|cancelled|disconnected)\\b|失败|超时|取消")
    }
}
