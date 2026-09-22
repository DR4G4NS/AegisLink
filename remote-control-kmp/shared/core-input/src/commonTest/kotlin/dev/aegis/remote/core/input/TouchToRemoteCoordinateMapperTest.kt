package dev.aegis.remote.core.input

import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class TouchToRemoteCoordinateMapperTest {
    @Test
    fun mapsViewportCoordinatesIntoMonitorSpace() {
        val monitor =
            MonitorInfo(
                id = MonitorId("primary"),
                name = "Primary",
                width = 1920,
                height = 1080,
                originX = 1920,
                originY = 0,
            )

        val mapped =
            TouchToRemoteCoordinateMapper().map(
                touchX = 50f,
                touchY = 50f,
                viewportWidth = 100f,
                viewportHeight = 100f,
                monitor = monitor,
            )

        assertEquals(2880 to 540, mapped)
    }

    @Test
    fun mapsBottomRightToLastPixelForNegativeOriginMonitor() {
        val monitor =
            MonitorInfo(
                id = MonitorId("left"),
                name = "Left",
                width = 1920,
                height = 1080,
                originX = -1920,
                originY = -100,
            )

        val mapped = TouchToRemoteCoordinateMapper().map(100f, 100f, 100f, 100f, monitor)

        assertEquals(-1 to 979, mapped)
    }
}
