package dev.aegis.remote.protocol

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JsonProtocolMessageChannelTest {
    @Test
    fun encodesProtocolMessagesToTextTransport() =
        runTest {
            val transport = RecordingProtocolTextTransport()
            val channel = JsonProtocolMessageChannel(transport)

            channel.send(
                ProtocolMessage.Input(
                    sessionId = SessionId("session-1"),
                    event = RemoteInputEvent.TextInput("hello"),
                ),
            )

            val decoded = ProtocolMessageJsonCodec().decode(transport.sent.single())
            val input = assertIs<ProtocolMessage.Input>(decoded)
            assertEquals(SessionId("session-1"), input.sessionId)
            assertEquals("hello", assertIs<RemoteInputEvent.TextInput>(input.event).text)
        }

    @Test
    fun decodesIncomingTextTransportPayloads() =
        runTest {
            val transport = RecordingProtocolTextTransport()
            val channel = JsonProtocolMessageChannel(transport)
            val original =
                ProtocolMessage.Control(
                    sessionId = SessionId("session-1"),
                    command = ControlCommand.Ping(1_000L),
                )

            transport.incomingMessages.emit(ProtocolMessageJsonCodec().encode(original))

            val received = assertIs<ProtocolMessage.Control>(channel.incoming.first())
            assertEquals(SessionId("session-1"), received.sessionId)
            assertEquals(1_000L, assertIs<ControlCommand.Ping>(received.command).sentAtEpochMillis)
        }

    @Test
    fun ignoresOversizedIncomingTextTransportPayloads() =
        runTest {
            val transport = RecordingProtocolTextTransport()
            val channel = JsonProtocolMessageChannel(transport)

            transport.incomingMessages.emit("x".repeat(MAX_PROTOCOL_TEXT_PAYLOAD_BYTES + 1))

            val incoming = withTimeoutOrNull(50) { channel.incoming.first() }
            assertEquals(null, incoming)
        }

    @Test
    fun rejectsOversizedOutgoingTextTransportPayloads() =
        runTest {
            val transport = RecordingProtocolTextTransport()
            val channel = JsonProtocolMessageChannel(transport)

            assertFailsWith<IllegalArgumentException> {
                channel.send(
                    ProtocolMessage.Input(
                        sessionId = SessionId("session-1"),
                        event = RemoteInputEvent.TextInput("x".repeat(MAX_PROTOCOL_TEXT_PAYLOAD_BYTES)),
                    ),
                )
            }

            assertEquals(emptyList(), transport.sent)
        }

    @Test
    fun closeClosesUnderlyingTextTransport() =
        runTest {
            val transport = RecordingProtocolTextTransport()
            val channel = JsonProtocolMessageChannel(transport)

            channel.close()

            assertEquals(true, transport.closed)
        }
}

private class RecordingProtocolTextTransport : ProtocolTextTransport {
    val sent = mutableListOf<String>()
    val incomingMessages = MutableSharedFlow<String>(replay = 8)
    var closed = false

    override val incomingText: Flow<String> = incomingMessages

    override suspend fun sendText(payload: String) {
        sent += payload
    }

    override suspend fun close() {
        closed = true
    }
}
