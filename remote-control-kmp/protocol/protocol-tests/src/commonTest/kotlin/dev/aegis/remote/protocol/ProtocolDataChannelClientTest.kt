package dev.aegis.remote.protocol

import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolDataChannelClientTest {
    @Test
    fun sendsInputEnvelopeForRemoteInputEvents() =
        runTest {
            val channel = RecordingProtocolMessageChannel()
            val client = ProtocolDataChannelClient(SessionId("session-1"), channel)

            client.sendInput(RemoteInputEvent.Key(KeyCode.Enter, pressed = true))

            val message = assertIs<ProtocolMessage.Input>(channel.sent.single())
            val event = assertIs<RemoteInputEvent.Key>(message.event)
            assertEquals("session-1", message.sessionId.value)
            assertEquals(KeyCode.Enter, event.code)
            assertEquals(true, event.pressed)
        }

    @Test
    fun mapsClipboardTextToClipboardSyncInputEvent() =
        runTest {
            val channel = RecordingProtocolMessageChannel()
            val client = ProtocolDataChannelClient(SessionId("session-1"), channel)

            client.sendClipboardText("hello")

            val message = assertIs<ProtocolMessage.Input>(channel.sent.single())
            val event = assertIs<RemoteInputEvent.ClipboardSync>(message.event)
            assertEquals("hello", event.text)
        }

    @Test
    fun sendsControlEnvelopesForMonitorQualityAndHealthMessages() =
        runTest {
            val channel = RecordingProtocolMessageChannel()
            val client = ProtocolDataChannelClient(SessionId("session-1"), channel)
            val config = videoConfig()

            client.startVisualSession(config, StunTurnConfig(stunUrls = listOf("stun:stun.example.test")))
            client.selectMonitor(MonitorId("secondary"))
            client.setQuality(config)
            client.sendPing(1000)
            client.sendPong(pingSentAtEpochMillis = 1000, receivedAtEpochMillis = 1200)
            client.sendError("webrtc_failed", "ICE failed")
            client.stopVisualSession()

            val start = assertControl<ControlCommand.StartVisualSession>(channel.sent[0])
            assertEquals(config, start.videoConfig)
            assertEquals(listOf("stun:stun.example.test"), start.iceConfig.stunUrls)
            assertEquals(MonitorId("secondary"), assertControl<ControlCommand.SelectMonitor>(channel.sent[1]).monitorId)
            assertEquals(config, assertControl<ControlCommand.SetQuality>(channel.sent[2]).videoConfig)
            assertEquals(1000, assertControl<ControlCommand.Ping>(channel.sent[3]).sentAtEpochMillis)
            assertEquals(1200, assertControl<ControlCommand.Pong>(channel.sent[4]).receivedAtEpochMillis)
            assertEquals("webrtc_failed", assertControl<ControlCommand.Error>(channel.sent[5]).code)
            assertIs<ControlCommand.StopVisualSession>(assertIs<ProtocolMessage.Control>(channel.sent[6]).command)
        }

    @Test
    fun sendsSessionCapabilityHandshake() =
        runTest {
            val channel = RecordingProtocolMessageChannel()
            val client = ProtocolDataChannelClient(SessionId("session-1"), channel)
            client.sendSessionCapabilities(
                dev.aegis.remote.core.model
                    .localSessionProtocolCapabilities(),
            )
            val command = assertControl<ControlCommand.SessionCapabilities>(channel.sent.single())
            assertEquals(1, command.protocolRev)
            assertEquals(true, command.features.contains("ping"))
        }

    @Test
    fun jsonCodecRoundTripsDataChannelProtocolMessages() {
        val codec = ProtocolMessageJsonCodec()
        val original: ProtocolMessage =
            ProtocolMessage.Input(
                sessionId = SessionId("session-1"),
                event = RemoteInputEvent.TextInput("abc"),
            )

        val decoded = codec.decode(codec.encode(original))

        val input = assertIs<ProtocolMessage.Input>(decoded)
        assertEquals("session-1", input.sessionId.value)
        assertEquals("abc", assertIs<RemoteInputEvent.TextInput>(input.event).text)
    }

    private inline fun <reified T : ControlCommand> assertControl(message: ProtocolMessage): T = assertIs<T>(assertIs<ProtocolMessage.Control>(message).command)

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

private class RecordingProtocolMessageChannel : ProtocolMessageChannel {
    val sent = mutableListOf<ProtocolMessage>()
    var closed = false

    override val incoming: Flow<ProtocolMessage> = emptyFlow()

    override suspend fun send(message: ProtocolMessage) {
        sent += message
    }

    override suspend fun close() {
        closed = true
    }
}
