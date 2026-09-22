package dev.aegis.remote.core.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalViewportSizerTest {
    @Test
    fun calculatesPtySizeFromViewportAndCellMetrics() {
        val sizer = TerminalViewportSizer()

        val size =
            sizer.calculate(
                TerminalViewportMetrics(
                    viewportWidthPx = 960f,
                    viewportHeightPx = 480f,
                    cellWidthPx = 8f,
                    cellHeightPx = 16f,
                ),
            )

        assertEquals(TerminalPtySize(columns = 120, rows = 30), size)
    }

    @Test
    fun clampsToMinimumPtySize() {
        val sizer = TerminalViewportSizer(minColumns = 20, minRows = 5)

        val size =
            sizer.calculate(
                TerminalViewportMetrics(
                    viewportWidthPx = 10f,
                    viewportHeightPx = 10f,
                    cellWidthPx = 8f,
                    cellHeightPx = 16f,
                ),
            )

        assertEquals(TerminalPtySize(columns = 20, rows = 5), size)
    }

    @Test
    fun clampsToMaximumPtySize() {
        val sizer = TerminalViewportSizer(maxColumns = 100, maxRows = 40)

        val size =
            sizer.calculate(
                TerminalViewportMetrics(
                    viewportWidthPx = 4_000f,
                    viewportHeightPx = 4_000f,
                    cellWidthPx = 8f,
                    cellHeightPx = 16f,
                ),
            )

        assertEquals(TerminalPtySize(columns = 100, rows = 40), size)
    }

    @Test
    fun rejectsInvalidViewportMetrics() {
        val sizer = TerminalViewportSizer()

        assertFailsWith<IllegalArgumentException> {
            sizer.calculate(
                TerminalViewportMetrics(
                    viewportWidthPx = 0f,
                    viewportHeightPx = 480f,
                    cellWidthPx = 8f,
                    cellHeightPx = 16f,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            sizer.calculate(
                TerminalViewportMetrics(
                    viewportWidthPx = 960f,
                    viewportHeightPx = 480f,
                    cellWidthPx = 0f,
                    cellHeightPx = 16f,
                ),
            )
        }
    }

    @Test
    fun rejectsInvalidBounds() {
        assertFailsWith<IllegalArgumentException> {
            TerminalViewportSizer(minColumns = 80, maxColumns = 79).calculate(
                TerminalViewportMetrics(
                    viewportWidthPx = 960f,
                    viewportHeightPx = 480f,
                    cellWidthPx = 8f,
                    cellHeightPx = 16f,
                ),
            )
        }
    }
}
