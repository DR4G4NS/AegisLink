package dev.aegis.remote.core.relay

import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.SignalingClient
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class RelayBackedSignalingClient(
    private val relayClient: RelayClient,
    private val sessionId: SessionId,
) : SignalingClient {
    private var channel: RelaySignalingChannel? = null

    override suspend fun connect() {
        ensureChannel()
    }

    override suspend fun send(message: SignalingMessage) {
        require(message.sessionId == sessionId) {
            "Signaling message session ${message.sessionId.value} does not match relay session ${sessionId.value}"
        }
        ensureChannel().send(message)
    }

    override fun incoming(): Flow<SignalingMessage> =
        flow {
            emitAll(ensureChannel().incoming)
        }

    override suspend fun close() {
        channel?.close()
        channel = null
    }

    private suspend fun ensureChannel(): RelaySignalingChannel = channel ?: relayClient.openSignalingChannel(sessionId).also { channel = it }
}
