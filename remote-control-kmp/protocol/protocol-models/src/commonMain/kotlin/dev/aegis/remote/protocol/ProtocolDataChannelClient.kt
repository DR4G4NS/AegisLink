package dev.aegis.remote.protocol

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.webrtc.DataChannelClient
import dev.aegis.remote.core.webrtc.SignalingClient
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MAX_PROTOCOL_TEXT_PAYLOAD_BYTES: Int = 64 * 1024

fun String.isProtocolTextPayloadWithinLimit(): Boolean = encodeToByteArray().size <= MAX_PROTOCOL_TEXT_PAYLOAD_BYTES

interface ProtocolMessageChannel {
    val incoming: Flow<ProtocolMessage>

    suspend fun send(message: ProtocolMessage)

    suspend fun close()
}

interface ProtocolTextTransport {
    val incomingText: Flow<String>

    suspend fun sendText(payload: String)

    suspend fun close()
}

class JsonProtocolMessageChannel(
    private val transport: ProtocolTextTransport,
    private val codec: ProtocolMessageJsonCodec = ProtocolMessageJsonCodec(),
) : ProtocolMessageChannel {
    override val incoming: Flow<ProtocolMessage> =
        transport.incomingText.mapNotNull { payload ->
            if (payload.isProtocolTextPayloadWithinLimit()) {
                codec.decode(payload)
            } else {
                null
            }
        }

    override suspend fun send(message: ProtocolMessage) {
        val payload = codec.encode(message)
        require(payload.isProtocolTextPayloadWithinLimit()) {
            "Protocol text payload exceeds $MAX_PROTOCOL_TEXT_PAYLOAD_BYTES bytes"
        }
        transport.sendText(payload)
    }

    override suspend fun close() {
        transport.close()
    }
}

class ProtocolDataChannelClient(
    private val sessionId: SessionId,
    private val channel: ProtocolMessageChannel,
) : DataChannelClient {
    override suspend fun sendInput(event: RemoteInputEvent) {
        channel.send(ProtocolMessage.Input(sessionId, event))
    }

    override suspend fun sendClipboardText(text: String) {
        sendInput(RemoteInputEvent.ClipboardSync(text))
    }

    suspend fun startVisualSession(
        config: VideoConfig,
        ice: StunTurnConfig,
    ) {
        sendControl(ControlCommand.StartVisualSession(config, ice))
    }

    suspend fun stopVisualSession() {
        sendControl(ControlCommand.StopVisualSession)
    }

    override suspend fun selectMonitor(monitorId: MonitorId) {
        sendControl(ControlCommand.SelectMonitor(monitorId))
    }

    override suspend fun setQuality(config: VideoConfig) {
        sendControl(ControlCommand.SetQuality(config))
    }

    override suspend fun sendPing(sentAtEpochMillis: Long) {
        sendControl(ControlCommand.Ping(sentAtEpochMillis))
    }

    override suspend fun sendPong(
        pingSentAtEpochMillis: Long,
        receivedAtEpochMillis: Long,
    ) {
        sendControl(ControlCommand.Pong(pingSentAtEpochMillis, receivedAtEpochMillis))
    }

    suspend fun sendSessionCapabilities(capabilities: dev.aegis.remote.core.model.SessionProtocolCapabilities) {
        sendControl(
            ControlCommand.SessionCapabilities(
                protocolRev = capabilities.protocolRev,
                features = capabilities.features,
            ),
        )
    }

    override suspend fun sendError(
        code: String,
        message: String,
    ) {
        sendControl(ControlCommand.Error(code, message))
    }

    private suspend fun sendControl(command: ControlCommand) {
        channel.send(ProtocolMessage.Control(sessionId, command))
    }
}

class ProtocolSignalingClient(
    private val sessionId: SessionId,
    private val channel: ProtocolMessageChannel,
) : SignalingClient {
    override suspend fun connect() = Unit

    override suspend fun send(message: SignalingMessage) {
        require(message.sessionId == sessionId) {
            "Signaling message session ${message.sessionId.value} does not match protocol session ${sessionId.value}"
        }
        channel.send(ProtocolMessage.Signaling(sessionId, message))
    }

    override fun incoming(): Flow<SignalingMessage> =
        channel.incoming
            .filter { message -> message is ProtocolMessage.Signaling && message.sessionId == sessionId }
            .map { message -> (message as ProtocolMessage.Signaling).message }

    override suspend fun close() {
        channel.close()
    }
}

open class ProtocolMessageJsonCodec(
    private val json: Json = protocolJson,
) {
    open fun encode(message: ProtocolMessage): String = json.encodeToString(ProtocolMessage.serializer(), message)

    open fun decode(payload: String): ProtocolMessage = json.decodeFromString(ProtocolMessage.serializer(), payload)
}

val protocolJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
