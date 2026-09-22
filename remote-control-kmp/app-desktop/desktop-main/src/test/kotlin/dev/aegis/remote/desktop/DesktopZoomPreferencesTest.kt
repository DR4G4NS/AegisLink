package dev.aegis.remote.desktop

import androidx.compose.ui.unit.Density
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.desktop.capture.CaptureConfig
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopZoomPreferencesTest {
    @Test
    fun clampsBelowAndAboveSupportedRange() {
        assertEquals(DESKTOP_ZOOM_MIN_PERCENT, clampZoomPercent(0))
        assertEquals(DESKTOP_ZOOM_MAX_PERCENT, clampZoomPercent(999))
    }

    @Test
    fun changesInTenPercentStepsAndResets() {
        assertEquals(110, DesktopZoomPreferences.increase(100))
        assertEquals(90, DesktopZoomPreferences.decrease(100))
        assertEquals(100, DesktopZoomPreferences.reset())
        assertEquals(200, DesktopZoomPreferences.increase(200))
        assertEquals(80, DesktopZoomPreferences.decrease(80))
    }

    @Test
    fun persistsAndReadsTheSavedLevel() {
        withTestPreferences { node ->
            val first = DesktopZoomPreferences(node)
            assertEquals(100, first.load())

            first.save(150)

            assertEquals(150, DesktopZoomPreferences(node).load())
        }
    }

    @Test
    fun savedLevelIsAppliedToTheBaseDensityAtStartup() {
        withTestPreferences { node ->
            node.put("zoomPercent", "170")
            val startupPreferences = DesktopZoomPreferences(node)
            val startupDensity = DesktopZoomPreferences.effectiveDensity(Density(2f, 1.25f), startupPreferences.load())

            assertEquals(3.4f, startupDensity.density)
            assertEquals(2.125f, startupDensity.fontScale)
        }
    }

    @Test
    fun zoomDoesNotChangeMonitorCaptureOrRemoteCoordinateGeometry() {
        val monitor = MonitorInfo(MonitorId("primary"), "Primary", 2560, 1440, 1.5f, -1280, 0, true)
        val capture = CaptureConfig(monitor.id, maxWidth = 1920, maxHeight = 1080, maxFps = 60)
        val remoteCoordinates = -640 to 720

        DesktopZoomPreferences.effectiveDensity(Density(2f, 1f), 200)

        assertEquals(2560, monitor.width)
        assertEquals(1440, monitor.height)
        assertEquals(-1280, monitor.originX)
        assertEquals(1920, capture.maxWidth)
        assertEquals(1080, capture.maxHeight)
        assertEquals(60, capture.maxFps)
        assertEquals(-640 to 720, remoteCoordinates)
    }

    @Test
    fun corruptConfigurationFallsBackToOneHundredPercent() {
        withTestPreferences { node ->
            listOf("not-a-percent", "79", "201", "105").forEach { corruptValue ->
                node.put("zoomPercent", corruptValue)
                assertEquals(100, DesktopZoomPreferences(node).load())
            }
        }
    }

    @Test
    fun effectiveDensityAlwaysUsesTheUnscaledBaseDensity() {
        val base = Density(density = 2f, fontScale = 1.5f)

        repeat(4) {
            val effective = DesktopZoomPreferences.effectiveDensity(base, 150)
            assertEquals(3f, effective.density)
            assertEquals(2.25f, effective.fontScale)
        }
    }

    @Test
    fun shortcutsRequireTheExpectedModifierAndSelectTheExpectedAction() {
        assertEquals(
            DesktopZoomAction.Increase,
            desktopZoomActionForShortcut(DesktopZoomShortcutKey.Plus, ctrlPressed = true, metaPressed = false, isMac = false),
        )
        assertEquals(
            DesktopZoomAction.Increase,
            desktopZoomActionForShortcut(DesktopZoomShortcutKey.Equals, ctrlPressed = true, metaPressed = false, isMac = false),
        )
        assertEquals(
            DesktopZoomAction.Decrease,
            desktopZoomActionForShortcut(DesktopZoomShortcutKey.Minus, ctrlPressed = true, metaPressed = false, isMac = false),
        )
        assertEquals(
            DesktopZoomAction.Reset,
            desktopZoomActionForShortcut(DesktopZoomShortcutKey.Zero, ctrlPressed = true, metaPressed = false, isMac = false),
        )
        assertEquals(
            DesktopZoomAction.Reset,
            desktopZoomActionForShortcut(DesktopZoomShortcutKey.Zero, ctrlPressed = false, metaPressed = true, isMac = true),
        )
        assertNull(
            desktopZoomActionForShortcut(DesktopZoomShortcutKey.Plus, ctrlPressed = false, metaPressed = false, isMac = false),
        )
    }

    private fun withTestPreferences(block: (Preferences) -> Unit) {
        val node = Preferences.userRoot().node("dev/aegis/test/${UUID.randomUUID()}")
        try {
            block(node)
        } finally {
            runCatching { node.removeNode() }
        }
    }
}
