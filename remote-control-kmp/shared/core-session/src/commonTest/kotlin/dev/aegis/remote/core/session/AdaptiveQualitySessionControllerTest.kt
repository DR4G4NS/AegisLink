package dev.aegis.remote.core.session

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.webrtc.DataChannelClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdaptiveQualitySessionControllerTest {
    @Test
    fun sendsChosenQualityConfigOverDataChannel() =
        runTest {
            val channel = RecordingDataChannelClient()
            val controller =
                AdaptiveQualitySessionController(
                    dataChannelClient = channel,
                    clock = { 1_000L },
                )

            val applied =
                controller.evaluateAndApply(
                    mode = QualityMode.QualityFirst,
                    stats = stats(ConnectionRouteType.TurnRelay),
                    monitorId = MonitorId("primary"),
                )

            assertEquals(QualityMode.RelaySaver, applied?.qualityMode)
            assertEquals(1500, channel.qualityConfigs.single().bitrateKbps)
            assertEquals(MonitorId("primary"), channel.qualityConfigs.single().monitorId)
        }

    @Test
    fun skipsDuplicateQualityConfig() =
        runTest {
            val channel = RecordingDataChannelClient()
            val controller =
                AdaptiveQualitySessionController(
                    dataChannelClient = channel,
                    clock = { 1_000L },
                )

            controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.Lan), null)
            val second = controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.Lan), null)

            assertNull(second)
            assertEquals(1, channel.qualityConfigs.size)
        }

    @Test
    fun respectsMinimumChangeInterval() =
        runTest {
            var now = 1_000L
            val channel = RecordingDataChannelClient()
            val controller =
                AdaptiveQualitySessionController(
                    dataChannelClient = channel,
                    minChangeIntervalMillis = 2_000L,
                    clock = { now },
                )

            controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.Lan), null)
            now = 1_500L
            val tooSoon = controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.TurnRelay), null)
            now = 3_000L
            val afterInterval = controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.TurnRelay), null)

            assertNull(tooSoon)
            assertEquals(QualityMode.RelaySaver, afterInterval?.qualityMode)
            assertEquals(2, channel.qualityConfigs.size)
        }

    @Test
    fun ignoresSingleTransientNetworkSpikeThroughHysteresis() =
        runTest {
            var now = 1_000L
            val channel = RecordingDataChannelClient()
            val controller =
                AdaptiveQualitySessionController(
                    dataChannelClient = channel,
                    minChangeIntervalMillis = 0,
                    hysteresisSamples = 2,
                    clock = { now },
                )
            controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.Lan), null)

            now += 1_000
            assertNull(controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.TurnRelay), null))
            now += 1_000
            assertNull(controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.Lan), null))

            assertEquals(1, channel.qualityConfigs.size)
        }

    @Test
    fun holdsLastConfigWhenStatsAreUnknown() =
        runTest {
            val channel = RecordingDataChannelClient()
            val controller =
                AdaptiveQualitySessionController(
                    dataChannelClient = channel,
                    clock = { 1_000L },
                )
            controller.evaluateAndApply(QualityMode.Balanced, stats(ConnectionRouteType.Lan), null)
            val held =
                controller.evaluateAndApply(
                    QualityMode.Balanced,
                    ConnectionStats(routeType = ConnectionRouteType.Lan),
                    null,
                )

            assertNull(held)
            assertEquals(1, channel.qualityConfigs.size)
        }

    private fun stats(routeType: ConnectionRouteType) =
        ConnectionStats(
            rttMs = if (routeType == ConnectionRouteType.Lan) 20 else 220,
            bitrateKbps = 3000,
            packetLossPercent = if (routeType == ConnectionRouteType.Lan) 0f else 5f,
            framesDropped = 3,
            encodeMs = 8f,
            decodeMs = 7f,
            fps = 30,
            resolution = "1920x1080",
            networkType = "wifi",
            routeType = routeType,
        )
}

private class RecordingDataChannelClient : DataChannelClient {
    val qualityConfigs = mutableListOf<VideoConfig>()

    override suspend fun sendInput(event: RemoteInputEvent) = Unit

    override suspend fun sendClipboardText(text: String) = Unit

    override suspend fun selectMonitor(monitorId: MonitorId) = Unit

    override suspend fun setQuality(config: VideoConfig) {
        qualityConfigs += config
    }

    override suspend fun sendPing(sentAtEpochMillis: Long) = Unit

    override suspend fun sendPong(
        pingSentAtEpochMillis: Long,
        receivedAtEpochMillis: Long,
    ) = Unit

    override suspend fun sendError(
        code: String,
        message: String,
    ) = Unit
}
