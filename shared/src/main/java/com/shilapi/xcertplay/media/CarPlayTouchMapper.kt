package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact

/** Converts Android MotionEvents into normalized CarPlay touch contacts. */
object CarPlayTouchMapper {
    private const val MAX_CONTACTS = 2

    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> {
        val width = viewWidth.coerceAtLeast(1)
        val height = viewHeight.coerceAtLeast(1)
        val action = event.actionMasked
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val count = minOf(MAX_CONTACTS, event.pointerCount)
        val contacts = ArrayList<AirPlayContact>(count)
        for (index in 0 until count) {
            contacts.add(
                AirPlayContact(
                    id = index,
                    x = (event.getX(index).toDouble() / width).coerceIn(0.0, 1.0),
                    y = (event.getY(index).toDouble() / height).coerceIn(0.0, 1.0),
                    down = !allUp && index != liftedIndex,
                ),
            )
        }
        return contacts
    }

    /** 每个宿主持有自己的状态；Android pointer 索引变化不会交换协议接触点。 */
    class Tracker {
        private val state = VideoTouchState()
        fun reset() { state.reset() }
        fun contacts(event: MotionEvent, viewport: VideoViewport): List<AirPlayContact> {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) state.reset()
            val lifted = when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.getPointerId(event.actionIndex)
                else -> null
            }
            val samples = (0 until event.pointerCount).map { index ->
                VideoTouchPoint(event.getPointerId(index), event.getX(index).toDouble(), event.getY(index).toDouble())
            }
            return state.update(samples, viewport, lifted,
                event.actionMasked == MotionEvent.ACTION_CANCEL,
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> event.getPointerId(event.actionIndex)
                    else -> null
                })
        }
    }
}

internal data class VideoTouchPoint(val pointerId: Int, val x: Double, val y: Double)

internal class VideoTouchState {
    private val slots = LinkedHashMap<Int, Int>()
    fun reset() { slots.clear() }
    fun update(points: List<VideoTouchPoint>, viewport: VideoViewport, lifted: Int? = null,
               cancel: Boolean = false, pressed: Int? = null): List<AirPlayContact> {
        if (cancel) { reset(); return emptyList() }
        // 只有视频内的新按下才建立接触点；从黑边滑入不会生成幽灵按下。
        points.firstOrNull { it.pointerId == pressed && viewport.contains(it.x, it.y) }?.let { point ->
            if (point.pointerId !in slots && slots.size < 2) {
                slots[point.pointerId] = (0..1).first { it !in slots.values }
            }
        }
        val contacts = points.mapNotNull { point ->
            slots[point.pointerId]?.let { slot ->
                AirPlayContact(slot, viewport.normalizedX(point.x), viewport.normalizedY(point.y), point.pointerId != lifted)
            }
        }
        if (lifted != null) slots.remove(lifted)
        return contacts
    }
}
