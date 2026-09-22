package dev.aegis.remote.core.input

import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.QualityMode
import kotlinx.serialization.Serializable

@Serializable
sealed interface RemoteInputEvent {
    @Serializable
    data class MouseMove(
        val x: Int,
        val y: Int,
        val monitorId: MonitorId,
    ) : RemoteInputEvent

    /** Relative movement is intentionally separate from virtual-desktop coordinates. */
    @Serializable
    data class MouseMoveRelative(
        val deltaX: Int,
        val deltaY: Int,
    ) : RemoteInputEvent

    @Serializable
    data class MouseButton(
        val button: MouseButtonType,
        val pressed: Boolean,
    ) : RemoteInputEvent

    @Serializable
    data class Scroll(
        /** Positive X scrolls right; positive Y scrolls down on every desktop backend. */
        val deltaX: Float,
        val deltaY: Float,
    ) : RemoteInputEvent

    @Serializable
    data class Key(
        val code: KeyCode,
        val pressed: Boolean,
        /** Windows set-1 scan code when the sender is reporting a physical key. */
        val scanCode: Int? = null,
        /** Null lets the Windows backend derive the extended-key flag safely. */
        val extended: Boolean? = null,
        val location: KeyLocation = KeyLocation.Standard,
        /** Informational source layout. Logical text is always sent with [TextInput]. */
        val sourceLayoutId: String? = null,
    ) : RemoteInputEvent

    @Serializable
    data class TextInput(
        val text: String,
    ) : RemoteInputEvent

    @Serializable
    data class Shortcut(
        val keys: List<KeyCode>,
    ) : RemoteInputEvent

    @Serializable
    data class ClipboardSync(
        val text: String,
    ) : RemoteInputEvent

    @Serializable
    data class SelectMonitor(
        val monitorId: MonitorId,
    ) : RemoteInputEvent

    @Serializable
    data class SetQuality(
        val qualityMode: QualityMode,
    ) : RemoteInputEvent
}

@Serializable
enum class MouseButtonType {
    Left,
    Right,
    Middle,
    Back,
    Forward,
}

@Serializable
enum class KeyCode {
    Enter,
    Escape,
    Backspace,
    Tab,
    Space,
    ArrowUp,
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    Control,
    Alt,
    Shift,
    Meta,
    Character,
}

@Serializable
enum class KeyLocation {
    Standard,
    Left,
    Right,
    Numpad,
}

interface InputSender {
    suspend fun send(event: RemoteInputEvent)
}

interface RemoteInputExecutor {
    suspend fun execute(event: RemoteInputEvent)

    /** Releases every key/button held by this executor. Must be safe to call repeatedly. */
    suspend fun releaseAll() = Unit
}
