package dev.aegis.remote.protocol

import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.IceCandidateModel
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProtocolSignalingClientTest {
    @Test
    fun sendsSignalingEnvelopeForActiveSession() =
        runTest {
            val channel = RecordingProtocolSignalingChannel()
            val sessionId = SessionId("session-1")
            val client = ProtocolSignalingClient(sessionId, channel)
            val offer = SignalingMessage.Offer(sessionId, "v=0")

            client.send(offer)

            val message = assertIs<ProtocolMessage.Signaling>(channel.sent.single())
            assertEquals(sessionId, message.sessionId)
            assertEquals(offer, message.message)
        }

    @Test
    fun rejectsOutgoingSignalingForAnotherSession() =
        runTest {
            val client =
                ProtocolSignalingClient(
                    sessionId = SessionId("session-1"),
                    channel = RecordingProtocolSignalingChannel(),
                )

            assertFailsWith<IllegalArgumentException> {
                client.send(SignalingMessage.Answer(SessionId("session-2"), "v=0"))
            }
        }

    @Test
    fun receivesOnlySignalingForActiveSession() =
        runTest {
            val channel = RecordingProtocolSignalingChannel()
            val sessionId = SessionId("session-1")
            val client = ProtocolSignalingClient(sessionId, channel)
            val candidate =
                SignalingMessage.IceCandidate(
                    sessionId = sessionId,
                    candidate =
                        IceCandidateModel(
                            candidate = "candidate:1 1 udp 2122260223 192.0.2.10 54400 typ host",
                            sdpMid = "0",
                            sdpMLineIndex = 0,
                        ),
                )

            val received = async { client.incoming().first() }
            channel.incomingMessages.emit(ProtocolMessage.Control(sessionId, ControlCommand.Ping(1_000L)))
            channel.incomingMessages.emit(
                ProtocolMessage.Signaling(SessionId("session-2"), SignalingMessage.Offer(SessionId("session-2"), "ignored")),
            )
            channel.incomingMessages.emit(ProtocolMessage.Signaling(sessionId, candidate))

            assertEquals(candidate, received.await())
        }

    @Test
    fun closeClosesUnderlyingChannel() =
        runTest {
            val channel = RecordingProtocolSignalingChannel()
            val client = ProtocolSignalingClient(SessionId("session-1"), channel)

            client.close()

            assertEquals(true, channel.closed)
        }
}

private class RecordingProtocolSignalingChannel : ProtocolMessageChannel {
    val sent = mutableListOf<ProtocolMessage>()
    val incomingMessages = MutableSharedFlow<ProtocolMessage>(replay = 8)
    var closed = false

    override val incoming: Flow<ProtocolMessage> = incomingMessages

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() {
        closed = true
    }
}
