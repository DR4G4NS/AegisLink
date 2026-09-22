package dev.aegis.remote.core.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelayBackedSignalingClientTest {
    @Test
    fun connectOpensRelaySignalingChannelOnce() =
        runTest {
            val relay = FakeRelayClient()
            val client = RelayBackedSignalingClient(relay, SessionId("session-1"))

            client.connect()
            client.connect()

            assertEquals(listOf(SessionId("session-1")), relay.openedSessions)
        }

    @Test
    fun sendsMessagesThroughRelayChannel() =
        runTest {
            val relay = FakeRelayClient()
            val client = RelayBackedSignalingClient(relay, SessionId("session-1"))
            val offer = SignalingMessage.Offer(SessionId("session-1"), "v=0\r\n")

            client.send(offer)

            assertEquals(listOf<SignalingMessage>(offer), relay.channel.sent)
            assertEquals(listOf(SessionId("session-1")), relay.openedSessions)
        }

    @Test
    fun rejectsMessagesForAnotherSession() =
        runTest {
            val client = RelayBackedSignalingClient(FakeRelayClient(), SessionId("session-1"))

            assertFailsWith<IllegalArgumentException> {
                client.send(SignalingMessage.Answer(SessionId("session-2"), "v=0\r\n"))
            }
        }

    @Test
    fun exposesIncomingMessagesFromRelayChannel() =
        runTest {
            val relay = FakeRelayClient()
            val client = RelayBackedSignalingClient(relay, SessionId("session-1"))
            val answer = SignalingMessage.Answer(SessionId("session-1"), "v=0\r\n")

            val received = async { client.incoming().first() }
            relay.channel.incomingFlow.emit(answer)

            assertEquals(answer, received.await())
        }

    @Test
    fun closeClosesRelayChannel() =
        runTest {
            val relay = FakeRelayClient()
            val client = RelayBackedSignalingClient(relay, SessionId("session-1"))

            client.connect()
            client.close()

            assertTrue(relay.channel.closed)
        }
}

private class FakeRelayClient : RelayClient {
    val channel = FakeRelaySignalingChannel()
    val openedSessions = mutableListOf<SessionId>()
    override val states: Flow<RelayConnectionState> = MutableStateFlow(RelayConnectionState.Disconnected)

    override suspend fun connect() = Unit

    override suspend fun registerDevice(registration: RelayDeviceRegistration): RelayRegistration = RelayRegistration(RelayDeviceId("relay-1"), RelayAuthToken("token-1", 1_000L))

    override suspend fun createSession(targetRelayDeviceId: RelayDeviceId): RelaySession = RelaySession(SessionId("session-1"), targetRelayDeviceId, 1_000L)

    override suspend fun approveSession(
        sessionId: SessionId,
        approved: Boolean,
    ): RelaySessionApproval =
        RelaySessionApproval(
            sessionId = sessionId,
            sourceRelayDeviceId = RelayDeviceId("android-1"),
            targetRelayDeviceId = RelayDeviceId("pc-1"),
            approved = approved,
            decidedAtEpochMillis = 1_000L,
        )

    override suspend fun requestTurnCredentials(): RelayTurnCredentials =
        RelayTurnCredentials(
            urls = listOf("turn:turn.example.test:3478"),
            username = "1600:relay-1",
            credential = "temporary-secret",
            expiresAtEpochMillis = 1_600_000L,
        )

    override suspend fun openSignalingChannel(sessionId: SessionId): RelaySignalingChannel {
        openedSessions += sessionId
        return channel
    }

    override suspend fun openDeviceEvents(): RelayDeviceEventChannel {
        error("Device events are not used in this test")
    }

    override suspend fun close() = Unit
}

private class FakeRelaySignalingChannel : RelaySignalingChannel {
    val incomingFlow = MutableSharedFlow<SignalingMessage>(replay = 1)
    val sent = mutableListOf<SignalingMessage>()
    var closed = false

    override val incoming: Flow<SignalingMessage> = incomingFlow

    override suspend fun send(message: SignalingMessage) {
        sent += message
    }

    override suspend fun close() {
        closed = true
    }
}
