package dev.aegis.remote.desktop.input

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.input.RemoteInputExecutor
import dev.aegis.remote.core.model.AppError
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * X11 adapter using xdotool, which injects through the XTEST extension. The
 * executable is a deliberate runtime capability: Wayland is never routed here.
 */
class LinuxX11InputExecutor(
    private val runner: X11CommandRunner = ProcessX11CommandRunner(),
    private val tool: String = "xdotool",
    private val headless: () -> Boolean = { java.awt.GraphicsEnvironment.isHeadless() },
) : RemoteInputExecutor {
    private val stateLock = Any()
    private val pressedKeys = linkedSetOf<KeyCode>()
    private val pressedButtons = linkedSetOf<MouseButtonType>()

    @Suppress("CyclomaticComplexMethod")
    override suspend fun execute(event: RemoteInputEvent) {
        if (headless()) throw unavailable("X11 input injection is unavailable in a headless environment")
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
            var firstFailure: Throwable? = null
            pressedKeys.toList().asReversed().forEach { key ->
                runCatching { run("keyup", key.toX11Key()) }
                    .onSuccess { pressedKeys -= key }
                    .onFailure { if (firstFailure == null) firstFailure = it }
            }
            pressedButtons.toList().asReversed().forEach { button ->
                runCatching { run("mouseup", button.toX11Button().toString()) }
                    .onSuccess { pressedButtons -= button }
                    .onFailure { if (firstFailure == null) firstFailure = it }
            }
            firstFailure?.let { throw it }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun executeLocked(event: RemoteInputEvent) {
        when (event) {
            is RemoteInputEvent.MouseMove -> {
                run("mousemove", "--sync", event.x.toString(), event.y.toString())
            }

            is RemoteInputEvent.MouseMoveRelative -> {
                run("mousemove_relative", "--sync", "--", event.deltaX.toString(), event.deltaY.toString())
            }

            is RemoteInputEvent.MouseButton -> {
                run(if (event.pressed) "mousedown" else "mouseup", event.button.toX11Button().toString())
                if (event.pressed) pressedButtons += event.button else pressedButtons -= event.button
            }

            is RemoteInputEvent.Scroll -> {
                scroll(event.deltaX, event.deltaY)
            }

            is RemoteInputEvent.Key -> {
                run(if (event.pressed) "keydown" else "keyup", event.code.toX11Key())
                if (event.pressed) pressedKeys += event.code else pressedKeys -= event.code
            }

            is RemoteInputEvent.TextInput -> {
                if (event.text.isNotEmpty()) run("type", "--clearmodifiers", "--delay", "0", "--", event.text)
            }

            is RemoteInputEvent.Shortcut -> {
                event.keys.forEach { run("keydown", it.toX11Key()) }
                event.keys.asReversed().forEach { run("keyup", it.toX11Key()) }
            }

            is RemoteInputEvent.ClipboardSync,
            is RemoteInputEvent.SelectMonitor,
            is RemoteInputEvent.SetQuality,
            -> {
                Unit
            }
        }
    }

    private fun releasePossiblePartialEvent(event: RemoteInputEvent) {
        runCatching {
            when (event) {
                is RemoteInputEvent.Key -> {
                    if (event.pressed) runBestEffort("keyup", event.code.toX11Key())
                }

                is RemoteInputEvent.MouseButton -> {
                    if (event.pressed) runBestEffort("mouseup", event.button.toX11Button().toString())
                }

                is RemoteInputEvent.Shortcut -> {
                    event.keys.asReversed().forEach { key -> runBestEffort("keyup", key.toX11Key()) }
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private fun releaseTrackedBestEffort() {
        pressedKeys.toList().asReversed().forEach { key ->
            runCatching { run("keyup", key.toX11Key()) }
                .onSuccess { pressedKeys -= key }
        }
        pressedButtons.toList().asReversed().forEach { button ->
            runCatching { run("mouseup", button.toX11Button().toString()) }
                .onSuccess { pressedButtons -= button }
        }
    }

    private fun runBestEffort(vararg args: String) {
        runCatching { runner.run(listOf(tool, *args)) }
    }

    private fun scroll(
        deltaX: Float,
        deltaY: Float,
    ) {
        val vertical = deltaY.roundToInt()
        val horizontal = deltaX.roundToInt()
        if (vertical != 0) clickRepeated(if (vertical > 0) 5 else 4, abs(vertical))
        if (horizontal != 0) clickRepeated(if (horizontal > 0) 7 else 6, abs(horizontal))
    }

    private fun clickRepeated(
        button: Int,
        count: Int,
    ) {
        run("click", "--repeat", count.coerceAtMost(100).toString(), button.toString())
    }

    private fun run(vararg args: String) {
        val result = runner.run(listOf(tool, *args))
        if (result.exitCode != 0) {
            throw DesktopInputException(AppError.CapabilityUnavailable("X11 input injection failed: ${result.output.take(300)}"))
        }
    }

    private fun unavailable(message: String): DesktopInputException = DesktopInputException(AppError.CapabilityUnavailable(message))
}

interface X11CommandRunner {
    fun run(command: List<String>): X11CommandResult
}

data class X11CommandResult(
    val exitCode: Int,
    val output: String = "",
)

class ProcessX11CommandRunner(
    private val timeoutMillis: Long = X11_COMMAND_TIMEOUT_MILLIS,
) : X11CommandRunner {
    override fun run(command: List<String>): X11CommandResult {
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (error: java.io.IOException) {
                return X11CommandResult(127, error.message.orEmpty())
            } catch (error: SecurityException) {
                return X11CommandResult(127, error.message.orEmpty())
            }
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            val output =
                runCatching { process.inputStream.bufferedReader().use { it.readText() } }
                    .getOrDefault("")
            return X11CommandResult(124, "X11 input command timed out after ${timeoutMillis}ms. $output")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return X11CommandResult(process.exitValue(), output)
    }
}

private const val X11_COMMAND_TIMEOUT_MILLIS = 5_000L

object XdotoolLocator {
    fun isAvailable(pathValue: String? = System.getenv("PATH")): Boolean =
        pathValue
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve("xdotool") }
            .any { Files.isRegularFile(it) && Files.isExecutable(it) }
}

private fun MouseButtonType.toX11Button(): Int =
    when (this) {
        MouseButtonType.Left -> 1
        MouseButtonType.Middle -> 2
        MouseButtonType.Right -> 3
        MouseButtonType.Back -> 8
        MouseButtonType.Forward -> 9
    }

private fun KeyCode.toX11Key(): String =
    when (this) {
        KeyCode.Enter -> "Return"
        KeyCode.Escape -> "Escape"
        KeyCode.Backspace -> "BackSpace"
        KeyCode.Tab -> "Tab"
        KeyCode.Space -> "space"
        KeyCode.ArrowUp -> "Up"
        KeyCode.ArrowDown -> "Down"
        KeyCode.ArrowLeft -> "Left"
        KeyCode.ArrowRight -> "Right"
        KeyCode.Control -> "ctrl"
        KeyCode.Alt -> "alt"
        KeyCode.Shift -> "shift"
        KeyCode.Meta -> "super"
        KeyCode.Character -> throw DesktopInputException(AppError.Validation("Use TextInput for character injection"))
    }
