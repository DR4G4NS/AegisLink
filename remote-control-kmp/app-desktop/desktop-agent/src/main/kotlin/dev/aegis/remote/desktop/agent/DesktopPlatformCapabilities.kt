package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DesktopSessionBackend
import dev.aegis.remote.core.model.DesktopSessionEnvironment
import dev.aegis.remote.core.model.selectBackend
import dev.aegis.remote.desktop.input.AwtRobotAvailabilityProbe
import dev.aegis.remote.desktop.input.XdotoolLocator
import dev.aegis.remote.desktop.input.YdotoolAvailabilityProbe
import java.awt.GraphicsEnvironment

data class DesktopCapabilityReport(
    val capture: CapabilityStatus,
    val input: CapabilityStatus,
    val linuxPreflight: LinuxPreflightReport? = null,
    val windowsPreflight: WindowsPreflightReport? = null,
)

interface DesktopCapabilityDetector {
    fun detect(): DesktopCapabilityReport
}

class DesktopPlatformCapabilityDetector(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val sessionType: String? = System.getenv("XDG_SESSION_TYPE"),
    private val display: String? = System.getenv("DISPLAY"),
    private val waylandDisplay: String? = System.getenv("WAYLAND_DISPLAY"),
    private val headless: Boolean = GraphicsEnvironment.isHeadless(),
    private val xdotoolAvailable: () -> Boolean = { XdotoolLocator.isAvailable() },
    private val ydotoolAvailable: () -> Boolean = { YdotoolAvailabilityProbe().inspect().available },
    private val awtRobotAvailable: () -> Boolean = AwtRobotAvailabilityProbe::isAvailable,
    private val linuxPreflightDetector: (() -> LinuxPreflightReport?)? = null,
    private val windowsPreflightDetector: (() -> WindowsPreflightReport?)? = null,
) : DesktopCapabilityDetector {
    override fun detect(): DesktopCapabilityReport {
        val backend =
            DesktopSessionEnvironment(
                osName = osName,
                sessionType = sessionType,
                waylandDisplay = waylandDisplay,
                x11Display = display,
            ).selectBackend()
        val linuxPreflight =
            if (osName.contains("linux", ignoreCase = true)) {
                linuxPreflightDetector?.invoke()
            } else {
                null
            }
        val windowsPreflight =
            if (osName.contains("windows", ignoreCase = true)) {
                windowsPreflightDetector?.invoke()
            } else {
                null
            }
        if (headless &&
            (backend.backend != DesktopSessionBackend.LinuxWayland || !backend.displayMarkerPresent)
        ) {
            return DesktopCapabilityReport(
                capture = CapabilityStatus.Unavailable,
                input = CapabilityStatus.Unavailable,
                linuxPreflight = linuxPreflight,
                windowsPreflight = windowsPreflight,
            )
        }

        return when (backend.backend) {
            DesktopSessionBackend.Windows -> {
                DesktopCapabilityReport(
                    capture = CapabilityStatus.Available,
                    input = CapabilityStatus.Available,
                    linuxPreflight = linuxPreflight,
                    windowsPreflight = windowsPreflight,
                )
            }

            DesktopSessionBackend.LinuxWayland -> {
                DesktopCapabilityReport(
                    capture = CapabilityStatus.Degraded,
                    input = if (ydotoolAvailable()) CapabilityStatus.Available else CapabilityStatus.Unavailable,
                    linuxPreflight = linuxPreflight,
                    windowsPreflight = windowsPreflight,
                )
            }

            DesktopSessionBackend.LinuxX11 -> {
                DesktopCapabilityReport(
                    capture = CapabilityStatus.Available,
                    input =
                        if (xdotoolAvailable() || awtRobotAvailable()) {
                            CapabilityStatus.Available
                        } else {
                            CapabilityStatus.Unavailable
                        },
                    linuxPreflight = linuxPreflight,
                    windowsPreflight = windowsPreflight,
                )
            }

            DesktopSessionBackend.Headless,
            DesktopSessionBackend.Unsupported,
            -> {
                DesktopCapabilityReport(
                    capture = CapabilityStatus.Unavailable,
                    input = CapabilityStatus.Unavailable,
                    linuxPreflight = linuxPreflight,
                    windowsPreflight = windowsPreflight,
                )
            }
        }
    }
}
