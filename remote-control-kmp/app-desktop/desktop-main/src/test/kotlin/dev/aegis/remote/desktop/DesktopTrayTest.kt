package dev.aegis.remote.desktop

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTrayTest {
    private val screen = Rectangle(0, 0, 1920, 1040)

    @Test
    fun menuOpensAboveAndLeftOfABottomRightTrayIcon() {
        val position = trayMenuPosition(Point(1880, 1030), widthDp = 288, heightDp = 500, screen = screen)
        assertEquals(Point(1880 - 288, 1030 - 500), position)
    }

    @Test
    fun menuOpensBelowAndRightOfATopLeftPanelIcon() {
        val position = trayMenuPosition(Point(40, 10), widthDp = 288, heightDp = 500, screen = screen)
        assertEquals(Point(40, 10), position)
    }

    @Test
    fun menuIsClampedInsideTheUsableScreenArea() {
        val position = trayMenuPosition(Point(2500, 3000), widthDp = 288, heightDp = 500, screen = screen)
        assertEquals(Point(1920 - 288, 1040 - 500), position)
    }

    @Test
    fun menuHeightFollowsEntryHeights() {
        val entries =
            listOf(
                TrayMenuEntry.Header("Aegis", "ready", ready = true),
                TrayMenuEntry.Divider,
                TrayMenuEntry.Action("Exit", onClick = {}),
            )
        assertEquals(66 + 13 + 38 + 16, trayMenuHeightDp(entries))
    }
}
