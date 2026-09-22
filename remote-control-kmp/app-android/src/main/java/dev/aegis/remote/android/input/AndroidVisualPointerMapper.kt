package dev.aegis.remote.android.input

import dev.aegis.remote.core.model.MonitorInfo
import kotlin.math.min

/** Maps touches through SCALE_ASPECT_FIT letterboxing into Windows virtual-desktop coordinates. */
class AndroidVisualPointerMapper {
    fun map(
        touchX: Float,
        touchY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        monitor: MonitorInfo,
    ): Pair<Int, Int>? {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return null
        if (monitor.width <= 0 || monitor.height <= 0) return null

        val scale = min(viewportWidth / monitor.width, viewportHeight / monitor.height)
        val contentWidth = monitor.width * scale
        val contentHeight = monitor.height * scale
        val contentLeft = (viewportWidth - contentWidth) / 2f
        val contentTop = (viewportHeight - contentHeight) / 2f
        if (touchX < contentLeft || touchX > contentLeft + contentWidth) return null
        if (touchY < contentTop || touchY > contentTop + contentHeight) return null

        val localX = ((touchX - contentLeft) / scale).toInt().coerceIn(0, monitor.width - 1)
        val localY = ((touchY - contentTop) / scale).toInt().coerceIn(0, monitor.height - 1)
        return monitor.originX + localX to monitor.originY + localY
    }
}
