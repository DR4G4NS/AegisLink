package dev.aegis.remote.protocol

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolDataChannelRoutingTest {
    private val sessionId = SessionId("session-1")

    @Test
    fun `declares required SCTP delivery semantics`() {
        assertEquals(true, ProtocolDataChannelKind.Control.ordered)
        assertNull(ProtocolDataChannelKind.Control.maxRetransmits)
        assertEquals(false, ProtocolDataChannelKind.Pointer.ordered)
        assertEquals(0, ProtocolDataChannelKind.Pointer.maxRetransmits)
        assertEquals(true, ProtocolDataChannelKind.Keyboard.ordered)
        assertNull(ProtocolDataChannelKind.Keyboard.maxRetransmits)
        assertEquals(true, ProtocolDataChannelKind.Clipboard.ordered)
        assertNull(ProtocolDataChannelKind.Clipboard.maxRetransmits)
    }

    @Test
    fun `rejects mismatched SCTP channel contracts`() {
        assertFalse(ProtocolDataChannelKind.Pointer.matchesNegotiatedProperties(ordered = true, maxRetransmits = 0))
        assertFalse(ProtocolDataChannelKind.Pointer.matchesNegotiatedProperties(ordered = false, maxRetransmits = -1))
        assertFalse(ProtocolDataChannelKind.Keyboard.matchesNegotiatedProperties(ordered = false, maxRetransmits = -1))
        assertFalse(ProtocolDataChannelKind.Clipboard.matchesNegotiatedProperties(ordered = true, maxRetransmits = 0))
        assertTrue(ProtocolDataChannelKind.Control.matchesNegotiatedProperties(ordered = true, maxRetransmits = -1))
    }

    @Test
    fun `accepts only bootstrap and signaling on the base channel`() {
        assertTrue(
            ProtocolMessage
                .Control(
                    sessionId,
                    ControlCommand.StartVisualSession(
                        videoConfig =
                            VideoConfig(
                                width = 1280,
                                height = 720,
                                fps = 30,
                                bitrateKbps = 1_000,
                                routeType = ConnectionRouteType.Lan,
                            ),
                        iceConfig = StunTurnConfig(),
                    ),
                ).isBaseProtocolMessageAllowed(),
        )
        assertFalse(ProtocolMessage.Control(sessionId, ControlCommand.StopVisualSession).isBaseProtocolMessageAllowed())
        assertFalse(input(RemoteInputEvent.MouseMoveRelative(1, 1)).isBaseProtocolMessageAllowed())
        assertFalse(input(RemoteInputEvent.ClipboardSync("clipboard")).isBaseProtocolMessageAllowed())
    }

    @Test
    fun `routes pointer keyboard clipboard and control independently`() {
        assertRoute(
            ProtocolDataChannelKind.Pointer,
            RemoteInputEvent.MouseMove(10, 20, MonitorId("main")),
            RemoteInputEvent.MouseMoveRelative(2, -3),
            RemoteInputEvent.Scroll(1f, -2f),
        )
        assertRoute(
            ProtocolDataChannelKind.Keyboard,
            RemoteInputEvent.Key(KeyCode.Enter, pressed = true),
            RemoteInputEvent.Shortcut(listOf(KeyCode.Control, KeyCode.Character)),
            RemoteInputEvent.TextInput("hello"),
        )
        assertRoute(ProtocolDataChannelKind.Clipboard, RemoteInputEvent.ClipboardSync("hello"))
        assertRoute(
            ProtocolDataChannelKind.Control,
            RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true),
            RemoteInputEvent.SelectMonitor(MonitorId("secondary")),
        )
    }

    @Test
    fun `all dedicated channels including control fail closed`() {
        val keyboard = input(RemoteInputEvent.TextInput("safe"))
        val pointer = input(RemoteInputEvent.Scroll(0f, 1f))

        assertFalse(ProtocolDataChannelKind.Control.accepts(keyboard))
        assertFalse(ProtocolDataChannelKind.Control.accepts(pointer))
        assertTrue(ProtocolDataChannelKind.Keyboard.accepts(keyboard))
        assertFalse(ProtocolDataChannelKind.Pointer.accepts(keyboard))
        assertFalse(ProtocolDataChannelKind.Clipboard.accepts(pointer))
    }

    @Test
    fun `only recognizes the four reserved labels`() {
        ProtocolDataChannelKind.entries.forEach { kind ->
            assertEquals(kind, ProtocolDataChannelKind.fromLabel(kind.label))
        }
        assertNull(ProtocolDataChannelKind.fromLabel("untrusted-channel"))
    }

    private fun assertRoute(
        expected: ProtocolDataChannelKind,
        vararg events: RemoteInputEvent,
    ) {
        events.forEach { event -> assertEquals(expected, input(event).preferredDataChannelKind()) }
    }

    private fun input(event: RemoteInputEvent): ProtocolMessage = ProtocolMessage.Input(sessionId, event)
}
