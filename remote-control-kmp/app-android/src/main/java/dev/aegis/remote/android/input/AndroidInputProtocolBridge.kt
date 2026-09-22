package dev.aegis.remote.android.input

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolMessageJsonCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class AndroidInputProtocolBridge(
    private val codec: ProtocolMessageJsonCodec = ProtocolMessageJsonCodec(),
) {
    suspend fun buildInputPayload(
        sessionId: SessionId,
        event: RemoteInputEvent,
    ): String {
        val channel = CapturingProtocolMessageChannel()
        ProtocolDataChannelClient(sessionId, channel).sendInput(event)
        val message =
            channel.sent.singleOrNull()
                ?: error("Remote input must emit exactly one protocol message")
        return codec.encode(message)
    }
}

private class CapturingProtocolMessageChannel : ProtocolMessageChannel {
    val sent = mutableListOf<ProtocolMessage>()

    override val incoming: Flow<ProtocolMessage> = emptyFlow()

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() = Unit
}
