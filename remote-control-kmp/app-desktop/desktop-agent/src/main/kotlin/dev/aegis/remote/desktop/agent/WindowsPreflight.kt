package dev.aegis.remote.desktop.agent

import com.sun.jna.platform.win32.User32
import dev.aegis.remote.core.model.DesktopSessionEnvironment
import dev.aegis.remote.core.model.selectBackend
import dev.aegis.remote.desktop.webrtc.WindowsNativePreflightProbe
import dev.aegis.remote.desktop.webrtc.isWindowsDesktopDuplicationSelected
import dev.aegis.remote.desktop.webrtc.probeWindowsDesktopDuplication
import dev.aegis.remote.desktop.webrtc.probeWindowsWebRtcNativeRuntime
import kotlinx.serialization.Serializable
import java.awt.GraphicsEnvironment
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Serializable
enum class WindowsPreflightStatus {
    Available,
    Degraded,
    Unavailable,
}

@Serializable
data class WindowsPreflightCapability(
    val status: WindowsPreflightStatus,
    val cause: String,
    val nextAction: String,
    val backend: String? = null,
)

@Serializable
data class WindowsPreflightReport(
    val operatingSystem: String,
    val architecture: String,
    val sessionBackend: String,
    val capabilities: Map<String, WindowsPreflightCapability>,
)

// Keep OS inputs and side-effecting probes individually injectable for deterministic preflight tests.
@Suppress("LongParameterList")
class WindowsPreflightDetector(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val architecture: String = System.getProperty("os.arch").orEmpty(),
    private val userHome: Path = Path.of(System.getProperty("user.home") ?: "."),
    private val appData: String? = System.getenv("APPDATA"),
    private val headless: Boolean = GraphicsEnvironment.isHeadless(),
    private val portProbe: (Int) -> Boolean = ::isPortAvailable,
    private val openSshScriptDirectory: () -> Path? = ::locateWindowsOpenSshScriptDirectory,
    private val webrtcNativeProbe: () -> WindowsPreflightCapability = ::probeWebRtcNativeRuntime,
    private val desktopDuplicationProbe: (
        WindowsPreflightCapability,
        WindowsPreflightCapability,
    ) -> WindowsPreflightCapability = ::probeDesktopDuplication,
    private val sendInputProbe: (WindowsPreflightCapability) -> WindowsPreflightCapability = ::probeSendInput,
    private val dpapiProbe: () -> WindowsPreflightCapability = ::probeDpapi,
    private val openSshServiceRunning: () -> Boolean = ::isAegisOpenSshServiceRunning,
    private val networkCategories: () -> List<String> = ::queryWindowsNetworkCategories,
) {
    fun detect(): WindowsPreflightReport {
        val backendLabel =
            DesktopSessionEnvironment(
                osName = osName,
                sessionType = null,
                waylandDisplay = null,
                x11Display = null,
            ).selectBackend()
                .backend
                .name
                .lowercase()
        return if (osName.contains("windows", ignoreCase = true)) {
            windowsReport(backendLabel)
        } else {
            unsupportedReport(backendLabel)
        }
    }

    private fun unsupportedReport(backendLabel: String): WindowsPreflightReport =
        WindowsPreflightReport(
            operatingSystem = osName,
            architecture = architecture,
            sessionBackend = backendLabel,
            capabilities =
                mapOf(
                    "operating-system" to
                        capability(
                            WindowsPreflightStatus.Unavailable,
                            "Windows preflight is not applicable to this operating system",
                            "Run the Windows host on Windows 10/11 x64",
                        ),
                ),
        )

    private fun windowsReport(backendLabel: String): WindowsPreflightReport {
        val interactiveSession = interactiveSessionCapability()
        val webrtcNative = webrtcNativeProbe()
        val desktopDuplication = desktopDuplicationProbe(interactiveSession, webrtcNative)
        val sendInput = sendInputProbe(interactiveSession)
        val dpapi = dpapiProbe()

        return WindowsPreflightReport(
            operatingSystem = osName,
            architecture = architecture,
            sessionBackend = backendLabel,
            capabilities =
                linkedMapOf(
                    "operating-system" to capability(WindowsPreflightStatus.Available, "Windows host detected", "No action required", "windows"),
                    "architecture" to architectureCapability(),
                    "interactive-session" to interactiveSession,
                    "webrtc-native" to webrtcNative,
                    "desktop-duplication" to desktopDuplication,
                    "send-input" to sendInput,
                    "dpapi" to dpapi,
                    "openssh-scripts" to openSshScriptsCapability(),
                    "openssh-port" to openSshPortCapability(),
                    "network-profile" to networkProfileCapability(),
                    "data-directory" to dataDirectoryCapability(),
                    "autostart" to autostartCapability(),
                    "capture" to captureCapability(interactiveSession, webrtcNative, desktopDuplication),
                    "input" to inputCapability(interactiveSession, sendInput),
                    "clipboard" to clipboardCapability(interactiveSession),
                ),
        )
    }

    private fun interactiveSessionCapability(): WindowsPreflightCapability =
        if (headless) {
            capability(
                WindowsPreflightStatus.Unavailable,
                "The desktop session is headless",
                "Launch Aegis from an interactive Windows user session",
            )
        } else {
            capability(
                WindowsPreflightStatus.Available,
                "An interactive desktop session is available",
                "No action required",
            )
        }

    private fun openSshScriptsCapability(): WindowsPreflightCapability {
        val directory = openSshScriptDirectory()
        return if (directory != null) {
            capability(
                WindowsPreflightStatus.Available,
                "Managed OpenSSH provisioning scripts are present",
                "No action required",
                directory.toString(),
            )
        } else {
            capability(
                WindowsPreflightStatus.Degraded,
                "Managed OpenSSH provisioning scripts were not found beside the running host",
                "Install with the provisioned Setup or set AEGIS_OPENSSH_SCRIPTS to the script directory",
            )
        }
    }

    private fun openSshPortCapability(): WindowsPreflightCapability {
        val portFree = portProbe(WINDOWS_AEGIS_PREFLIGHT_SSH_PORT)
        val managedRunning = openSshServiceRunning()
        return when {
            portFree -> {
                capability(
                    WindowsPreflightStatus.Available,
                    "TCP ${WINDOWS_AEGIS_PREFLIGHT_SSH_PORT} is available for the isolated AegisOpenSSH service",
                    "No action required",
                    "aegis-openssh",
                )
            }

            managedRunning -> {
                capability(
                    WindowsPreflightStatus.Available,
                    "Isolated AegisOpenSSH is already listening on TCP ${WINDOWS_AEGIS_PREFLIGHT_SSH_PORT}",
                    "No action required",
                    "aegis-openssh",
                )
            }

            else -> {
                capability(
                    WindowsPreflightStatus.Degraded,
                    "TCP ${WINDOWS_AEGIS_PREFLIGHT_SSH_PORT} is already occupied or blocked",
                    "Stop the conflicting service or reinstall the provisioned AegisOpenSSH instance",
                    "aegis-openssh",
                )
            }
        }
    }

    private fun networkProfileCapability(): WindowsPreflightCapability {
        val categories = networkCategories().map { it.trim() }.filter { it.isNotBlank() }
        val publicOnly =
            categories.any(::isPublicNetworkCategory) &&
                categories.none(::isPrivateOrDomainNetworkCategory)
        return if (publicOnly) {
            capability(
                WindowsPreflightStatus.Degraded,
                "NETWORK_PUBLIC",
                "SET_NETWORK_PRIVATE",
                categories.joinToString(),
            )
        } else {
            capability(
                WindowsPreflightStatus.Available,
                "NETWORK_PRIVATE_OR_DOMAIN",
                "No action required",
                categories.joinToString().ifBlank { "undetermined" },
            )
        }
    }

    private fun architectureCapability(): WindowsPreflightCapability {
        val normalized = architecture.lowercase()
        val supported = normalized in setOf("amd64", "x86_64")
        return capability(
            if (supported) WindowsPreflightStatus.Available else WindowsPreflightStatus.Unavailable,
            if (supported) "JVM architecture is Windows x64" else "Unsupported JVM architecture: $architecture",
            if (supported) "No action required" else "Run the Windows x64 distributable",
            architecture,
        )
    }

    private fun dataDirectoryCapability(): WindowsPreflightCapability {
        val directory = userHome.resolve(".aegis")
        val writable =
            (Files.isDirectory(directory) && Files.isWritable(directory)) ||
                (!Files.exists(directory) && Files.isWritable(directory.parent ?: userHome))
        return if (writable) {
            capability(
                WindowsPreflightStatus.Available,
                "The user-scoped Aegis data directory is writable",
                "No action required",
                directory.toString(),
            )
        } else {
            capability(
                WindowsPreflightStatus.Unavailable,
                "The user-scoped Aegis data directory is not writable",
                "Fix ownership/permissions below the user profile; Aegis will not change global ACLs",
                directory.toString(),
            )
        }
    }

    private fun autostartCapability(): WindowsPreflightCapability {
        val startup =
            appData
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it, "Microsoft", "Windows", "Start Menu", "Programs", "Startup") }
        val writable =
            startup != null &&
                (
                    (Files.isDirectory(startup) && Files.isWritable(startup)) ||
                        (!Files.exists(startup) && Files.isWritable(startup.parent ?: userHome))
                )
        return if (writable) {
            capability(
                WindowsPreflightStatus.Available,
                "The Windows Startup folder is writable",
                "No action required",
                startup.toString(),
            )
        } else {
            capability(
                WindowsPreflightStatus.Degraded,
                "The Windows Startup folder is unavailable or not writable",
                "Autostart remains optional; packaged installs can still register Startup entries",
            )
        }
    }

    private fun captureCapability(
        interactiveSession: WindowsPreflightCapability,
        webrtcNative: WindowsPreflightCapability,
        desktopDuplication: WindowsPreflightCapability,
    ): WindowsPreflightCapability {
        if (!isWindowsDesktopDuplicationSelected(osName)) {
            return capability(
                WindowsPreflightStatus.Unavailable,
                "The Windows Desktop Duplication backend was not selected",
                "Run Aegis on Windows 10/11 x64",
                "windows-desktop-duplication",
            )
        }
        val statuses = listOf(interactiveSession.status, webrtcNative.status, desktopDuplication.status)
        return when {
            statuses.any { it == WindowsPreflightStatus.Unavailable } -> {
                capability(
                    WindowsPreflightStatus.Unavailable,
                    "Windows capture prerequisites are incomplete",
                    "Repair the interactive-session, webrtc-native, and desktop-duplication capabilities above",
                    "windows-desktop-duplication",
                )
            }

            statuses.any { it == WindowsPreflightStatus.Degraded } -> {
                capability(
                    WindowsPreflightStatus.Degraded,
                    "Windows capture is selected but one or more prerequisites are degraded",
                    "Review the desktop-duplication capability before starting a remote session",
                    "windows-desktop-duplication",
                )
            }

            else -> {
                capability(
                    WindowsPreflightStatus.Available,
                    "The Windows Desktop Duplication capture backend is ready",
                    "No action required",
                    "windows-desktop-duplication",
                )
            }
        }
    }

    private fun inputCapability(
        interactiveSession: WindowsPreflightCapability,
        sendInput: WindowsPreflightCapability,
    ): WindowsPreflightCapability =
        when {
            interactiveSession.status == WindowsPreflightStatus.Unavailable ||
                sendInput.status == WindowsPreflightStatus.Unavailable -> {
                capability(
                    WindowsPreflightStatus.Unavailable,
                    "Windows input injection prerequisites are incomplete",
                    "Repair the interactive-session and send-input capabilities above",
                    "windows-send-input",
                )
            }

            else -> {
                capability(
                    WindowsPreflightStatus.Available,
                    "The Windows SendInput backend is ready",
                    "No action required",
                    "windows-send-input",
                )
            }
        }

    private fun clipboardCapability(interactiveSession: WindowsPreflightCapability): WindowsPreflightCapability =
        if (interactiveSession.status == WindowsPreflightStatus.Unavailable) {
            capability(
                WindowsPreflightStatus.Unavailable,
                "The AWT clipboard bridge requires an interactive desktop session",
                "Launch Aegis from an interactive Windows user session",
                "windows-awt-clipboard",
            )
        } else {
            capability(
                WindowsPreflightStatus.Available,
                "The AWT clipboard bridge is available",
                "No action required",
                "windows-awt-clipboard",
            )
        }

    private fun capability(
        status: WindowsPreflightStatus,
        cause: String,
        nextAction: String,
        backend: String? = null,
    ) = WindowsPreflightCapability(status, cause, nextAction, backend)
}

private fun probeWebRtcNativeRuntime(): WindowsPreflightCapability = probeWindowsWebRtcNativeRuntime().toPreflightCapability()

private fun probeDesktopDuplication(
    interactiveSession: WindowsPreflightCapability,
    webrtcNative: WindowsPreflightCapability,
): WindowsPreflightCapability =
    probeWindowsDesktopDuplication(
        interactiveSessionAvailable = interactiveSession.status == WindowsPreflightStatus.Available,
        webrtcNativeAvailable = webrtcNative.status == WindowsPreflightStatus.Available,
    ).toPreflightCapability()

private fun probeSendInput(interactiveSession: WindowsPreflightCapability): WindowsPreflightCapability {
    if (interactiveSession.status == WindowsPreflightStatus.Unavailable) {
        return windowsPreflightCapability(
            WindowsPreflightStatus.Unavailable,
            "SendInput requires an interactive desktop session",
            "Launch Aegis from an interactive Windows user session",
            "windows-send-input",
        )
    }
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        return windowsPreflightCapability(
            WindowsPreflightStatus.Unavailable,
            "SendInput preflight can only execute on Windows",
            "Run this probe on a Windows host",
            "windows-send-input",
        )
    }
    return runCatching {
        checkNotNull(User32.INSTANCE) { "User32 is unavailable" }
        windowsPreflightCapability(
            WindowsPreflightStatus.Available,
            "JNA can access the User32 SendInput bridge",
            "No action required",
            "windows-send-input",
        )
    }.getOrElse { error ->
        windowsPreflightCapability(
            WindowsPreflightStatus.Unavailable,
            "SendInput is unavailable: ${error.message ?: error.javaClass.simpleName}",
            "Run Aegis from an interactive Windows user session",
            "windows-send-input",
        )
    }
}

private fun probeDpapi(): WindowsPreflightCapability {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        return windowsPreflightCapability(
            WindowsPreflightStatus.Unavailable,
            "DPAPI preflight can only execute on Windows",
            "Run this probe on a Windows host",
            "windows-dpapi",
        )
    }
    return runCatching {
        val protector = WindowsDpapiPrivateKeyProtector()
        val plaintext = "aegis-windows-preflight".encodeToByteArray()
        val protected = protector.protect(plaintext)
        check(!protected.contentEquals(plaintext)) { "DPAPI did not transform the payload" }
        check(protector.unprotect(protected).contentEquals(plaintext)) { "DPAPI round-trip failed" }
        windowsPreflightCapability(
            WindowsPreflightStatus.Available,
            "DPAPI can protect and unprotect user-scoped secrets",
            "No action required",
            "windows-dpapi",
        )
    }.getOrElse { error ->
        windowsPreflightCapability(
            WindowsPreflightStatus.Unavailable,
            "DPAPI is unavailable: ${error.message ?: error.javaClass.simpleName}",
            "Run Aegis under the intended Windows user profile",
            "windows-dpapi",
        )
    }
}

private fun WindowsNativePreflightProbe.toPreflightCapability(): WindowsPreflightCapability =
    windowsPreflightCapability(
        status =
            when {
                available -> WindowsPreflightStatus.Available
                degraded -> WindowsPreflightStatus.Degraded
                else -> WindowsPreflightStatus.Unavailable
            },
        cause = cause,
        nextAction = nextAction,
        backend = backend,
    )

private fun windowsPreflightCapability(
    status: WindowsPreflightStatus,
    cause: String,
    nextAction: String,
    backend: String? = null,
) = WindowsPreflightCapability(status, cause, nextAction, backend)

fun locateWindowsOpenSshScriptDirectory(): Path? {
    val explicitCandidates =
        listOfNotNull(
            System.getProperty("aegis.openssh.scripts")?.takeIf(String::isNotBlank),
            System.getenv("AEGIS_OPENSSH_SCRIPTS")?.takeIf(String::isNotBlank),
        ).map(Path::of)
    val processDirectory =
        runCatching {
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)
                ?.let(Path::of)
                ?.toAbsolutePath()
                ?.parent
        }.getOrNull()
    val workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath()
    val codeSourceDirectory =
        runCatching {
            Path
                .of(
                    WindowsPreflightDetector::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toAbsolutePath()
                .parent
        }.getOrNull()
    val roots =
        buildList {
            processDirectory?.let(::add)
            add(workingDirectory)
            codeSourceDirectory?.let { directory ->
                generateSequence(directory) { it.parent }.take(5).forEach(::add)
            }
        }
    val candidates =
        explicitCandidates +
            roots.flatMap { root ->
                listOf(
                    root.resolve("provisioning/openssh"),
                    root.resolve("packaging/windows/openssh"),
                )
            }
    return candidates
        .map { candidate -> candidate.normalize() }
        .distinct()
        .firstOrNull { directory ->
            WINDOWS_OPENSSH_SCRIPT_NAMES.all { script -> Files.isRegularFile(directory.resolve(script)) }
        }
}

private fun isPortAvailable(port: Int): Boolean =
    runCatching {
        ServerSocket(port, 1).use { true }
    }.getOrDefault(false)

private fun isAegisOpenSshServiceRunning(): Boolean =
    runCatching {
        val process =
            ProcessBuilder("sc.exe", "query", "AegisOpenSSH")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0 && Regex("""(?i)(?:STATE|ESTADO)\s*:\s*4\b""").containsMatchIn(output)
    }.getOrDefault(false)

internal fun isPublicNetworkCategory(category: String): Boolean =
    category.equals("Public", ignoreCase = true) ||
        category.equals("Público", ignoreCase = true) ||
        category.equals("Publico", ignoreCase = true)

internal fun isPrivateOrDomainNetworkCategory(category: String): Boolean =
    category.equals("Private", ignoreCase = true) ||
        category.equals("Privado", ignoreCase = true) ||
        category.contains("Domain", ignoreCase = true) ||
        category.contains("Dominio", ignoreCase = true)

private fun queryWindowsNetworkCategories(): List<String> =
    runCatching {
        val process =
            ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-NetConnectionProfile).NetworkCategory",
            ).redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return emptyList()
        }
        output.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Get-NetConnectionProfile") }
    }.getOrDefault(emptyList())

private const val WINDOWS_AEGIS_PREFLIGHT_SSH_PORT = 48_222

val WINDOWS_OPENSSH_SCRIPT_NAMES =
    setOf(
        "aegis-openssh-managed-instance.ps1",
        "install-aegis-openssh.ps1",
        "inspect-aegis-openssh.ps1",
        "enroll-aegis-ssh-key.ps1",
        "remove-aegis-ssh-key.ps1",
        "uninstall-aegis-openssh.ps1",
    )
