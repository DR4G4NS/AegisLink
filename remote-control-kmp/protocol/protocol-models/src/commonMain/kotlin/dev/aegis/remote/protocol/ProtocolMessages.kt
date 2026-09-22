package dev.aegis.remote.protocol

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.serialization.Serializable

@Serializable
sealed interface ProtocolMessage {
    val sessionId: SessionId

    @Serializable
    data class Signaling(
        override val sessionId: SessionId,
        val message: SignalingMessage,
    ) : ProtocolMessage

    @Serializable
    data class Input(
        override val sessionId: SessionId,
        val event: RemoteInputEvent,
    ) : ProtocolMessage

    @Serializable
    data class Stats(
        override val sessionId: SessionId,
        val stats: ConnectionStats,
    ) : ProtocolMessage

    @Serializable
    data class Monitors(
        override val sessionId: SessionId,
        val monitors: List<MonitorInfo>,
    ) : ProtocolMessage

    @Serializable
    data class Control(
        override val sessionId: SessionId,
        val command: ControlCommand,
    ) : ProtocolMessage
}

@Serializable
sealed interface ControlCommand {
    @Serializable
    data class StartVisualSession(
        val videoConfig: VideoConfig,
        val iceConfig: StunTurnConfig,
    ) : ControlCommand

    @Serializable
    data object StopVisualSession : ControlCommand

    @Serializable
    data class SelectMonitor(
        val monitorId: MonitorId,
    ) : ControlCommand

    @Serializable
    data class SetQuality(
        val videoConfig: VideoConfig,
    ) : ControlCommand

    @Serializable
    data class Ping(
        val sentAtEpochMillis: Long,
    ) : ControlCommand

    @Serializable
    data class Pong(
        val pingSentAtEpochMillis: Long,
        val receivedAtEpochMillis: Long,
    ) : ControlCommand

    @Serializable
    data class Error(
        val code: String,
        val message: String,
        val failure: AegisFailure? = null,
    ) : ControlCommand

    @Serializable
    data class SessionCapabilities(
        val protocolRev: Int,
        val features: List<String>,
    ) : ControlCommand
}
