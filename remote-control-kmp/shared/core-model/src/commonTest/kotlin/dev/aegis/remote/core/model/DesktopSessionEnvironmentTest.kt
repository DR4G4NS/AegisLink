package dev.aegis.remote.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSessionEnvironmentTest {
    @Test
    fun explicitX11WinsOverWaylandMarker() {
        val selection =
            DesktopSessionEnvironment("Linux", "x11", waylandDisplay = "wayland-0", x11Display = ":0")
                .selectBackend()

        assertEquals(DesktopSessionBackend.LinuxX11, selection.backend)
        assertEquals(DesktopSessionSelectionSource.ExplicitSessionType, selection.source)
        assertTrue(selection.displayMarkerPresent)
    }

    @Test
    fun explicitWaylandWinsOverX11Marker() {
        val selection =
            DesktopSessionEnvironment("Linux", "wayland", waylandDisplay = "wayland-0", x11Display = ":0")
                .selectBackend()

        assertEquals(DesktopSessionBackend.LinuxWayland, selection.backend)
        assertEquals(DesktopSessionSelectionSource.ExplicitSessionType, selection.source)
        assertTrue(selection.displayMarkerPresent)
    }

    @Test
    fun waylandMarkerIsUsedWhenSessionTypeIsEmpty() {
        val selection = DesktopSessionEnvironment("Linux", "", waylandDisplay = "wayland-0").selectBackend()

        assertEquals(DesktopSessionBackend.LinuxWayland, selection.backend)
        assertEquals(DesktopSessionSelectionSource.WaylandDisplay, selection.source)
    }

    @Test
    fun x11MarkerIsUsedWhenOnlyDisplayIsPresent() {
        val selection = DesktopSessionEnvironment("Linux", null, x11Display = ":0").selectBackend()

        assertEquals(DesktopSessionBackend.LinuxX11, selection.backend)
        assertEquals(DesktopSessionSelectionSource.X11Display, selection.source)
    }

    @Test
    fun waylandWinsWhenBothDisplayMarkersArePresentWithoutSessionType() {
        val selection =
            DesktopSessionEnvironment("Linux", null, waylandDisplay = "wayland-0", x11Display = ":0")
                .selectBackend()

        assertEquals(DesktopSessionBackend.LinuxWayland, selection.backend)
        assertEquals(DesktopSessionSelectionSource.WaylandDisplay, selection.source)
    }

    @Test
    fun noMarkersAreHeadlessAndNeverWayland() {
        val selection = DesktopSessionEnvironment("Linux").selectBackend()

        assertEquals(DesktopSessionBackend.Headless, selection.backend)
        assertFalse(selection.isLinuxGraphicalBackend)
        assertFalse(selection.displayMarkerPresent)
    }

    @Test
    fun windowsIgnoresUnixMarkers() {
        val selection =
            DesktopSessionEnvironment("Windows 11", "wayland", waylandDisplay = "wayland-0", x11Display = ":0")
                .selectBackend()

        assertEquals(DesktopSessionBackend.Windows, selection.backend)
    }
}
