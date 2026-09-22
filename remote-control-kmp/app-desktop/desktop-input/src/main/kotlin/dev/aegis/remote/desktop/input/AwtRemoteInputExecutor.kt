package dev.aegis.remote.desktop.input

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.input.RemoteInputExecutor
import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.DesktopSessionBackend
import dev.aegis.remote.core.model.DesktopSessionEnvironment
import dev.aegis.remote.core.model.selectBackend
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.math.roundToInt

class DesktopInputException(
    val appError: AppError,
) : RuntimeException(appError.message)

enum class DesktopInputBackend {
    AwtRobot,
    WindowsSendInput,
    LinuxWaylandYdotool,
    LinuxWaylandPortalRequired,
    LinuxX11Xtest,
    Unavailable,
}

data class DesktopInputExecutorSelection(
    val backend: DesktopInputBackend,
    val executor: RemoteInputExecutor,
)

interface DesktopRobotInput {
    fun mouseMove(
        x: Int,
        y: Int,
    )

    fun mouseMoveRelative(
        deltaX: Int,
        deltaY: Int,
    ) {
        val current = MouseInfo.getPointerInfo().location
        mouseMove(current.x + deltaX, current.y + deltaY)
    }

    fun mousePress(buttonMask: Int)

    fun mouseRelease(buttonMask: Int)

    fun mouseWheel(amount: Int)

    fun keyPress(keyCode: Int)

    fun keyRelease(keyCode: Int)
}

class AwtDesktopRobotInput : DesktopRobotInput {
    private val robot: Robot by lazy { Robot() }

    override fun mouseMove(
        x: Int,
        y: Int,
    ) = robot.mouseMove(x, y)

    override fun mousePress(buttonMask: Int) = robot.mousePress(buttonMask)

    override fun mouseRelease(buttonMask: Int) = robot.mouseRelease(buttonMask)

    override fun mouseWheel(amount: Int) = robot.mouseWheel(amount)

    override fun keyPress(keyCode: Int) = robot.keyPress(keyCode)

    override fun keyRelease(keyCode: Int) = robot.keyRelease(keyCode)
}

object AwtRobotAvailabilityProbe {
    fun isAvailable(): Boolean =
        !GraphicsEnvironment.isHeadless() &&
            runCatching { Robot() }.isSuccess
}

class AwtRemoteInputExecutor(
    private val robot: DesktopRobotInput = AwtDesktopRobotInput(),
    private val headless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
) : RemoteInputExecutor {
    private val stateLock = Any()
    private val pressedKeys = linkedSetOf<Int>()
    private val pressedButtons = linkedSetOf<Int>()

    override suspend fun execute(event: RemoteInputEvent) {
        if (headless()) {
            throw DesktopInputException(AppError.CapabilityUnavailable("Desktop input injection is unavailable in a headless environment"))
        }
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
                runCatching { robot.keyRelease(key) }
                    .onSuccess { pressedKeys -= key }
                    .onFailure { if (firstFailure == null) firstFailure = it }
            }
            pressedButtons.toList().asReversed().forEach { button ->
                runCatching { robot.mouseRelease(button) }
                    .onSuccess { pressedButtons -= button }
                    .onFailure { if (firstFailure == null) firstFailure = it }
            }
            firstFailure?.let { throw it }
        }
    }

    private fun executeLocked(event: RemoteInputEvent) {
        when (event) {
            is RemoteInputEvent.MouseMove -> robot.mouseMove(event.x, event.y)

            is RemoteInputEvent.MouseMoveRelative -> robot.mouseMoveRelative(event.deltaX, event.deltaY)

            is RemoteInputEvent.MouseButton -> executeMouseButton(event)

            is RemoteInputEvent.Scroll -> executeScroll(event)

            is RemoteInputEvent.Key -> executeKey(event)

            is RemoteInputEvent.TextInput -> executeText(event.text)

            is RemoteInputEvent.Shortcut -> executeShortcut(event.keys)

            is RemoteInputEvent.ClipboardSync,
            is RemoteInputEvent.SelectMonitor,
            is RemoteInputEvent.SetQuality,
            -> Unit
        }
    }

    private fun executeMouseButton(event: RemoteInputEvent.MouseButton) {
        val mask = event.button.toAwtMask()
        if (event.pressed) {
            robot.mousePress(mask)
            pressedButtons += mask
        } else {
            robot.mouseRelease(mask)
            pressedButtons -= mask
        }
    }

    private fun executeScroll(event: RemoteInputEvent.Scroll) {
        val amount = event.deltaY.roundToInt()
        if (amount != 0) robot.mouseWheel(amount)
    }

    private fun executeKey(event: RemoteInputEvent.Key) {
        val key = event.code.toAwtKeyCode()
        if (event.pressed) {
            robot.keyPress(key)
            pressedKeys += key
        } else {
            robot.keyRelease(key)
            pressedKeys -= key
        }
    }

    private fun executeText(text: String) {
        text.forEach { char ->
            val keyCode = KeyEvent.getExtendedKeyCodeForChar(char.code)
            if (keyCode == KeyEvent.VK_UNDEFINED) {
                throw DesktopInputException(
                    AppError.Validation("Character cannot be injected through AWT Robot: U+${char.code.toString(16)}"),
                )
            }
            if (char.isUpperCase()) robot.keyPress(KeyEvent.VK_SHIFT)
            robot.keyPress(keyCode)
            robot.keyRelease(keyCode)
            if (char.isUpperCase()) robot.keyRelease(KeyEvent.VK_SHIFT)
        }
    }

    private fun executeShortcut(keys: List<KeyCode>) {
        val awtKeys = keys.map { it.toAwtKeyCode() }
        awtKeys.forEach(robot::keyPress)
        awtKeys.asReversed().forEach(robot::keyRelease)
    }

    private fun releasePossiblePartialEvent(event: RemoteInputEvent) {
        runCatching {
            when (event) {
                is RemoteInputEvent.Key -> {
                    if (event.pressed) releaseKeyBestEffort(event.code.toAwtKeyCode())
                }

                is RemoteInputEvent.MouseButton -> {
                    if (event.pressed) releaseButtonBestEffort(event.button.toAwtMask())
                }

                is RemoteInputEvent.TextInput -> {
                    event.text.reversed().forEach { char ->
                        val keyCode = KeyEvent.getExtendedKeyCodeForChar(char.code)
                        if (keyCode != KeyEvent.VK_UNDEFINED) releaseKeyBestEffort(keyCode)
                    }
                    releaseKeyBestEffort(KeyEvent.VK_SHIFT)
                }

                is RemoteInputEvent.Shortcut -> {
                    event.keys.asReversed().forEach { key -> releaseKeyBestEffort(key.toAwtKeyCode()) }
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private fun releaseTrackedBestEffort() {
        pressedKeys.toList().asReversed().forEach { key ->
            runCatching { robot.keyRelease(key) }
                .onSuccess { pressedKeys -= key }
        }
        pressedButtons.toList().asReversed().forEach { button ->
            runCatching { robot.mouseRelease(button) }
                .onSuccess { pressedButtons -= button }
        }
    }

    private fun releaseKeyBestEffort(key: Int) {
        runCatching { robot.keyRelease(key) }
    }

    private fun releaseButtonBestEffort(button: Int) {
        runCatching { robot.mouseRelease(button) }
    }

    private fun MouseButtonType.toAwtMask(): Int =
        when (this) {
            MouseButtonType.Left -> InputEvent.BUTTON1_DOWN_MASK
            MouseButtonType.Middle -> InputEvent.BUTTON2_DOWN_MASK
            MouseButtonType.Right -> InputEvent.BUTTON3_DOWN_MASK
            MouseButtonType.Back -> InputEvent.getMaskForButton(4)
            MouseButtonType.Forward -> InputEvent.getMaskForButton(5)
        }

    private fun KeyCode.toAwtKeyCode(): Int =
        when (this) {
            KeyCode.Enter -> KeyEvent.VK_ENTER
            KeyCode.Escape -> KeyEvent.VK_ESCAPE
            KeyCode.Backspace -> KeyEvent.VK_BACK_SPACE
            KeyCode.Tab -> KeyEvent.VK_TAB
            KeyCode.Space -> KeyEvent.VK_SPACE
            KeyCode.ArrowUp -> KeyEvent.VK_UP
            KeyCode.ArrowDown -> KeyEvent.VK_DOWN
            KeyCode.ArrowLeft -> KeyEvent.VK_LEFT
            KeyCode.ArrowRight -> KeyEvent.VK_RIGHT
            KeyCode.Control -> KeyEvent.VK_CONTROL
            KeyCode.Alt -> KeyEvent.VK_ALT
            KeyCode.Shift -> KeyEvent.VK_SHIFT
            KeyCode.Meta -> KeyEvent.VK_META
            KeyCode.Character -> throw DesktopInputException(AppError.Validation("Use TextInput for character injection"))
        }
}

class UnavailableRemoteInputExecutor(
    private val reason: String,
) : RemoteInputExecutor {
    override suspend fun execute(event: RemoteInputEvent): Unit = throw DesktopInputException(AppError.CapabilityUnavailable(reason))
}

class DesktopRemoteInputExecutorFactory(
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val sessionType: String? = System.getenv("XDG_SESSION_TYPE"),
    private val display: String? = System.getenv("DISPLAY"),
    private val waylandDisplay: String? = System.getenv("WAYLAND_DISPLAY"),
    private val xdotoolAvailable: () -> Boolean = { XdotoolLocator.isAvailable() },
    private val ydotoolAvailability: () -> YdotoolAvailability = { YdotoolAvailabilityProbe().inspect() },
    private val awtRobotAvailable: () -> Boolean = AwtRobotAvailabilityProbe::isAvailable,
    private val headless: () -> Boolean = { GraphicsEnvironment.isHeadless() },
) {
    fun create(): DesktopInputExecutorSelection {
        val backend =
            DesktopSessionEnvironment(
                osName = osName,
                sessionType = sessionType,
                waylandDisplay = waylandDisplay,
                x11Display = display,
            ).selectBackend()
        if (headless() &&
            (backend.backend != DesktopSessionBackend.LinuxWayland || !backend.displayMarkerPresent)
        ) {
            return unavailable(
                DesktopInputBackend.Unavailable,
                "Desktop input injection is unavailable in a headless environment",
            )
        }

        return when (backend.backend) {
            DesktopSessionBackend.Windows -> {
                DesktopInputExecutorSelection(
                    backend = DesktopInputBackend.WindowsSendInput,
                    executor = WindowsSendInputExecutor(headless = headless),
                )
            }

            DesktopSessionBackend.LinuxWayland,
            DesktopSessionBackend.LinuxX11,
            -> {
                linuxSelection()
            }

            DesktopSessionBackend.Headless,
            DesktopSessionBackend.Unsupported,
            -> {
                unavailable(
                    DesktopInputBackend.Unavailable,
                    "Desktop input injection is not available for this platform yet: $osName",
                )
            }
        }
    }

    private fun linuxSelection(): DesktopInputExecutorSelection {
        val backend =
            DesktopSessionEnvironment(
                osName = osName,
                sessionType = sessionType,
                waylandDisplay = waylandDisplay,
                x11Display = display,
            ).selectBackend().backend
        return when (backend) {
            DesktopSessionBackend.LinuxWayland -> {
                val availability = ydotoolAvailability()
                if (availability.available && availability.executable != null) {
                    DesktopInputExecutorSelection(
                        backend = DesktopInputBackend.LinuxWaylandYdotool,
                        executor =
                            LinuxWaylandYdotoolInputExecutor(
                                tool = availability.executable,
                            ),
                    )
                } else {
                    unavailable(
                        DesktopInputBackend.LinuxWaylandPortalRequired,
                        "Wayland input injection requires an installed ydotool client and an accessible, " +
                            "locally managed ydotoold socket: ${availability.detail}",
                    )
                }
            }

            DesktopSessionBackend.LinuxX11 -> {
                if (xdotoolAvailable()) {
                    DesktopInputExecutorSelection(
                        backend = DesktopInputBackend.LinuxX11Xtest,
                        executor = LinuxX11InputExecutor(headless = headless),
                    )
                } else if (awtRobotAvailable()) {
                    DesktopInputExecutorSelection(
                        backend = DesktopInputBackend.AwtRobot,
                        executor = AwtRemoteInputExecutor(headless = headless),
                    )
                } else {
                    unavailable(
                        DesktopInputBackend.Unavailable,
                        "X11 input injection requires either xdotool (XTEST) or a working AWT Robot backend",
                    )
                }
            }

            else -> {
                unavailable(
                    DesktopInputBackend.Unavailable,
                    "Linux input injection requires a Wayland or X11 session",
                )
            }
        }
    }

    private fun unavailable(
        backend: DesktopInputBackend,
        reason: String,
    ): DesktopInputExecutorSelection =
        DesktopInputExecutorSelection(
            backend = backend,
            executor = UnavailableRemoteInputExecutor(reason),
        )
}
