package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopProtocolSignalingClientTest {
    @Test
    fun preservesAnOfferReceivedBeforeTheWebRtcCollectorSubscribes() =
        runTest {
            val client = DesktopProtocolSignalingClient(SessionId("session-1"), RecordingSignalingChannel())
            val offer = SignalingMessage.Offer(SessionId("session-1"), "early-offer-sdp")

            client.receive(offer)

            assertEquals(offer, client.incoming().first())
        }

    @Test
    fun relaysOnlyTheActiveSessionAndWrapsOutgoingMessages() =
        runTest {
            val channel = RecordingSignalingChannel()
            val client = DesktopProtocolSignalingClient(SessionId("session-1"), channel)
            val received = async { client.incoming().first() }
            runCurrent()
            val offer = SignalingMessage.Offer(SessionId("session-1"), "offer-sdp")

            client.receive(SignalingMessage.Offer(SessionId("other"), "ignored"))
            client.receive(offer)
            client.send(SignalingMessage.Answer(SessionId("session-1"), "answer-sdp"))

            assertEquals(offer, received.await())
            assertEquals(
                ProtocolMessage.Signaling(SessionId("session-1"), SignalingMessage.Answer(SessionId("session-1"), "answer-sdp")),
                channel.sent.single(),
            )
        }
}

private class RecordingSignalingChannel : ProtocolMessageChannel {
    val sent = mutableListOf<ProtocolMessage>()
    override val incoming: Flow<ProtocolMessage> = emptyFlow()

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() = Unit
}
