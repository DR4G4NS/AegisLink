package dev.aegis.remote.core.input

import dev.aegis.remote.core.model.MonitorInfo
import kotlin.math.roundToInt

class TouchToRemoteCoordinateMapper {
    fun map(
        touchX: Float,
        touchY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        monitor: MonitorInfo,
    ): Pair<Int, Int> {
        require(viewportWidth > 0f) { "viewportWidth must be positive" }
        require(viewportHeight > 0f) { "viewportHeight must be positive" }

        val normalizedX = (touchX / viewportWidth).coerceIn(0f, 1f)
        val normalizedY = (touchY / viewportHeight).coerceIn(0f, 1f)
        require(monitor.width > 0) { "monitor width must be positive" }
        require(monitor.height > 0) { "monitor height must be positive" }
        val remoteX = monitor.originX + (normalizedX * (monitor.width - 1)).roundToInt()
        val remoteY = monitor.originY + (normalizedY * (monitor.height - 1)).roundToInt()
        return remoteX to remoteY
    }
}
