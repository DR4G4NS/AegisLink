package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DesktopSessionBackend
import dev.aegis.remote.core.model.DesktopSessionEnvironment
import dev.aegis.remote.core.model.DesktopSessionSelection
import dev.aegis.remote.core.model.selectBackend
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Serializable
enum class LinuxPreflightStatus {
    Available,
    Degraded,
    Unavailable,
}

@Serializable
data class LinuxPreflightCapability(
    val status: LinuxPreflightStatus,
    val cause: String,
    val nextAction: String,
    val backend: String? = null,
)

@Serializable
data class LinuxPreflightReport(
    val operatingSystem: String,
    val architecture: String,
    val sessionBackend: String,
    val compositor: String?,
    val capabilities: Map<String, LinuxPreflightCapability>,
)

data class LinuxPreflightCommandResult(
    val exitCode: Int,
    val output: String = "",
)

fun interface LinuxPreflightCommandRunner {
    fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): LinuxPreflightCommandResult
}

class ProcessLinuxPreflightCommandRunner : LinuxPreflightCommandRunner {
    override fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): LinuxPreflightCommandResult {
        require(command.isNotEmpty()) { "preflight command must not be empty" }
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (error: IOException) {
                return LinuxPreflightCommandResult(127, error.javaClass.simpleName)
            } catch (error: SecurityException) {
                return LinuxPreflightCommandResult(127, error.javaClass.simpleName)
            }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return LinuxPreflightCommandResult(124, "command timed out")
        }
        return LinuxPreflightCommandResult(
            exitCode = process.exitValue(),
            output = process.inputStream.bufferedReader().use { it.readText().take(LINUX_PREFLIGHT_OUTPUT_LIMIT) },
        )
    }
}

class LinuxPreflightDetector(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val architecture: String = System.getProperty("os.arch").orEmpty(),
    private val environment: Map<String, String> = System.getenv(),
    private val userHome: Path = Path.of(System.getProperty("user.home") ?: "."),
    private val commandRunner: LinuxPreflightCommandRunner = ProcessLinuxPreflightCommandRunner(),
    private val portProbe: (Int) -> Boolean = ::isPortAvailable,
    private val managedSshdProbe: (Path, Int) -> Boolean = ::isManagedAegisSshd,
    private val nativePortalBridgeAvailable: () -> Boolean = ::isPackagedPortalBridgeAvailable,
) {
    fun detect(): LinuxPreflightReport {
        val selection =
            DesktopSessionEnvironment(
                osName = osName,
                sessionType = environment["XDG_SESSION_TYPE"],
                waylandDisplay = environment["WAYLAND_DISPLAY"],
                x11Display = environment["DISPLAY"],
            ).selectBackend()
        val backendLabel = selection.backend.name.lowercase()
        return if (osName.contains("linux", ignoreCase = true)) {
            linuxReport(selection, backendLabel)
        } else {
            unsupportedReport(backendLabel)
        }
    }

    private fun unsupportedReport(backendLabel: String): LinuxPreflightReport =
        LinuxPreflightReport(
            operatingSystem = osName,
            architecture = architecture,
            sessionBackend = backendLabel,
            compositor = null,
            capabilities =
                mapOf(
                    "operating-system" to
                        capability(
                            LinuxPreflightStatus.Unavailable,
                            "Linux preflight is not applicable to this operating system",
                            "Run the Linux host on Linux",
                        ),
                ),
        )

    private fun linuxReport(
        selection: DesktopSessionSelection,
        backendLabel: String,
    ): LinuxPreflightReport {
        val compositor = compositorLabel()
        return LinuxPreflightReport(
            operatingSystem = osName,
            architecture = architecture,
            sessionBackend = backendLabel,
            compositor = compositor,
            capabilities = detectLinuxCapabilities(selection, compositor),
        )
    }

    private fun detectLinuxCapabilities(
        selection: DesktopSessionSelection,
        compositor: String?,
    ): Map<String, LinuxPreflightCapability> {
        val path = environment["PATH"].orEmpty()
        val dbusAvailable = !environment["DBUS_SESSION_BUS_ADDRESS"].isNullOrBlank()
        val portal = dbusService("portal", "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop")
        val pipewire =
            commandCapability(
                command = listOf("pw-cli", "info", "0"),
                path = path,
                missingCause = "PipeWire is not reachable",
                nextAction = "Start the user PipeWire session; Aegis will not start it",
            )
        val sessionBus = sessionBusCapability(dbusAvailable)
        val portalBackend = portalBackendCapability(portal, compositor)
        val secretService = dbusService("secret-service", "org.freedesktop.secrets", "/org/freedesktop/secrets")
        val sshd = executableCapability("sshd", path, listOf("/usr/bin/sshd", "/usr/sbin/sshd", "/sbin/sshd"))
        val sshKeygen = executableCapability("ssh-keygen", path, listOf("/usr/bin/ssh-keygen", "/usr/local/bin/ssh-keygen"))
        val sshPort = sshPortCapability()
        val xdotool =
            commandCapability(
                command = listOf("xdotool", "getdisplaygeometry"),
                path = path,
                missingCause = "xdotool/XTEST is unavailable",
                nextAction = "Install xdotool and expose the X11 display",
                backend = "linux-x11",
            )
        val ydotool =
            commandCapability(
                command = listOf("ydotool", "debug"),
                path = path,
                missingCause = "ydotool or its existing daemon socket is unavailable",
                nextAction = "Install ydotool and start ydotoold through the distribution/user setup; Aegis will not start it",
                backend = "linux-wayland",
            )
        val xclip = executableCapability("xclip", path, emptyList())
        val wlCopy = executableCapability("wl-copy", path, emptyList())
        val wlPaste = executableCapability("wl-paste", path, emptyList())
        val dataDirectory = dataDirectoryCapability()
        val autostart = autostartCapability()
        val nativeBridge = nativePortalBridgeAvailable()
        val backendCapabilities =
            detectBackendCapabilities(
                selection = selection,
                portal = portal,
                pipewire = pipewire,
                nativeBridge = nativeBridge,
                xdotool = xdotool,
                ydotool = ydotool,
                xclip = xclip,
                wlCopy = wlCopy,
                wlPaste = wlPaste,
            )

        return linkedMapOf(
            "operating-system" to capability(LinuxPreflightStatus.Available, "Linux host detected", "No action required", "linux"),
            "architecture" to architectureCapability(),
            "session" to sessionCapability(selection.backend, selection.displayMarkerPresent),
            "compositor" to compositorCapability(compositor),
            "xdg-desktop-portal" to portal,
            "portal-backend" to portalBackend,
            "pipewire" to pipewire,
            "dbus-session" to sessionBus,
            "secret-service" to secretService,
            "sshd" to sshd,
            "ssh-keygen" to sshKeygen,
            "openssh-port" to sshPort,
            "xdotool-xtest" to xdotool,
            "ydotool-socket" to ydotool,
            "xclip" to xclip,
            "wl-copy" to wlCopy,
            "wl-paste" to wlPaste,
            "data-directory" to dataDirectory,
            "autostart" to autostart,
            "capture" to backendCapabilities.capture,
            "input" to backendCapabilities.input,
            "clipboard" to backendCapabilities.clipboard,
            "portal-bridge" to portalBridgeCapability(nativeBridge),
        )
    }

    private fun sessionBusCapability(available: Boolean): LinuxPreflightCapability =
        if (available) {
            capability(LinuxPreflightStatus.Available, "A session D-Bus address is present", "No action required")
        } else {
            capability(LinuxPreflightStatus.Unavailable, "DBUS_SESSION_BUS_ADDRESS is empty", "Launch Aegis from the graphical user session")
        }

    private fun portalBackendCapability(
        portal: LinuxPreflightCapability,
        compositor: String?,
    ): LinuxPreflightCapability =
        if (portal.status == LinuxPreflightStatus.Available) {
            capability(
                LinuxPreflightStatus.Available,
                "The xdg-desktop-portal service responds",
                "No action required",
                portal.backend,
            )
        } else {
            capability(
                LinuxPreflightStatus.Unavailable,
                "The screen-cast portal service is unavailable",
                "Install and start a user xdg-desktop-portal backend for the compositor",
                compositor,
            )
        }

    private fun sshPortCapability(): LinuxPreflightCapability =
        when {
            portProbe(LINUX_AEGIS_PREFLIGHT_SSH_PORT) -> {
                capability(
                    LinuxPreflightStatus.Available,
                    "TCP ${LINUX_AEGIS_PREFLIGHT_SSH_PORT} is available for the user-scoped child sshd",
                    "No action required",
                    "linux-user-sshd",
                )
            }

            managedSshdProbe(userHome, LINUX_AEGIS_PREFLIGHT_SSH_PORT) -> {
                capability(
                    LinuxPreflightStatus.Available,
                    "TCP ${LINUX_AEGIS_PREFLIGHT_SSH_PORT} is served by the Aegis-managed sshd",
                    "No action required",
                    "linux-user-sshd",
                )
            }

            else -> {
                capability(
                    LinuxPreflightStatus.Unavailable,
                    "TCP ${LINUX_AEGIS_PREFLIGHT_SSH_PORT} is already occupied by another process",
                    "Stop the conflicting user process and restart Aegis",
                    "linux-user-sshd",
                )
            }
        }

    private fun architectureCapability(): LinuxPreflightCapability =
        capability(
            if (architecture.isNotBlank()) LinuxPreflightStatus.Available else LinuxPreflightStatus.Unavailable,
            architecture.ifBlank { "JVM architecture is unknown" },
            "Run the JDK supplied for the target architecture",
        )

    private fun compositorCapability(compositor: String?): LinuxPreflightCapability =
        capability(
            if (compositor != null) LinuxPreflightStatus.Available else LinuxPreflightStatus.Degraded,
            compositor ?: "Compositor was not identified",
            "Keep the graphical session variables available",
        )

    private fun portalBridgeCapability(available: Boolean): LinuxPreflightCapability =
        if (available) {
            capability(
                LinuxPreflightStatus.Available,
                "The packaged Linux Portal/PipeWire bridge is present",
                "No action required",
                "linux-wayland",
            )
        } else {
            capability(
                LinuxPreflightStatus.Unavailable,
                "The packaged Linux Portal/PipeWire bridge is missing",
                "Rebuild the Linux distributable with the native bridge",
                "linux-wayland",
            )
        }

    private fun detectBackendCapabilities(
        selection: DesktopSessionSelection,
        portal: LinuxPreflightCapability,
        pipewire: LinuxPreflightCapability,
        nativeBridge: Boolean,
        xdotool: LinuxPreflightCapability,
        ydotool: LinuxPreflightCapability,
        xclip: LinuxPreflightCapability,
        wlCopy: LinuxPreflightCapability,
        wlPaste: LinuxPreflightCapability,
    ): BackendCapabilities =
        BackendCapabilities(
            capture = captureCapability(selection.backend, portal, pipewire, nativeBridge),
            input = inputCapability(selection.backend, xdotool, ydotool),
            clipboard = clipboardCapability(selection.backend, xclip, wlCopy, wlPaste),
        )

    private fun inputCapability(
        backend: DesktopSessionBackend,
        xdotool: LinuxPreflightCapability,
        ydotool: LinuxPreflightCapability,
    ): LinuxPreflightCapability =
        when (backend) {
            DesktopSessionBackend.LinuxX11 -> {
                xdotool
            }

            DesktopSessionBackend.LinuxWayland -> {
                ydotool
            }

            DesktopSessionBackend.Windows -> {
                capability(LinuxPreflightStatus.Unavailable, "Windows does not use Linux input backends", "Use the Windows SendInput backend")
            }

            else -> {
                capability(LinuxPreflightStatus.Unavailable, "No graphical Linux session was selected", "Start Aegis inside an X11 or Wayland session")
            }
        }

    private fun clipboardCapability(
        backend: DesktopSessionBackend,
        xclip: LinuxPreflightCapability,
        wlCopy: LinuxPreflightCapability,
        wlPaste: LinuxPreflightCapability,
    ): LinuxPreflightCapability =
        when (backend) {
            DesktopSessionBackend.LinuxX11 -> {
                xclip.withBackend("linux-x11")
            }

            DesktopSessionBackend.LinuxWayland -> {
                if (wlCopy.status == LinuxPreflightStatus.Available && wlPaste.status == LinuxPreflightStatus.Available) {
                    capability(LinuxPreflightStatus.Available, "wl-copy and wl-paste are executable", "No action required", "linux-wayland")
                } else {
                    capability(
                        LinuxPreflightStatus.Unavailable,
                        "Wayland clipboard requires both wl-copy and wl-paste",
                        "Install wl-clipboard",
                        "linux-wayland",
                    )
                }
            }

            else -> {
                capability(
                    LinuxPreflightStatus.Unavailable,
                    "No graphical Linux clipboard backend was selected",
                    "Start Aegis inside an X11 or Wayland session",
                )
            }
        }

    private data class BackendCapabilities(
        val capture: LinuxPreflightCapability,
        val input: LinuxPreflightCapability,
        val clipboard: LinuxPreflightCapability,
    )

    private fun sessionCapability(
        backend: DesktopSessionBackend,
        marker: Boolean,
    ): LinuxPreflightCapability =
        if (backend == DesktopSessionBackend.LinuxX11 || backend == DesktopSessionBackend.LinuxWayland) {
            if (marker) {
                capability(LinuxPreflightStatus.Available, "The selected Linux display marker is present", "No action required", backend.name.lowercase())
            } else {
                capability(
                    LinuxPreflightStatus.Unavailable,
                    "The selected session has no usable display marker",
                    "Set the session variables from the graphical login",
                )
            }
        } else {
            capability(LinuxPreflightStatus.Unavailable, "No supported Linux graphical backend was selected", "Start Aegis inside X11 or Wayland")
        }

    private fun captureCapability(
        backend: DesktopSessionBackend,
        portal: LinuxPreflightCapability,
        pipewire: LinuxPreflightCapability,
        nativeBridge: Boolean,
    ): LinuxPreflightCapability =
        when (backend) {
            DesktopSessionBackend.LinuxX11 -> {
                capability(LinuxPreflightStatus.Available, "The Linux X11 capture backend is selected", "No action required", "linux-x11")
            }

            DesktopSessionBackend.LinuxWayland -> {
                if (portal.status == LinuxPreflightStatus.Available &&
                    pipewire.status == LinuxPreflightStatus.Available &&
                    nativeBridge
                ) {
                    capability(
                        LinuxPreflightStatus.Degraded,
                        "Portal/PipeWire capture is ready but requires visible user consent",
                        "Approve the screen selector when a capture session starts",
                        "linux-wayland-portal-pipewire",
                    )
                } else {
                    capability(
                        LinuxPreflightStatus.Unavailable,
                        "Portal/PipeWire prerequisites are incomplete",
                        "Repair portal, PipeWire, D-Bus, and bridge capabilities shown above",
                        "linux-wayland-portal-pipewire",
                    )
                }
            }

            else -> {
                capability(LinuxPreflightStatus.Unavailable, "No Linux capture backend is selected", "Start Aegis inside X11 or Wayland")
            }
        }

    private fun dbusService(
        name: String,
        service: String,
        objectPath: String,
    ): LinuxPreflightCapability {
        val command =
            listOf(
                "gdbus",
                "call",
                "--session",
                "--dest",
                service,
                "--object-path",
                objectPath,
                "--method",
                "org.freedesktop.DBus.Peer.Ping",
            )
        val result = commandRunner.run(command, LINUX_PREFLIGHT_COMMAND_TIMEOUT_MILLIS)
        return if (result.exitCode == 0) {
            capability(LinuxPreflightStatus.Available, "$name service responds on the session bus", "No action required", service)
        } else {
            capability(
                LinuxPreflightStatus.Unavailable,
                "$name service did not respond on the session bus",
                "Start the user service; Aegis will not start privileged/global services",
                service,
            )
        }
    }

    private fun commandCapability(
        command: List<String>,
        path: String,
        missingCause: String,
        nextAction: String,
        backend: String? = null,
    ): LinuxPreflightCapability {
        if (findExecutable(command.first(), path) == null) {
            return capability(LinuxPreflightStatus.Unavailable, missingCause, nextAction, backend)
        }
        val result = commandRunner.run(command, LINUX_PREFLIGHT_COMMAND_TIMEOUT_MILLIS)
        return if (result.exitCode == 0) {
            capability(LinuxPreflightStatus.Available, "${command.first()} responded successfully", "No action required", backend)
        } else {
            capability(LinuxPreflightStatus.Unavailable, result.output.ifBlank { missingCause }, nextAction, backend)
        }
    }

    private fun executableCapability(
        name: String,
        path: String,
        absoluteCandidates: List<String>,
    ): LinuxPreflightCapability {
        val executable = findExecutable(name, path, absoluteCandidates)
        return if (executable != null) {
            capability(LinuxPreflightStatus.Available, "$name is executable", "No action required", executable)
        } else {
            capability(
                LinuxPreflightStatus.Unavailable,
                "$name is not executable",
                "Install the distribution package that provides $name",
            )
        }
    }

    private fun dataDirectoryCapability(): LinuxPreflightCapability {
        val directory = userHome.resolve(".aegis")
        val writable =
            (Files.isDirectory(directory) && Files.isWritable(directory)) ||
                (!Files.exists(directory) && Files.isWritable(directory.parent ?: userHome))
        return if (writable) {
            capability(LinuxPreflightStatus.Available, "The user-scoped Aegis data directory is writable", "No action required", directory.toString())
        } else {
            capability(
                LinuxPreflightStatus.Unavailable,
                "The user-scoped Aegis data directory is not writable",
                "Fix ownership/permissions below the user home; Aegis will not change global permissions",
                directory.toString(),
            )
        }
    }

    private fun autostartCapability(): LinuxPreflightCapability {
        val path = userHome.resolve(".config").resolve("autostart")
        val writable =
            (Files.isDirectory(path) && Files.isWritable(path)) ||
                (!Files.exists(path) && Files.isWritable(path.parent ?: userHome))
        return if (writable) {
            capability(LinuxPreflightStatus.Available, "The user autostart location is writable", "No action required", path.toString())
        } else {
            capability(
                LinuxPreflightStatus.Unavailable,
                "The user autostart location is not writable",
                "Create a user-owned XDG autostart directory",
                path.toString(),
            )
        }
    }

    private fun compositorLabel(): String? =
        environment["XDG_CURRENT_DESKTOP"]?.takeIf(String::isNotBlank)
            ?: environment["XDG_SESSION_DESKTOP"]?.takeIf(String::isNotBlank)
            ?: environment["HYPRLAND_INSTANCE_SIGNATURE"]?.let { "Hyprland" }

    private fun LinuxPreflightCapability.withBackend(backend: String): LinuxPreflightCapability = copy(backend = backend)

    private fun capability(
        status: LinuxPreflightStatus,
        cause: String,
        nextAction: String,
        backend: String? = null,
    ) = LinuxPreflightCapability(status, cause, nextAction, backend)
}

private fun findExecutable(
    name: String,
    path: String,
    absoluteCandidates: List<String> = emptyList(),
): String? {
    val candidates =
        absoluteCandidates +
            path.split(java.io.File.pathSeparatorChar).filter(String::isNotBlank).map { "$it/$name" }
    return candidates.firstOrNull { candidate ->
        val file = Path.of(candidate)
        Files.isRegularFile(file) && Files.isExecutable(file)
    }
}

private fun isPortAvailable(port: Int): Boolean =
    runCatching {
        ServerSocket(port, 1).use { true }
    }.getOrDefault(false)

private fun isManagedAegisSshd(
    userHome: Path,
    port: Int,
): Boolean {
    val root = userHome.resolve(".aegis").resolve("openssh")
    return findManagedLinuxSshdProcess(
        pidPath = root.resolve("sshd.pid"),
        configPath = root.resolve("sshd_config"),
        authorizedKeysPath = root.resolve("authorized_keys"),
        sshdPath = defaultLinuxSshdPath(),
        port = port,
        authorizedUser = System.getProperty("user.name").orEmpty(),
    ) != null
}

private fun isPackagedPortalBridgeAvailable(): Boolean =
    LinuxPreflightDetector::class.java.classLoader
        .getResource("native/linux-x86_64/aegis-pipewire-portal-capture") != null

private const val LINUX_AEGIS_PREFLIGHT_SSH_PORT = 48_222
private const val LINUX_PREFLIGHT_COMMAND_TIMEOUT_MILLIS = 1_500L
private const val LINUX_PREFLIGHT_OUTPUT_LIMIT = 512
