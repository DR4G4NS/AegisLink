package dev.aegis.remote.desktop.input

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.LONG
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinUser
import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.KeyLocation
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.input.RemoteInputExecutor
import dev.aegis.remote.core.model.AppError
import kotlin.math.roundToInt

/** Native Windows input backend. It deliberately uses SendInput rather than
 * synthesizing AWT events so input reaches applications outside the JVM. */
class WindowsSendInputExecutor(
    private val api: WindowsSendInputApi = JnaWindowsSendInputApi(),
    private val headless: () -> Boolean = { java.awt.GraphicsEnvironment.isHeadless() },
) : RemoteInputExecutor {
    private val stateLock = Any()
    private val pressedKeys = linkedMapOf<WindowsPressedKey, WindowsInputPacket.Key>()
    private val pressedButtons = linkedSetOf<MouseButtonType>()

    override suspend fun execute(event: RemoteInputEvent) {
        if (headless()) {
            throw DesktopInputException(AppError.CapabilityUnavailable("Windows input injection is unavailable in a headless environment"))
        }
        val packets =
            when (event) {
                is RemoteInputEvent.MouseMove -> {
                    listOf(api.absoluteMove(event.x, event.y))
                }

                is RemoteInputEvent.MouseMoveRelative -> {
                    listOf(api.relativeMove(event.deltaX, event.deltaY))
                }

                is RemoteInputEvent.MouseButton -> {
                    listOf(buttonPacket(event.button, event.pressed))
                }

                is RemoteInputEvent.Scroll -> {
                    buildList {
                        event.deltaY.roundToInt().takeIf { it != 0 }?.let {
                            // Win32 defines positive WHEEL_DELTA as up; Aegis defines positive Y
                            // as down so every host backend exposes the same protocol semantics.
                            add(WindowsInputPacket.Mouse(MOUSEEVENTF_WHEEL, -it * WHEEL_DELTA))
                        }
                        event.deltaX.roundToInt().takeIf { it != 0 }?.let {
                            add(WindowsInputPacket.Mouse(MOUSEEVENTF_HWHEEL, it * WHEEL_DELTA))
                        }
                    }
                }

                is RemoteInputEvent.Key -> {
                    listOf(event.toWindowsKeyPacket(api))
                }

                is RemoteInputEvent.TextInput -> {
                    event.toWindowsPackets(api)
                }

                is RemoteInputEvent.Shortcut -> {
                    event.keys.map { api.logicalKey(it.toVirtualKey(KeyLocation.Standard), keyUp = false, extended = it.isExtended()) } +
                        event.keys.asReversed().map {
                            api.logicalKey(it.toVirtualKey(KeyLocation.Standard), keyUp = true, extended = it.isExtended())
                        }
                }

                is RemoteInputEvent.ClipboardSync,
                is RemoteInputEvent.SelectMonitor,
                is RemoteInputEvent.SetQuality,
                -> {
                    emptyList()
                }
            }
        if (packets.isNotEmpty()) {
            sendAndTrack(event, packets)
        }
    }

    override suspend fun releaseAll() {
        synchronized(stateLock) {
            val releases = drainReleasePackets()
            if (releases.isEmpty()) return
            runCatching { api.send(releases) }
                .getOrElse { error -> throw wrapSendFailure("release held input", error) }
        }
    }

    private fun sendAndTrack(
        event: RemoteInputEvent,
        packets: List<WindowsInputPacket>,
    ) {
        synchronized(stateLock) {
            runCatching {
                api.send(packets)
                when (event) {
                    is RemoteInputEvent.Key -> {
                        val identity = WindowsPressedKey(event.code, event.scanCode, event.location)
                        val packet = packets.single() as WindowsInputPacket.Key
                        if (event.pressed) {
                            pressedKeys[identity] = packet.copy(keyUp = false)
                        } else {
                            pressedKeys.remove(identity)
                        }
                    }

                    is RemoteInputEvent.MouseButton -> {
                        if (event.pressed) pressedButtons += event.button else pressedButtons -= event.button
                    }

                    else -> {
                        Unit
                    }
                }
            }.onFailure { error ->
                recoverAfterFailedSend(packets)
                throw (error as? DesktopInputException ?: wrapSendFailure("inject input", error))
            }.getOrThrow()
        }
    }

    private fun recoverAfterFailedSend(packets: List<WindowsInputPacket>) {
        val recovery = drainReleasePackets() + packets.releasePacketsForPossiblePartialSend()
        if (recovery.isNotEmpty()) runCatching { api.send(recovery.distinct()) }
    }

    private fun drainReleasePackets(): List<WindowsInputPacket> {
        val releases =
            pressedButtons.toList().asReversed().map { buttonPacket(it, pressed = false) } +
                pressedKeys.values
                    .toList()
                    .asReversed()
                    .map { it.copy(keyUp = true) }
        pressedButtons.clear()
        pressedKeys.clear()
        return releases
    }

    private fun wrapSendFailure(
        operation: String,
        error: Throwable,
    ): DesktopInputException =
        DesktopInputException(
            AppError.CapabilityUnavailable(
                "Windows SendInput could not $operation: ${error.message?.take(300) ?: error::class.simpleName.orEmpty()}",
            ),
        )

    private fun buttonPacket(
        button: MouseButtonType,
        pressed: Boolean,
    ): WindowsInputPacket.Mouse =
        when (button) {
            MouseButtonType.Left -> WindowsInputPacket.Mouse(if (pressed) MOUSEEVENTF_LEFTDOWN else MOUSEEVENTF_LEFTUP)
            MouseButtonType.Right -> WindowsInputPacket.Mouse(if (pressed) MOUSEEVENTF_RIGHTDOWN else MOUSEEVENTF_RIGHTUP)
            MouseButtonType.Middle -> WindowsInputPacket.Mouse(if (pressed) MOUSEEVENTF_MIDDLEDOWN else MOUSEEVENTF_MIDDLEUP)
            MouseButtonType.Back -> WindowsInputPacket.Mouse(if (pressed) MOUSEEVENTF_XDOWN else MOUSEEVENTF_XUP, XBUTTON1)
            MouseButtonType.Forward -> WindowsInputPacket.Mouse(if (pressed) MOUSEEVENTF_XDOWN else MOUSEEVENTF_XUP, XBUTTON2)
        }
}

sealed interface WindowsInputPacket {
    data class Mouse(
        val flags: Int,
        val data: Int = 0,
        val x: Int = 0,
        val y: Int = 0,
    ) : WindowsInputPacket

    data class Key(
        val virtualKey: Int,
        val keyUp: Boolean,
        val scanCode: Int? = null,
        val extended: Boolean = false,
    ) : WindowsInputPacket

    data class Unicode(
        val character: Char,
        val keyUp: Boolean,
    ) : WindowsInputPacket
}

interface WindowsSendInputApi {
    fun absoluteMove(
        x: Int,
        y: Int,
    ): WindowsInputPacket.Mouse

    fun relativeMove(
        deltaX: Int,
        deltaY: Int,
    ): WindowsInputPacket.Mouse = WindowsInputPacket.Mouse(MOUSEEVENTF_MOVE, x = deltaX, y = deltaY)

    fun logicalKey(
        virtualKey: Int,
        keyUp: Boolean,
        extended: Boolean,
    ): WindowsInputPacket.Key = WindowsInputPacket.Key(virtualKey, keyUp, extended = extended)

    fun send(packets: List<WindowsInputPacket>)
}

class JnaWindowsSendInputApi : WindowsSendInputApi {
    override fun absoluteMove(
        x: Int,
        y: Int,
    ): WindowsInputPacket.Mouse {
        val left = User32.INSTANCE.GetSystemMetrics(SM_XVIRTUALSCREEN)
        val top = User32.INSTANCE.GetSystemMetrics(SM_YVIRTUALSCREEN)
        val width = User32.INSTANCE.GetSystemMetrics(SM_CXVIRTUALSCREEN).coerceAtLeast(1)
        val height = User32.INSTANCE.GetSystemMetrics(SM_CYVIRTUALSCREEN).coerceAtLeast(1)
        val normalizedX = ((x - left).toLong() * 65_535 / (width - 1).coerceAtLeast(1)).coerceIn(0, 65_535).toInt()
        val normalizedY = ((y - top).toLong() * 65_535 / (height - 1).coerceAtLeast(1)).coerceIn(0, 65_535).toInt()
        return WindowsInputPacket.Mouse(
            MOUSEEVENTF_MOVE or MOUSEEVENTF_ABSOLUTE or MOUSEEVENTF_VIRTUALDESK,
            x = normalizedX,
            y = normalizedY,
        )
    }

    override fun logicalKey(
        virtualKey: Int,
        keyUp: Boolean,
        extended: Boolean,
    ): WindowsInputPacket.Key {
        val foregroundThread = User32.INSTANCE.GetWindowThreadProcessId(User32.INSTANCE.GetForegroundWindow(), null)
        val layout = User32.INSTANCE.GetKeyboardLayout(foregroundThread)
        val mapped = User32.INSTANCE.MapVirtualKeyEx(virtualKey, MAPVK_VK_TO_VSC_EX, layout)
        if (mapped == 0) return WindowsInputPacket.Key(virtualKey, keyUp, extended = extended)
        return WindowsInputPacket.Key(
            virtualKey = 0,
            keyUp = keyUp,
            scanCode = mapped and 0xff,
            extended = extended || mapped and 0xff00 != 0,
        )
    }

    override fun send(packets: List<WindowsInputPacket>) {
        if (packets.isEmpty()) return
        // JNA only marshals Structure[] arguments that share one contiguous native
        // allocation. Allocating each INPUT separately works for a single event but
        // throws for multi-event batches such as Unicode text (down + up per char).
        packets.chunked(MAX_SEND_INPUT_BATCH).forEach(::sendBatch)
    }

    private fun sendBatch(packets: List<WindowsInputPacket>) {
        @Suppress("UNCHECKED_CAST")
        val inputs = WinUser.INPUT().toArray(packets.size) as Array<WinUser.INPUT>
        packets.forEachIndexed { index, packet -> fillNativeInput(inputs[index], packet) }
        val sent = User32.INSTANCE.SendInput(DWORD(inputs.size.toLong()), inputs, inputs.first().size())
        if (sent.toInt() != inputs.size) {
            val nativeError = Native.getLastError()
            throw DesktopInputException(
                AppError.CapabilityUnavailable(
                    "Windows SendInput accepted ${sent.toInt()} of ${inputs.size} events (Win32 error $nativeError); " +
                        "a secure desktop or a higher-integrity foreground process may be blocking injection",
                ),
            )
        }
    }

    private fun fillNativeInput(
        input: WinUser.INPUT,
        packet: WindowsInputPacket,
    ) {
        when (packet) {
            is WindowsInputPacket.Mouse -> {
                input.type = DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
                input.input.setType(WinUser.MOUSEINPUT::class.java)
                input.input.mi =
                    WinUser.MOUSEINPUT().also {
                        it.dx = LONG(packet.x.toLong())
                        it.dy = LONG(packet.y.toLong())
                        it.mouseData = DWORD(packet.data.toLong() and 0xffffffffL)
                        it.dwFlags = DWORD(packet.flags.toLong())
                    }
            }

            is WindowsInputPacket.Key -> {
                input.type = DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
                input.input.setType(WinUser.KEYBDINPUT::class.java)
                input.input.ki =
                    WinUser.KEYBDINPUT().also {
                        it.wVk = WORD(packet.virtualKey.toLong())
                        packet.scanCode?.let { scanCode -> it.wScan = WORD(scanCode.toLong()) }
                        val flags =
                            (if (packet.keyUp) WinUser.KEYBDINPUT.KEYEVENTF_KEYUP else 0) or
                                (if (packet.scanCode != null) WinUser.KEYBDINPUT.KEYEVENTF_SCANCODE else 0) or
                                (if (packet.extended) WinUser.KEYBDINPUT.KEYEVENTF_EXTENDEDKEY else 0)
                        it.dwFlags = DWORD(flags.toLong())
                    }
            }

            is WindowsInputPacket.Unicode -> {
                input.type = DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
                input.input.setType(WinUser.KEYBDINPUT::class.java)
                input.input.ki =
                    WinUser.KEYBDINPUT().also {
                        it.wVk = WORD(0)
                        it.wScan = WORD(packet.character.code.toLong())
                        it.dwFlags =
                            DWORD(
                                (WinUser.KEYBDINPUT.KEYEVENTF_UNICODE or if (packet.keyUp) WinUser.KEYBDINPUT.KEYEVENTF_KEYUP else 0)
                                    .toLong(),
                            )
                    }
            }
        }
        input.write()
    }
}

/** SendInput accepts large arrays, but bounded batches keep partial-send recovery tractable. */
private const val MAX_SEND_INPUT_BATCH = 64

private data class WindowsPressedKey(
    val code: KeyCode,
    val scanCode: Int?,
    val location: KeyLocation,
)

private fun RemoteInputEvent.Key.toWindowsKeyPacket(api: WindowsSendInputApi): WindowsInputPacket.Key {
    val isExtended = extended ?: code.isExtended(location)
    return scanCode?.let {
        WindowsInputPacket.Key(virtualKey = 0, keyUp = !pressed, scanCode = it, extended = isExtended)
    } ?: api.logicalKey(code.toVirtualKey(location), keyUp = !pressed, extended = isExtended)
}

private fun RemoteInputEvent.TextInput.toWindowsPackets(api: WindowsSendInputApi): List<WindowsInputPacket> {
    val packets = mutableListOf<WindowsInputPacket>()
    var index = 0
    while (index < text.length) {
        val character = text[index]
        when {
            character == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> {
                packets += api.logicalKey(KeyCode.Enter.toVirtualKey(KeyLocation.Standard), keyUp = false, extended = false)
                packets += api.logicalKey(KeyCode.Enter.toVirtualKey(KeyLocation.Standard), keyUp = true, extended = false)
                index += 2
            }

            character == '\n' || character == '\r' -> {
                packets += api.logicalKey(KeyCode.Enter.toVirtualKey(KeyLocation.Standard), keyUp = false, extended = false)
                packets += api.logicalKey(KeyCode.Enter.toVirtualKey(KeyLocation.Standard), keyUp = true, extended = false)
                index += 1
            }

            character == '\t' -> {
                packets += api.logicalKey(KeyCode.Tab.toVirtualKey(KeyLocation.Standard), keyUp = false, extended = false)
                packets += api.logicalKey(KeyCode.Tab.toVirtualKey(KeyLocation.Standard), keyUp = true, extended = false)
                index += 1
            }

            else -> {
                packets += WindowsInputPacket.Unicode(character, false)
                packets += WindowsInputPacket.Unicode(character, true)
                index += 1
            }
        }
    }
    return packets
}

private fun List<WindowsInputPacket>.releasePacketsForPossiblePartialSend(): List<WindowsInputPacket> =
    mapNotNull { packet ->
        when (packet) {
            is WindowsInputPacket.Key -> packet.takeUnless { it.keyUp }?.copy(keyUp = true)
            is WindowsInputPacket.Mouse -> packet.releaseAfterDown()
            is WindowsInputPacket.Unicode -> packet.takeUnless { it.keyUp }?.copy(keyUp = true)
        }
    }.asReversed()

private fun WindowsInputPacket.Mouse.releaseAfterDown(): WindowsInputPacket.Mouse? =
    when (flags) {
        MOUSEEVENTF_LEFTDOWN -> copy(flags = MOUSEEVENTF_LEFTUP)
        MOUSEEVENTF_RIGHTDOWN -> copy(flags = MOUSEEVENTF_RIGHTUP)
        MOUSEEVENTF_MIDDLEDOWN -> copy(flags = MOUSEEVENTF_MIDDLEUP)
        MOUSEEVENTF_XDOWN -> copy(flags = MOUSEEVENTF_XUP)
        else -> null
    }

@Suppress("CyclomaticComplexMethod")
private fun KeyCode.toVirtualKey(location: KeyLocation): Int =
    when (this) {
        KeyCode.Enter -> {
            0x0D
        }

        KeyCode.Escape -> {
            0x1B
        }

        KeyCode.Backspace -> {
            0x08
        }

        KeyCode.Tab -> {
            0x09
        }

        KeyCode.Space -> {
            0x20
        }

        KeyCode.ArrowUp -> {
            0x26
        }

        KeyCode.ArrowDown -> {
            0x28
        }

        KeyCode.ArrowLeft -> {
            0x25
        }

        KeyCode.ArrowRight -> {
            0x27
        }

        KeyCode.Control -> {
            if (location == KeyLocation.Left) {
                0xA2
            } else if (location == KeyLocation.Right) {
                0xA3
            } else {
                0x11
            }
        }

        KeyCode.Alt -> {
            if (location == KeyLocation.Left) {
                0xA4
            } else if (location == KeyLocation.Right) {
                0xA5
            } else {
                0x12
            }
        }

        KeyCode.Shift -> {
            if (location == KeyLocation.Left) {
                0xA0
            } else if (location == KeyLocation.Right) {
                0xA1
            } else {
                0x10
            }
        }

        KeyCode.Meta -> {
            if (location == KeyLocation.Right) 0x5C else 0x5B
        }

        KeyCode.Character -> {
            throw DesktopInputException(AppError.Validation("Use TextInput for character injection"))
        }
    }

private fun KeyCode.isExtended(location: KeyLocation = KeyLocation.Standard): Boolean =
    (this in setOf(KeyCode.ArrowUp, KeyCode.ArrowDown, KeyCode.ArrowLeft, KeyCode.ArrowRight, KeyCode.Meta)) ||
        ((this == KeyCode.Control || this == KeyCode.Alt) && location == KeyLocation.Right)

private const val MOUSEEVENTF_MOVE = 0x0001
private const val MOUSEEVENTF_LEFTDOWN = 0x0002
private const val MOUSEEVENTF_LEFTUP = 0x0004
private const val MOUSEEVENTF_RIGHTDOWN = 0x0008
private const val MOUSEEVENTF_RIGHTUP = 0x0010
private const val MOUSEEVENTF_MIDDLEDOWN = 0x0020
private const val MOUSEEVENTF_MIDDLEUP = 0x0040
private const val MOUSEEVENTF_XDOWN = 0x0080
private const val MOUSEEVENTF_XUP = 0x0100
private const val MOUSEEVENTF_WHEEL = 0x0800
private const val MOUSEEVENTF_HWHEEL = 0x1000
private const val MOUSEEVENTF_VIRTUALDESK = 0x4000
private const val MOUSEEVENTF_ABSOLUTE = 0x8000
private const val WHEEL_DELTA = 120
private const val XBUTTON1 = 0x0001
private const val XBUTTON2 = 0x0002
private const val SM_XVIRTUALSCREEN = 76
private const val SM_YVIRTUALSCREEN = 77
private const val SM_CXVIRTUALSCREEN = 78
private const val SM_CYVIRTUALSCREEN = 79
private const val MAPVK_VK_TO_VSC_EX = 4
