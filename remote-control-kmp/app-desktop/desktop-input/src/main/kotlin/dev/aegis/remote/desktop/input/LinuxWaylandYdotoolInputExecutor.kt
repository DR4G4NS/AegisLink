package dev.aegis.remote.desktop.input

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.KeyLocation
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.input.RemoteInputExecutor
import dev.aegis.remote.core.model.AppError
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Wayland input through an already configured ydotool/ydotoold socket.
 *
 * Aegis deliberately never launches ydotoold: access to /dev/uinput and the
 * daemon socket is a local-administrator decision. Every client invocation is
 * a direct, bounded argv execution with no shell.
 */
class LinuxWaylandYdotoolInputExecutor(
    private val runner: YdotoolCommandRunner = ProcessYdotoolCommandRunner(),
    private val tool: String = "ydotool",
    /**
     * Optional `wtype` client used for text ydotool cannot type. ydotool only knows US
     * keycodes, so accents and other non-ASCII characters need the wlroots
     * virtual-keyboard protocol (Hyprland, Sway, river…) that wtype speaks.
     */
    private val unicodeTextTool: String? = LinuxExecutableLocator.find("wtype")?.toString(),
) : RemoteInputExecutor {
    private val stateLock = Any()
    private val pressedKeys = linkedSetOf<Int>()
    private val pressedButtons = linkedSetOf<Int>()

    override suspend fun execute(event: RemoteInputEvent) {
        synchronized(stateLock) {
            try {
                executeLocked(event)
            } catch (error: DesktopInputException) {
                releasePossiblePartialEvent(event)
                releaseTrackedBestEffort()
                throw error
            } catch (error: IllegalArgumentException) {
                releasePossiblePartialEvent(event)
                releaseTrackedBestEffort()
                throw error
            } catch (error: IllegalStateException) {
                releasePossiblePartialEvent(event)
                releaseTrackedBestEffort()
                throw error
            }
        }
    }

    override suspend fun releaseAll() {
        synchronized(stateLock) {
            var firstFailure: DesktopInputException? = null
            if (pressedKeys.isNotEmpty()) {
                val keys = pressedKeys.toList().asReversed()
                runCatching { invoke("key", keys.map { "$it:0" }) }
                    .onSuccess { pressedKeys.clear() }
                    .onFailure { firstFailure = it as? DesktopInputException ?: unavailable(it.message.orEmpty()) }
            }
            if (pressedButtons.isNotEmpty()) {
                val buttons = pressedButtons.toList().asReversed()
                runCatching { invoke("click", buttons.map { it.ydotoolButtonEvent(pressed = false) }) }
                    .onSuccess { pressedButtons.clear() }
                    .onFailure { if (firstFailure == null) firstFailure = it as? DesktopInputException ?: unavailable(it.message.orEmpty()) }
            }
            firstFailure?.let { throw it }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun executeLocked(event: RemoteInputEvent) {
        when (event) {
            is RemoteInputEvent.MouseMove -> {
                invoke(
                    "mousemove",
                    "--absolute",
                    "-x",
                    event.x.toString(),
                    "-y",
                    event.y.toString(),
                )
            }

            is RemoteInputEvent.MouseMoveRelative -> {
                invoke(
                    "mousemove",
                    "-x",
                    event.deltaX.toString(),
                    "-y",
                    event.deltaY.toString(),
                )
            }

            is RemoteInputEvent.MouseButton -> {
                val button = event.button.toYdotoolButton()
                invoke("click", button.ydotoolButtonEvent(event.pressed))
                if (event.pressed) pressedButtons += button else pressedButtons -= button
            }

            is RemoteInputEvent.Scroll -> {
                val horizontal = event.deltaX.roundToInt()
                // Aegis follows AWT wheel semantics: positive Y scrolls down.
                // Linux REL_WHEEL uses the opposite sign.
                val vertical = -event.deltaY.roundToInt()
                if (horizontal != 0 || vertical != 0) {
                    invoke(
                        "mousemove",
                        "--wheel",
                        "-x",
                        horizontal.toString(),
                        "-y",
                        vertical.toString(),
                    )
                }
            }

            is RemoteInputEvent.Key -> {
                val key = event.toLinuxInputKeyCode()
                invoke("key", "$key:${if (event.pressed) 1 else 0}")
                if (event.pressed) pressedKeys += key else pressedKeys -= key
            }

            is RemoteInputEvent.TextInput -> {
                typeText(event.text)
            }

            is RemoteInputEvent.Shortcut -> {
                val keys = event.keys.map { it.toLinuxInputKeyCode(KeyLocation.Standard) }
                if (keys.isNotEmpty()) {
                    invoke("key", keys.map { "$it:1" } + keys.asReversed().map { "$it:0" })
                }
            }

            is RemoteInputEvent.ClipboardSync,
            is RemoteInputEvent.SelectMonitor,
            is RemoteInputEvent.SetQuality,
            -> {
                Unit
            }
        }
    }

    private fun typeText(text: String) {
        if (text.isEmpty()) return
        if (text.all(::isYdotoolTypeable)) {
            // ydotool interprets backslash escapes in command-line text by default.
            // Disable that parser so the remote text is injected byte-for-byte.
            invoke("type", "--key-delay=0", "--key-hold=0", "--escape=0", "--", text)
            return
        }
        if (text.any { it.isISOControl() && it != '\t' && it != '\n' }) {
            throw DesktopInputException(
                AppError.Validation("Wayland text input rejects control characters other than tab and newline"),
            )
        }
        val wtype =
            unicodeTextTool
                ?: throw DesktopInputException(
                    AppError.CapabilityUnavailable(
                        "ydotool can only type printable ASCII; install wtype to send accented or non-Latin text on Wayland",
                    ),
                )
        // wtype types Unicode through the virtual-keyboard protocol, but its stdin/text mode
        // maps '\n' to Linefeed rather than Return, so line breaks and tabs go as named keys.
        wtypeSegments(text).forEach { segment ->
            val args =
                when (segment) {
                    "\n" -> listOf(wtype, "-k", "Return")
                    "\t" -> listOf(wtype, "-k", "Tab")
                    else -> listOf(wtype, "--", segment)
                }
            val result = runner.run(args)
            if (result.exitCode != 0) {
                throw unavailable("Wayland Unicode text through wtype failed: ${result.output.take(YDOTOOL_MAX_DIAGNOSTIC_CHARS)}")
            }
        }
    }

    private fun releasePossiblePartialEvent(event: RemoteInputEvent) {
        runCatching {
            when (event) {
                is RemoteInputEvent.Key -> {
                    if (event.pressed) invokeBestEffort("key", "${event.toLinuxInputKeyCode()}:0")
                }

                is RemoteInputEvent.MouseButton -> {
                    if (event.pressed) {
                        invokeBestEffort("click", event.button.toYdotoolButton().ydotoolButtonEvent(pressed = false))
                    }
                }

                is RemoteInputEvent.Shortcut -> {
                    val keys = event.keys.map { it.toLinuxInputKeyCode(KeyLocation.Standard) }
                    if (keys.isNotEmpty()) invokeBestEffort("key", keys.asReversed().map { "$it:0" })
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private fun releaseTrackedBestEffort() {
        if (pressedKeys.isNotEmpty()) {
            invokeBestEffort("key", pressedKeys.toList().asReversed().map { "$it:0" })
        }
        if (pressedButtons.isNotEmpty()) {
            invokeBestEffort(
                "click",
                pressedButtons
                    .toList()
                    .asReversed()
                    .map { it.ydotoolButtonEvent(pressed = false) },
            )
        }
    }

    private fun invoke(vararg args: String) {
        invokeCommand(args.asList())
    }

    private fun invoke(
        action: String,
        arguments: List<String>,
    ) {
        invokeCommand(listOf(action) + arguments)
    }

    private fun invokeCommand(args: List<String>) {
        val result =
            runner.run(
                buildList {
                    add(tool)
                    addAll(args)
                },
            )
        if (result.exitCode != 0) {
            throw unavailable("Wayland input through ydotool failed: ${result.output.take(YDOTOOL_MAX_DIAGNOSTIC_CHARS)}")
        }
    }

    private fun invokeBestEffort(vararg args: String) {
        runCatching {
            runner.run(
                buildList {
                    add(tool)
                    addAll(args)
                },
            )
        }
    }

    private fun invokeBestEffort(
        action: String,
        arguments: List<String>,
    ) {
        runCatching {
            runner.run(
                buildList {
                    add(tool)
                    add(action)
                    addAll(arguments)
                },
            )
        }
    }

    private fun unavailable(message: String): DesktopInputException = DesktopInputException(AppError.CapabilityUnavailable(message))
}

data class YdotoolAvailability(
    val available: Boolean,
    val executable: String? = null,
    val detail: String,
)

class YdotoolAvailabilityProbe(
    private val runner: YdotoolCommandRunner = ProcessYdotoolCommandRunner(),
    private val pathValue: String? = System.getenv("PATH"),
) {
    /**
     * `ydotool debug` opens the configured daemon socket but emits no input.
     * A zero exit therefore proves both the client and its pre-existing daemon
     * socket are usable without Aegis starting a privileged background service.
     */
    fun inspect(): YdotoolAvailability {
        val executable =
            YdotoolLocator.find(pathValue)
                ?: return YdotoolAvailability(
                    available = false,
                    detail = "ydotool is not executable on PATH",
                )
        val result = runner.run(listOf(executable.toString(), "debug"))
        return if (result.exitCode == 0) {
            YdotoolAvailability(
                available = true,
                executable = executable.toString(),
                detail = "ydotool client and existing ydotoold socket are available",
            )
        } else {
            YdotoolAvailability(
                available = false,
                executable = executable.toString(),
                detail =
                    result.output
                        .take(YDOTOOL_MAX_DIAGNOSTIC_CHARS)
                        .ifBlank { "ydotool could not connect to an existing ydotoold socket" },
            )
        }
    }
}

fun interface YdotoolCommandRunner {
    fun run(command: List<String>): YdotoolCommandResult
}

data class YdotoolCommandResult(
    val exitCode: Int,
    val output: String = "",
)

class ProcessYdotoolCommandRunner(
    private val timeoutMillis: Long = YDOTOOL_COMMAND_TIMEOUT_MILLIS,
) : YdotoolCommandRunner {
    override fun run(command: List<String>): YdotoolCommandResult {
        require(command.isNotEmpty()) { "ydotool command must not be empty" }
        require(timeoutMillis > 0) { "ydotool timeout must be positive" }
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (error: java.io.IOException) {
                return YdotoolCommandResult(127, error.message.orEmpty())
            } catch (error: SecurityException) {
                return YdotoolCommandResult(127, error.message.orEmpty())
            }
        val reader = Executors.newSingleThreadExecutor()
        return try {
            val output = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(YDOTOOL_FORCE_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                return YdotoolCommandResult(
                    124,
                    "ydotool command timed out after ${timeoutMillis}ms. " +
                        runCatching { output.get(YDOTOOL_FORCE_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }.getOrDefault(""),
                )
            }
            YdotoolCommandResult(
                process.exitValue(),
                runCatching { output.get(YDOTOOL_FORCE_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }.getOrDefault(""),
            )
        } finally {
            reader.shutdownNow()
        }
    }
}

object YdotoolLocator {
    fun find(pathValue: String? = System.getenv("PATH")): Path? = LinuxExecutableLocator.find("ydotool", pathValue)
}

object LinuxExecutableLocator {
    fun find(
        name: String,
        pathValue: String? = System.getenv("PATH"),
    ): Path? =
        pathValue
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map {
                Path
                    .of(it)
                    .resolve(name)
                    .toAbsolutePath()
                    .normalize()
            }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

private fun isYdotoolTypeable(char: Char): Boolean = char == '\t' || char == '\n' || char in ' '..'~'

/** Splits text into printable runs and single `\n` / `\t` tokens, preserving order. */
internal fun wtypeSegments(text: String): List<String> {
    val segments = mutableListOf<String>()
    val run = StringBuilder()
    text.forEach { char ->
        if (char == '\n' || char == '\t') {
            if (run.isNotEmpty()) {
                segments += run.toString()
                run.clear()
            }
            segments += char.toString()
        } else {
            run.append(char)
        }
    }
    if (run.isNotEmpty()) segments += run.toString()
    return segments
}

private fun RemoteInputEvent.Key.toLinuxInputKeyCode(): Int = code.toLinuxInputKeyCode(location)

private fun KeyCode.toLinuxInputKeyCode(location: KeyLocation): Int =
    when (this) {
        KeyCode.Enter -> if (location == KeyLocation.Numpad) 96 else 28
        KeyCode.Control -> if (location == KeyLocation.Right) 97 else 29
        KeyCode.Alt -> if (location == KeyLocation.Right) 100 else 56
        KeyCode.Shift -> if (location == KeyLocation.Right) 54 else 42
        KeyCode.Meta -> if (location == KeyLocation.Right) 126 else 125
        else -> toFixedLinuxInputKeyCode()
    }

private fun KeyCode.toFixedLinuxInputKeyCode(): Int =
    when (this) {
        KeyCode.Escape -> 1
        KeyCode.Backspace -> 14
        KeyCode.Tab -> 15
        KeyCode.Space -> 57
        KeyCode.ArrowUp -> 103
        KeyCode.ArrowDown -> 108
        KeyCode.ArrowLeft -> 105
        KeyCode.ArrowRight -> 106
        KeyCode.Character -> throw DesktopInputException(AppError.Validation("Use TextInput for character injection"))
        else -> error("Key $this requires a location-aware Linux mapping")
    }

private fun MouseButtonType.toYdotoolButton(): Int =
    when (this) {
        MouseButtonType.Left -> 0
        MouseButtonType.Right -> 1
        MouseButtonType.Middle -> 2
        MouseButtonType.Back -> 6
        MouseButtonType.Forward -> 5
    }

private fun Int.ydotoolButtonEvent(pressed: Boolean): String = "0x${((if (pressed) 0x40 else 0x80) or this).toString(16).uppercase()}"

private const val YDOTOOL_COMMAND_TIMEOUT_MILLIS = 5_000L
private const val YDOTOOL_FORCE_STOP_TIMEOUT_MILLIS = 1_000L
private const val YDOTOOL_MAX_DIAGNOSTIC_CHARS = 300
