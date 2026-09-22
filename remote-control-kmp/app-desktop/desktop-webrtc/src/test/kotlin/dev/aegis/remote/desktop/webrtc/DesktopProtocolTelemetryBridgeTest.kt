package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.clipboard.ClipboardBridge
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopProtocolTelemetryBridgeTest {
    @Test
    fun publishesMonitorSnapshotToProtocolChannel() =
        runTest {
            val channel = RecordingProtocolMessageChannel()
            val bridge = bridge(channel = channel, scope = this)

            bridge.publishMonitorSnapshot()

            val message = assertIs<ProtocolMessage.Monitors>(channel.sent.single())
            assertEquals("session-1", message.sessionId.value)
            assertEquals(listOf(MonitorId("primary"), MonitorId("secondary")), message.monitors.map { it.id })
        }

    @Test
    fun forwardsVideoStatsToProtocolChannelUntilStopped() =
        runTest {
            val video = FakeRemoteVideoSession()
            val channel = RecordingProtocolMessageChannel()
            val bridge = bridge(videoSession = video, channel = channel, scope = this)

            bridge.startStatsForwarding()
            runCurrent()
            video.statsFlow.emit(stats(fps = 30))
            runCurrent()
            bridge.stopStatsForwarding()
            video.statsFlow.emit(stats(fps = 15))
            runCurrent()

            val message = assertIs<ProtocolMessage.Stats>(channel.sent.single())
            assertEquals("session-1", message.sessionId.value)
            assertEquals(30, message.stats.fps)
        }

    @Test
    fun forwardsClipboardChangesToAuthorizedProtocolChannel() =
        runTest {
            val clipboard = FakeClipboardBridge()
            val channel = RecordingProtocolMessageChannel()
            val bridge = bridge(channel = channel, scope = this, clipboardBridge = clipboard)

            bridge.startClipboardForwarding()
            runCurrent()
            clipboard.changesFlow.emit(ClipboardPayload.Text("desktop text"))
            runCurrent()
            bridge.stopClipboardForwarding()

            val message = assertIs<ProtocolMessage.Input>(channel.sent.single())
            assertEquals(RemoteInputEvent.ClipboardSync("desktop text"), message.event)
        }

    @Test
    fun blocksSensitiveClipboardChangesBeforeForwarding() =
        runTest {
            val clipboard = FakeClipboardBridge()
            val channel = RecordingProtocolMessageChannel()
            val bridge = bridge(channel = channel, scope = this, clipboardBridge = clipboard)

            bridge.startClipboardForwarding()
            runCurrent()
            clipboard.changesFlow.emit(ClipboardPayload.Text("access_token=secret"))
            clipboard.changesFlow.emit(ClipboardPayload.Text(""))
            runCurrent()
            bridge.stopClipboardForwarding()

            assertEquals(emptyList(), channel.sent)
        }

    @Test
    fun suppressesNativeEchoOfRemoteClipboardWrite() =
        runTest {
            val clipboard = FakeClipboardBridge(suppressChanges = true)
            val channel = RecordingProtocolMessageChannel()
            val bridge = bridge(channel = channel, scope = this, clipboardBridge = clipboard)

            bridge.startClipboardForwarding()
            runCurrent()
            clipboard.changesFlow.emit(ClipboardPayload.Text("remote echo"))
            runCurrent()
            bridge.stopClipboardForwarding()

            assertEquals(emptyList(), channel.sent)
        }

    @Test
    fun usesLatestProvidedProtocolChannelForTelemetry() =
        runTest {
            val relayChannel = RecordingProtocolMessageChannel()
            val dataChannel = RecordingProtocolMessageChannel()
            var activeChannel: ProtocolMessageChannel = relayChannel
            val video = FakeRemoteVideoSession()
            val bridge =
                DesktopProtocolTelemetryBridge(
                    sessionId = SessionId("session-1"),
                    monitorProvider = TelemetryFakeMonitorProvider(),
                    videoSession = video,
                    channel = relayChannel,
                    scope = this,
                    channelProvider = { activeChannel },
                )

            bridge.publishMonitorSnapshot()
            activeChannel = dataChannel
            bridge.startStatsForwarding()
            runCurrent()
            video.statsFlow.emit(stats(fps = 60))
            runCurrent()
            bridge.stopStatsForwarding()

            assertIs<ProtocolMessage.Monitors>(relayChannel.sent.single())
            val stats = assertIs<ProtocolMessage.Stats>(dataChannel.sent.single())
            assertEquals(60, stats.stats.fps)
        }

    private fun bridge(
        videoSession: FakeRemoteVideoSession = FakeRemoteVideoSession(),
        channel: RecordingProtocolMessageChannel = RecordingProtocolMessageChannel(),
        clipboardBridge: ClipboardBridge? = null,
        scope: TestScope,
    ): DesktopProtocolTelemetryBridge =
        DesktopProtocolTelemetryBridge(
            sessionId = SessionId("session-1"),
            monitorProvider = TelemetryFakeMonitorProvider(),
            videoSession = videoSession,
            channel = channel,
            scope = scope,
            clipboardBridge = clipboardBridge,
        )

    private fun stats(fps: Int) =
        ConnectionStats(
            rttMs = 30,
            bitrateKbps = 2500,
            packetLossPercent = 0f,
            framesDropped = 0,
            encodeMs = 1f,
            decodeMs = 1f,
            fps = fps,
            resolution = "1280x720",
            networkType = "desktop-local",
            routeType = ConnectionRouteType.Lan,
        )
}

private class FakeClipboardBridge(
    private val suppressChanges: Boolean = false,
) : ClipboardBridge {
    val changesFlow = MutableSharedFlow<ClipboardPayload>()
    override val changes: Flow<ClipboardPayload> = changesFlow

    override suspend fun read(): ClipboardPayload? = null

    override suspend fun write(payload: ClipboardPayload) = Unit

    override fun shouldForwardChange(payload: ClipboardPayload): Boolean = !suppressChanges
}

private class TelemetryFakeMonitorProvider : MonitorProvider {
    override suspend fun listMonitors(): List<MonitorInfo> =
        listOf(
            MonitorInfo(MonitorId("primary"), "Primary", width = 1280, height = 720, primary = true),
            MonitorInfo(MonitorId("secondary"), "Secondary", width = 1024, height = 768, originX = 1280),
        )
}

private class FakeRemoteVideoSession : RemoteVideoSession {
    val statsFlow = MutableSharedFlow<ConnectionStats>()

    override val states: Flow<VideoSessionState> = emptyFlow()
    override val stats: Flow<ConnectionStats> = statsFlow

    override suspend fun start(
        config: VideoConfig,
        ice: StunTurnConfig,
    ) = Unit

    override suspend fun selectMonitor(monitorId: String) = Unit

    override suspend fun setQuality(config: VideoConfig) = Unit

    override suspend fun stop() = Unit
}

private class RecordingProtocolMessageChannel : ProtocolMessageChannel {
    val sent = mutableListOf<ProtocolMessage>()

    override val incoming: Flow<ProtocolMessage> = emptyFlow()

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() = Unit
}
