package dev.aegis.remote.desktop.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxPreflightTest {
    @Test
    fun explicitX11WinsOverWaylandMarkersWithoutSelectingWaylandInput() {
        val runner = RecordingPreflightRunner()
        val report =
            LinuxPreflightDetector(
                osName = "Linux",
                architecture = "x86_64",
                environment =
                    mapOf(
                        "PATH" to "",
                        "XDG_SESSION_TYPE" to "x11",
                        "WAYLAND_DISPLAY" to "wayland-test",
                        "DISPLAY" to ":99",
                        "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/tmp/aegis-test-bus",
                    ),
                userHome = temporaryHome(),
                commandRunner = runner,
                portProbe = { true },
                nativePortalBridgeAvailable = { true },
            ).detect()

        assertEquals("linuxx11", report.sessionBackend)
        assertEquals("linux-x11", report.capabilities.getValue("input").backend)
        assertEquals(LinuxPreflightStatus.Unavailable, report.capabilities.getValue("ydotool-socket").status)
        assertFalse(runner.commands.any { it.firstOrNull() == "ydotool" })
    }

    @Test
    fun explicitWaylandWinsOverX11MarkerAndReportsPortalCaptureAsConsentGated() {
        val bin = temporaryExecutableDirectory("pw-cli", "ydotool", "wl-copy", "wl-paste", "sshd", "ssh-keygen")
        val runner = RecordingPreflightRunner()
        val report =
            LinuxPreflightDetector(
                osName = "Linux",
                architecture = "x86_64",
                environment =
                    mapOf(
                        "PATH" to bin.toString(),
                        "XDG_SESSION_TYPE" to "wayland",
                        "WAYLAND_DISPLAY" to "wayland-test",
                        "DISPLAY" to ":99",
                        "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/tmp/aegis-test-bus",
                    ),
                userHome = temporaryHome(),
                commandRunner = runner,
                portProbe = { true },
                nativePortalBridgeAvailable = { true },
            ).detect()

        assertEquals("linuxwayland", report.sessionBackend)
        assertEquals("linux-wayland", report.capabilities.getValue("input").backend)
        assertEquals(LinuxPreflightStatus.Degraded, report.capabilities.getValue("capture").status)
        assertEquals("linux-wayland-portal-pipewire", report.capabilities.getValue("capture").backend)
        assertFalse(runner.commands.any { it.firstOrNull() == "xdotool" })
        assertTrue(runner.commands.any { it.firstOrNull() == "ydotool" })
    }

    @Test
    fun emptyDisplayEnvironmentIsHeadlessAndDoesNotDegradeToWayland() {
        val runner = RecordingPreflightRunner()
        val report =
            LinuxPreflightDetector(
                osName = "Linux",
                architecture = "x86_64",
                environment =
                    mapOf(
                        "PATH" to "",
                        "XDG_SESSION_TYPE" to "",
                        "WAYLAND_DISPLAY" to "",
                        "DISPLAY" to "",
                    ),
                userHome = temporaryHome(),
                commandRunner = runner,
                portProbe = { true },
                nativePortalBridgeAvailable = { true },
            ).detect()

        assertEquals("headless", report.sessionBackend)
        assertEquals(LinuxPreflightStatus.Unavailable, report.capabilities.getValue("session").status)
        assertEquals(LinuxPreflightStatus.Unavailable, report.capabilities.getValue("capture").status)
        assertEquals(LinuxPreflightStatus.Unavailable, report.capabilities.getValue("input").status)
        assertFalse(runner.commands.any { it.firstOrNull() == "ydotool" })
    }

    @Test
    fun managedAegisSshdMakesAnOccupiedPortAvailable() {
        val report =
            LinuxPreflightDetector(
                osName = "Linux",
                architecture = "x86_64",
                environment = mapOf("PATH" to ""),
                userHome = temporaryHome(),
                commandRunner = RecordingPreflightRunner(),
                portProbe = { false },
                managedSshdProbe = { _, _ -> true },
                nativePortalBridgeAvailable = { false },
            ).detect()

        val capability = report.capabilities.getValue("openssh-port")
        assertEquals(LinuxPreflightStatus.Available, capability.status)
        assertTrue(capability.cause.contains("Aegis-managed"))
    }

    @Test
    fun windowsNeverUsesLinuxBackendsEvenWhenLinuxMarkersArePresent() {
        val runner = RecordingPreflightRunner()
        val report =
            LinuxPreflightDetector(
                osName = "Windows 11",
                architecture = "amd64",
                environment =
                    mapOf(
                        "PATH" to "/usr/bin",
                        "XDG_SESSION_TYPE" to "wayland",
                        "WAYLAND_DISPLAY" to "wayland-test",
                        "DISPLAY" to ":99",
                    ),
                commandRunner = runner,
                portProbe = { error("Windows must not probe the Linux SSH port") },
                nativePortalBridgeAvailable = { error("Windows must not probe the Linux bridge") },
            ).detect()

        assertEquals("windows", report.sessionBackend)
        assertEquals(1, report.capabilities.size)
        assertFalse(runner.commands.isNotEmpty())
    }

    private fun temporaryHome(): Path = Files.createTempDirectory("aegis-preflight-home")

    private fun temporaryExecutableDirectory(vararg names: String): Path {
        val directory = Files.createTempDirectory("aegis-preflight-bin")
        names.forEach { name ->
            val file = Files.createFile(directory.resolve(name))
            Files.setPosixFilePermissions(
                file,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        return directory
    }
}

private class RecordingPreflightRunner : LinuxPreflightCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): LinuxPreflightCommandResult {
        commands += command
        return LinuxPreflightCommandResult(exitCode = 0, output = "ok")
    }
}
