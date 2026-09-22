package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolMessageJsonCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerializationException

class AndroidVisualProtocolBridge(
    private val codec: ProtocolMessageJsonCodec = ProtocolMessageJsonCodec(),
) {
    suspend fun buildStartVisualSessionPayload(
        sessionId: SessionId,
        videoConfig: VideoConfig,
        iceConfig: StunTurnConfig,
    ): String {
        val channel = CapturingProtocolMessageChannel()
        ProtocolDataChannelClient(sessionId, channel).startVisualSession(videoConfig, iceConfig)
        val message =
            channel.sent.singleOrNull()
                ?: error("StartVisualSession must emit exactly one protocol message")
        return codec.encode(message)
    }

    fun decodeIncomingEvent(
        sessionId: SessionId,
        payload: String,
    ): AndroidVisualProtocolEvent? {
        val message =
            try {
                codec.decode(payload)
            } catch (_: SerializationException) {
                return AndroidVisualProtocolEvent.MalformedPayload
            } catch (_: IllegalArgumentException) {
                return AndroidVisualProtocolEvent.MalformedPayload
            }

        if (message.sessionId != sessionId) return null

        return when (message) {
            is ProtocolMessage.Stats -> {
                AndroidVisualProtocolEvent.StatsUpdated(message.stats)
            }

            is ProtocolMessage.Monitors -> {
                AndroidVisualProtocolEvent.MonitorsUpdated(message.monitors)
            }

            is ProtocolMessage.Control -> {
                when (val command = message.command) {
                    is ControlCommand.Error -> {
                        AndroidVisualProtocolEvent.ControlError(
                            code = command.code,
                            message = command.message,
                        )
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }
    }
}

sealed interface AndroidVisualProtocolEvent {
    data class StatsUpdated(
        val stats: ConnectionStats,
    ) : AndroidVisualProtocolEvent

    data class MonitorsUpdated(
        val monitors: List<MonitorInfo>,
    ) : AndroidVisualProtocolEvent

    data class ControlError(
        val code: String,
        val message: String,
    ) : AndroidVisualProtocolEvent

    data object MalformedPayload : AndroidVisualProtocolEvent
}

private class CapturingProtocolMessageChannel : ProtocolMessageChannel {
    val sent = mutableListOf<ProtocolMessage>()

    override val incoming: Flow<ProtocolMessage> = emptyFlow()

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() = Unit
}
