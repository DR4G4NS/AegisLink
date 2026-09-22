package dev.aegis.remote.core.quality

import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.QualityMode
import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveQualityControllerTest {
    @Test
    fun supportsEveryRequestedQualityModeOnDirectRoutes() {
        val controller = AdaptiveQualityController()

        QualityMode.entries.forEach { mode ->
            val config = controller.choose(mode, stats(ConnectionRouteType.Lan), null)
            assertEquals(mode, config.qualityMode)
        }
    }

    @Test
    fun batterySaverUsesLowerFrameRateAndBitrateThanQualityFirst() {
        val controller = AdaptiveQualityController()
        val directStats = stats(ConnectionRouteType.Lan).copy(rttMs = 30, packetLossPercent = 0f, bitrateKbps = 20_000)

        val battery = controller.choose(QualityMode.BatterySaver, directStats, null)
        val quality = controller.choose(QualityMode.QualityFirst, directStats, null)

        assertEquals(24, battery.fps)
        assertEquals(2_000, battery.bitrateKbps)
        assertEquals(60, quality.fps)
        assertEquals(14_000, quality.bitrateKbps)
    }

    @Test
    fun usesRelaySaverForTurnRoutesRegardlessOfRequestedMode() {
        val config =
            AdaptiveQualityController().choose(
                mode = QualityMode.QualityFirst,
                stats = stats(ConnectionRouteType.TurnRelay),
                monitorId = null,
            )

        assertEquals(QualityMode.RelaySaver, config.qualityMode)
        assertEquals(1500, config.bitrateKbps)
    }

    @Test
    fun unknownStatsAreNotTreatedAsHealthyZeros() {
        val unknown =
            ConnectionStats(
                routeType = ConnectionRouteType.Lan,
            )
        assertEquals(false, unknown.hasMeasuredCongestion())
        assertEquals(null, unknown.rttMs)
        assertEquals(null, unknown.packetLossPercent)
    }

    @Test
    fun dropsResolutionBeforeFrameRateWhenCongested() {
        val config =
            BalancedStrategy().choose(
                stats(ConnectionRouteType.Lan).copy(rttMs = 220, packetLossPercent = 4f, bitrateKbps = 3_000, fps = 60),
                null,
            )
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(60, config.fps)
    }

    @Test
    fun turnMobileCeilingCapsBitrate() {
        val config =
            BalancedStrategy().choose(
                stats(ConnectionRouteType.TurnRelay).copy(rttMs = 80, packetLossPercent = 1f, bitrateKbps = 8_000),
                null,
            )
        assertEquals(1_500, config.bitrateKbps)
    }

    private fun stats(routeType: ConnectionRouteType) =
        ConnectionStats(
            rttMs = 220,
            bitrateKbps = 3000,
            packetLossPercent = 5f,
            framesDropped = 3,
            encodeMs = 8f,
            decodeMs = 7f,
            fps = 30,
            resolution = "1920x1080",
            networkType = "wifi",
            routeType = routeType,
        )
}
