package dev.aegis.remote.android.input

import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidVisualPointerMapperTest {
    private val monitor =
        MonitorInfo(
            id = MonitorId("display-2"),
            name = "Display 2",
            width = 1920,
            height = 1080,
            originX = -1920,
            originY = 0,
        )

    @Test
    fun mapsAspectFitContentToVirtualDesktopCoordinates() {
        val mapped =
            AndroidVisualPointerMapper().map(
                touchX = 500f,
                touchY = 500f,
                viewportWidth = 1000f,
                viewportHeight = 1000f,
                monitor = monitor,
            )

        assertEquals(-960 to 540, mapped)
    }

    @Test
    fun ignoresTouchesInLetterboxBars() {
        val mapped =
            AndroidVisualPointerMapper().map(
                touchX = 500f,
                touchY = 100f,
                viewportWidth = 1000f,
                viewportHeight = 1000f,
                monitor = monitor,
            )

        assertNull(mapped)
    }

    @Test
    fun clampsBottomRightEdgeToLastRemotePixel() {
        val mapped =
            AndroidVisualPointerMapper().map(
                touchX = 1920f,
                touchY = 1080f,
                viewportWidth = 1920f,
                viewportHeight = 1080f,
                monitor = monitor,
            )

        assertEquals(-1 to 1079, mapped)
    }

    @Test
    fun rejectsInvalidViewportDimensions() {
        assertNull(
            AndroidVisualPointerMapper().map(
                touchX = 0f,
                touchY = 0f,
                viewportWidth = 0f,
                viewportHeight = 1080f,
                monitor = monitor,
            ),
        )
    }
}
