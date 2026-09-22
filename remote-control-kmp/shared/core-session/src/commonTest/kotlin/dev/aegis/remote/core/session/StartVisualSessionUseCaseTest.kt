package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionState
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.core.webrtc.SignalingClient
import dev.aegis.remote.core.webrtc.SignalingMessage
import dev.aegis.remote.core.webrtc.VideoSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StartVisualSessionUseCaseTest {
    @Test
    fun connectsSignalingStartsVideoAndEmitsStreamingState() =
        runTest {
            val videoSession = FakeRemoteVideoSession()
            val signaling = FakeSignalingClient()
            val useCase = StartVisualSessionUseCase()

            val collected =
                async {
                    useCase(videoSession, signaling, videoConfig(), StunTurnConfig())
                        .take(2)
                        .toList()
                }
            advanceUntilIdle()
            videoSession.stateEvents.emit(VideoSessionState.Streaming)

            assertEquals(listOf(SessionState.NegotiatingWebRtc, SessionState.Streaming), collected.await())
            assertTrue(signaling.connected)
            assertEquals(videoConfig(), videoSession.startedConfig)
            assertEquals(StunTurnConfig(), videoSession.startedIce)
        }

    @Test
    fun emitsTypedFailureWhenSignalingCannotConnect() =
        runTest {
            val useCase = StartVisualSessionUseCase()
            val states =
                useCase(
                    session = FakeRemoteVideoSession(),
                    signalingClient = FakeSignalingClient(connectFailure = IllegalStateException("relay not approved")),
                    config = videoConfig(),
                    ice = StunTurnConfig(),
                ).toList()

            assertEquals(SessionState.NegotiatingWebRtc, states.first())
            val failed = assertIs<SessionState.Failed>(states.last())
            assertEquals("relay not approved", failed.error.message)
        }

    @Test
    fun stopClosesVideoAndSignaling() =
        runTest {
            val videoSession = FakeRemoteVideoSession()
            val signaling = FakeSignalingClient()

            StopVisualSessionUseCase()(videoSession, signaling)

            assertTrue(videoSession.stopped)
            assertTrue(signaling.closed)
        }

    private fun videoConfig() =
        VideoConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrateKbps = 2500,
            monitorId = MonitorId("primary"),
            routeType = ConnectionRouteType.ReverseRelay,
        )
}

private class FakeRemoteVideoSession : RemoteVideoSession {
    val stateEvents = MutableSharedFlow<VideoSessionState>(replay = 1)
    var startedConfig: VideoConfig? = null
    var startedIce: StunTurnConfig? = null
    var stopped = false

    override val states: Flow<VideoSessionState> = stateEvents
    override val stats: Flow<ConnectionStats> = emptyFlow()

    override suspend fun start(
        config: VideoConfig,
        ice: StunTurnConfig,
    ) {
        startedConfig = config
        startedIce = ice
    }

    override suspend fun selectMonitor(monitorId: String) = Unit

    override suspend fun setQuality(config: VideoConfig) = Unit

    override suspend fun stop() {
        stopped = true
    }
}

private class FakeSignalingClient(
    private val connectFailure: Throwable? = null,
) : SignalingClient {
    var connected = false
    var closed = false

    override suspend fun connect() {
        connectFailure?.let { throw it }
        connected = true
    }

    override suspend fun send(message: SignalingMessage) = Unit

    override fun incoming(): Flow<SignalingMessage> = emptyFlow()

    override suspend fun close() {
        closed = true
    }
}
