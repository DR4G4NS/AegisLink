package dev.aegis.remote.desktop.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

interface DesktopAutostartManager {
    fun status(): DesktopAutostartStatus

    fun setEnabled(enabled: Boolean): DesktopAutostartStatus
}

data class DesktopAutostartStatus(
    val available: Boolean,
    val enabled: Boolean,
    val message: String,
    val messageCode: AutostartMessageCode? = null,
    val messageContext: Map<String, String> = emptyMap(),
)

class UserDesktopAutostartManager(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val userHome: Path = Path.of(System.getProperty("user.home") ?: "."),
    private val appData: String? = System.getenv("APPDATA"),
    private val launchCommandProvider: () -> List<String>? = ::currentLaunchCommand,
) : DesktopAutostartManager {
    override fun status(): DesktopAutostartStatus {
        val target =
            autostartTarget()
                ?: return DesktopAutostartStatus(
                    available = false,
                    enabled = false,
                    message = "Autostart is not supported on this desktop platform.",
                    messageCode = AutostartMessageCode.Unsupported,
                )
        return DesktopAutostartStatus(
            available = true,
            enabled = target.path.exists(),
            message = target.path.toString(),
            messageCode = AutostartMessageCode.Path,
            messageContext = mapOf("path" to target.path.toString()),
        )
    }

    override fun setEnabled(enabled: Boolean): DesktopAutostartStatus {
        val target =
            autostartTarget()
                ?: return DesktopAutostartStatus(
                    available = false,
                    enabled = false,
                    message = "Autostart is not supported on this desktop platform.",
                    messageCode = AutostartMessageCode.Unsupported,
                )
        if (!enabled) {
            target.path.deleteIfExists()
            return status()
        }
        val command =
            launchCommandProvider()
                ?.takeIf { it.isNotEmpty() }
                ?: return DesktopAutostartStatus(
                    available = false,
                    enabled = false,
                    message = "Autostart requires a packaged desktop launch command.",
                    messageCode = AutostartMessageCode.PackagedCommandRequired,
                )
        target.path.parent?.createDirectories()
        target.path.writeText(target.render(command))
        return status()
    }

    private fun autostartTarget(): AutostartTarget? {
        val normalized = osName.lowercase()
        return when {
            normalized.contains("windows") -> {
                val root =
                    appData?.takeIf { it.isNotBlank() }?.let(Path::of)
                        ?: userHome.resolve("AppData").resolve("Roaming")
                AutostartTarget.Windows(
                    root
                        .resolve("Microsoft")
                        .resolve("Windows")
                        .resolve("Start Menu")
                        .resolve("Programs")
                        .resolve("Startup")
                        .resolve("Aegis Remote Desktop.cmd"),
                )
            }

            normalized.contains("linux") -> {
                AutostartTarget.Linux(
                    userHome
                        .resolve(".config")
                        .resolve("autostart")
                        .resolve("aegis-remote-desktop.desktop"),
                )
            }

            else -> {
                null
            }
        }
    }
}

private sealed class AutostartTarget(
    open val path: Path,
) {
    abstract fun render(command: List<String>): String

    data class Windows(
        override val path: Path,
    ) : AutostartTarget(path) {
        override fun render(command: List<String>): String =
            buildString {
                appendLine("@echo off")
                append("start \"\" ")
                append(command.joinToString(" ") { it.windowsQuote() })
                appendLine()
            }
    }

    data class Linux(
        override val path: Path,
    ) : AutostartTarget(path) {
        override fun render(command: List<String>): String {
            val exec = command.joinToString(" ") { it.desktopExecQuote() }
            return """
                |[Desktop Entry]
                |Type=Application
                |Name=Aegis Remote Desktop
                |Exec=$exec
                |Terminal=false
                |X-GNOME-Autostart-enabled=true
                |
                """.trimMargin()
        }
    }
}

private fun currentLaunchCommand(): List<String>? {
    val info = ProcessHandle.current().info()
    val command = info.command().orElse(null) ?: return null
    return listOf(command) + info.arguments().orElse(emptyArray()).toList()
}

private fun String.windowsQuote(): String = "\"" + replace("\"", "\"\"") + "\""

private fun String.desktopExecQuote(): String {
    if (none { it.isWhitespace() || it == '"' || it == '\\' }) return this
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
