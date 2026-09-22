package dev.aegis.remote.protocol

import dev.aegis.remote.core.input.RemoteInputEvent

/**
 * Stable labels and delivery semantics for the WebRTC protocol channels.
 *
 * Every message is accepted only on its declared channel. This fail-closed
 * routing prevents reliable clipboard/keyboard traffic from silently falling
 * back to a channel with different lifecycle or backpressure semantics.
 */
enum class ProtocolDataChannelKind(
    val label: String,
    val ordered: Boolean,
    val maxRetransmits: Int?,
) {
    Control(
        label = "aegis-control",
        ordered = true,
        maxRetransmits = null,
    ),
    Pointer(
        label = "aegis-pointer",
        ordered = false,
        maxRetransmits = 0,
    ),
    Keyboard(
        label = "aegis-keyboard",
        ordered = true,
        maxRetransmits = null,
    ),
    Clipboard(
        label = "aegis-clipboard",
        ordered = true,
        maxRetransmits = null,
    ),
    ;

    fun accepts(message: ProtocolMessage): Boolean = message.preferredDataChannelKind() == this

    /** Validates the SCTP properties exposed by a native DataChannel binding. */
    fun matchesNegotiatedProperties(
        ordered: Boolean,
        maxRetransmits: Int,
    ): Boolean =
        this.ordered == ordered &&
            (maxRetransmits == (this.maxRetransmits ?: SCTP_RELIABLE_MAX_RETRANSMITS))

    companion object {
        fun fromLabel(label: String): ProtocolDataChannelKind? = entries.firstOrNull { it.label == label }
    }
}

private const val SCTP_RELIABLE_MAX_RETRANSMITS = -1

/**
 * The E2EE bootstrap channel may only establish WebRTC or exchange signaling.
 * Input, clipboard, and all post-bootstrap control traffic must use their
 * dedicated DataChannel.
 */
fun ProtocolMessage.isBaseProtocolMessageAllowed(): Boolean =
    this is ProtocolMessage.Signaling ||
        (this is ProtocolMessage.Control && command is ControlCommand.StartVisualSession)

fun ProtocolMessage.preferredDataChannelKind(): ProtocolDataChannelKind =
    when (this) {
        is ProtocolMessage.Input -> event.preferredDataChannelKind()

        is ProtocolMessage.Control,
        is ProtocolMessage.Monitors,
        is ProtocolMessage.Signaling,
        is ProtocolMessage.Stats,
        -> ProtocolDataChannelKind.Control
    }

private fun RemoteInputEvent.preferredDataChannelKind(): ProtocolDataChannelKind =
    when (this) {
        is RemoteInputEvent.MouseMove,
        is RemoteInputEvent.MouseMoveRelative,
        is RemoteInputEvent.Scroll,
        -> ProtocolDataChannelKind.Pointer

        is RemoteInputEvent.Key,
        is RemoteInputEvent.Shortcut,
        is RemoteInputEvent.TextInput,
        -> ProtocolDataChannelKind.Keyboard

        is RemoteInputEvent.ClipboardSync -> ProtocolDataChannelKind.Clipboard

        is RemoteInputEvent.MouseButton,
        is RemoteInputEvent.SelectMonitor,
        is RemoteInputEvent.SetQuality,
        -> ProtocolDataChannelKind.Control
    }
