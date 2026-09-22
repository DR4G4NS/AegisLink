package dev.aegis.remote.core.webrtc

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface RemoteVideoSession {
    val states: Flow<VideoSessionState>
    val stats: Flow<ConnectionStats>

    suspend fun start(
        config: VideoConfig,
        ice: StunTurnConfig,
    )

    suspend fun selectMonitor(monitorId: String)

    suspend fun setQuality(config: VideoConfig)

    suspend fun stop()
}

interface SignalingClient {
    suspend fun connect()

    suspend fun send(message: SignalingMessage)

    fun incoming(): Flow<SignalingMessage>

    suspend fun close()
}

interface DataChannelClient {
    suspend fun sendInput(event: RemoteInputEvent)

    suspend fun sendClipboardText(text: String)

    suspend fun selectMonitor(monitorId: MonitorId)

    suspend fun setQuality(config: VideoConfig)

    suspend fun sendPing(sentAtEpochMillis: Long)

    suspend fun sendPong(
        pingSentAtEpochMillis: Long,
        receivedAtEpochMillis: Long,
    )

    suspend fun sendError(
        code: String,
        message: String,
    )
}

interface VideoRenderer {
    suspend fun attach(session: RemoteVideoSession)

    suspend fun detach()
}

@Serializable
sealed interface VideoSessionState {
    @Serializable data object Idle : VideoSessionState

    @Serializable data object Negotiating : VideoSessionState

    @Serializable data object Streaming : VideoSessionState

    @Serializable data object Reconnecting : VideoSessionState

    @Serializable data class Failed(
        val reason: String,
    ) : VideoSessionState
}

@Serializable
sealed interface SignalingMessage {
    val sessionId: SessionId

    @Serializable
    data class Offer(
        override val sessionId: SessionId,
        val sdp: String,
    ) : SignalingMessage

    @Serializable
    data class Answer(
        override val sessionId: SessionId,
        val sdp: String,
    ) : SignalingMessage

    @Serializable
    data class IceCandidate(
        override val sessionId: SessionId,
        val candidate: IceCandidateModel,
    ) : SignalingMessage
}

@Serializable
data class IceCandidateModel(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
)
