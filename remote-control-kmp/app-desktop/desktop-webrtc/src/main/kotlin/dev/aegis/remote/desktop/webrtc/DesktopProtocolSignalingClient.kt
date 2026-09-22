package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.SignalingClient
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Demultiplexes signaling from the single relay WebSocket reader owned by
 * [DesktopProtocolChannelBridge]. A Ktor WebSocket cannot safely have two
 * consumers, so WebRTC never collects the relay channel directly.
 */
class DesktopProtocolSignalingClient(
    private val sessionId: SessionId,
    private val channel: ProtocolMessageChannel,
) : SignalingClient {
    // The protocol bridge can receive the first offer immediately after StartVisualSession,
    // before the native WebRTC collector gets a chance to subscribe. A SharedFlow with no
    // replay silently drops that offer. This single-consumer queue preserves early signaling
    // and candidate bursts until the WebRTC session is ready to consume them.
    private val messages = Channel<SignalingMessage>(capacity = Channel.UNLIMITED)

    override suspend fun connect() = Unit

    override suspend fun send(message: SignalingMessage) {
        require(message.sessionId == sessionId) { "Signaling session mismatch" }
        channel.send(ProtocolMessage.Signaling(sessionId, message))
    }

    override fun incoming(): Flow<SignalingMessage> = messages.receiveAsFlow()

    override suspend fun close() {
        messages.close()
    }

    fun receive(message: SignalingMessage) {
        if (message.sessionId == sessionId) {
            messages.trySend(message)
        }
    }
}
