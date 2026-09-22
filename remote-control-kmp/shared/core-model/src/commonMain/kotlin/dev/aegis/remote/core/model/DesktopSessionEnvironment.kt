package dev.aegis.remote.core.model

/**
 * Explicitly describes the desktop markers used to choose a native backend.
 * Callers pass every value they want considered; production adapters may build
 * this value from the process environment at their boundary.
 */
data class DesktopSessionEnvironment(
    val osName: String,
    val sessionType: String? = null,
    val waylandDisplay: String? = null,
    val x11Display: String? = null,
)

enum class DesktopSessionBackend {
    Windows,
    LinuxX11,
    LinuxWayland,
    Headless,
    Unsupported,
}

enum class DesktopSessionSelectionSource {
    ExplicitSessionType,
    WaylandDisplay,
    X11Display,
    NoDisplayMarker,
}

data class DesktopSessionSelection(
    val backend: DesktopSessionBackend,
    val source: DesktopSessionSelectionSource,
    val displayMarkerPresent: Boolean,
) {
    val isLinuxGraphicalBackend: Boolean
        get() = backend == DesktopSessionBackend.LinuxX11 || backend == DesktopSessionBackend.LinuxWayland
}

/**
 * Resolves one backend with a strict precedence:
 * explicit XDG_SESSION_TYPE, then WAYLAND_DISPLAY, then DISPLAY.
 * An explicit session type always wins over contradictory display markers.
 */
fun DesktopSessionEnvironment.selectBackend(): DesktopSessionSelection {
    val normalizedOs = osName.trim().lowercase()
    if ("windows" in normalizedOs) {
        return DesktopSessionSelection(
            backend = DesktopSessionBackend.Windows,
            source = DesktopSessionSelectionSource.ExplicitSessionType,
            displayMarkerPresent = false,
        )
    }
    if ("linux" !in normalizedOs) {
        return DesktopSessionSelection(
            backend = DesktopSessionBackend.Unsupported,
            source = DesktopSessionSelectionSource.NoDisplayMarker,
            displayMarkerPresent = false,
        )
    }

    when (sessionType.normalizedMarker()) {
        "x11" -> {
            return DesktopSessionSelection(
                backend = DesktopSessionBackend.LinuxX11,
                source = DesktopSessionSelectionSource.ExplicitSessionType,
                displayMarkerPresent = !x11Display.normalizedMarker().isNullOrBlank(),
            )
        }

        "wayland" -> {
            return DesktopSessionSelection(
                backend = DesktopSessionBackend.LinuxWayland,
                source = DesktopSessionSelectionSource.ExplicitSessionType,
                displayMarkerPresent = !waylandDisplay.normalizedMarker().isNullOrBlank(),
            )
        }
    }

    if (!waylandDisplay.normalizedMarker().isNullOrBlank()) {
        return DesktopSessionSelection(
            backend = DesktopSessionBackend.LinuxWayland,
            source = DesktopSessionSelectionSource.WaylandDisplay,
            displayMarkerPresent = true,
        )
    }
    if (!x11Display.normalizedMarker().isNullOrBlank()) {
        return DesktopSessionSelection(
            backend = DesktopSessionBackend.LinuxX11,
            source = DesktopSessionSelectionSource.X11Display,
            displayMarkerPresent = true,
        )
    }
    return DesktopSessionSelection(
        backend = DesktopSessionBackend.Headless,
        source = DesktopSessionSelectionSource.NoDisplayMarker,
        displayMarkerPresent = false,
    )
}

private fun String?.normalizedMarker(): String? = this?.trim()?.lowercase()?.takeIf(String::isNotBlank)
