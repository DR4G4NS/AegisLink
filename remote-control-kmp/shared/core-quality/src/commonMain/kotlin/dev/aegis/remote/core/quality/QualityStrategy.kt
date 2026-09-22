package dev.aegis.remote.core.quality

import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.VideoConfig
import kotlin.math.min

interface QualityStrategy {
    val mode: QualityMode

    fun choose(
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig
}

private const val TURN_MOBILE_BITRATE_CEILING_KBPS = 1_500
private const val DIRECT_BITRATE_CEILING_KBPS = 14_000

internal fun ConnectionStats.measuredBitrateKbps(): Int? = availableOutgoingBitrateKbps ?: bitrateKbps

internal fun ConnectionStats.lossConstrained(
    rttLimitMs: Long = 180,
    lossLimitPercent: Float = 3f,
): Boolean {
    val rtt = rttMs
    val loss = packetLossPercent
    return (rtt != null && rtt > rttLimitMs) || (loss != null && loss > lossLimitPercent)
}

internal fun ConnectionStats.relayOrMobile(): Boolean =
    routeType == ConnectionRouteType.TurnRelay ||
        routeType == ConnectionRouteType.ReverseRelay ||
        networkType?.contains("cellular", ignoreCase = true) == true ||
        networkType?.contains("mobile", ignoreCase = true) == true

internal fun ConnectionStats.bitrateCeilingKbps(): Int = if (relayOrMobile()) TURN_MOBILE_BITRATE_CEILING_KBPS else DIRECT_BITRATE_CEILING_KBPS

class BalancedStrategy : QualityStrategy {
    override val mode = QualityMode.Balanced

    override fun choose(
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig {
        val constrained = stats.lossConstrained() || stats.relayOrMobile()
        val width = if (constrained) 1280 else 1920
        val height = if (constrained) 720 else 1080
        val heavyLoss = (stats.packetLossPercent ?: 0f) > 8f
        val fps = if (heavyLoss) 30 else 60
        val measured = stats.measuredBitrateKbps()
        val target =
            when {
                stats.relayOrMobile() -> TURN_MOBILE_BITRATE_CEILING_KBPS
                constrained -> 3_500
                else -> 8_000
            }
        val bitrate = min(target, measured ?: target).coerceAtMost(stats.bitrateCeilingKbps())
        return VideoConfig(width, height, fps, bitrate, qualityMode = mode, monitorId = monitorId, routeType = stats.routeType)
    }
}

class LowLatencyStrategy : QualityStrategy {
    override val mode = QualityMode.LowLatency

    override fun choose(
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig {
        val bitrate = min(4_000, stats.measuredBitrateKbps() ?: 4_000).coerceAtMost(stats.bitrateCeilingKbps())
        val constrained = stats.lossConstrained()
        val width = if (constrained) 960 else 1280
        val height = if (constrained) 540 else 720
        return VideoConfig(width, height, 60, bitrate, qualityMode = mode, monitorId = monitorId, routeType = stats.routeType)
    }
}

class QualityFirstStrategy : QualityStrategy {
    override val mode = QualityMode.QualityFirst

    override fun choose(
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig {
        val measured = stats.measuredBitrateKbps()
        val constrained =
            stats.lossConstrained(rttLimitMs = 150, lossLimitPercent = 2f) ||
                (measured != null && measured in 1..7_999)
        val bitrate =
            min(if (constrained) 6_000 else 14_000, measured ?: if (constrained) 6_000 else 14_000)
                .coerceAtMost(stats.bitrateCeilingKbps())
        return if (constrained) {
            VideoConfig(1920, 1080, 60, bitrate, qualityMode = mode, monitorId = monitorId, routeType = stats.routeType)
        } else {
            VideoConfig(2560, 1440, 60, bitrate, qualityMode = mode, monitorId = monitorId, routeType = stats.routeType)
        }
    }
}

class BatterySaverStrategy : QualityStrategy {
    override val mode = QualityMode.BatterySaver

    override fun choose(
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig {
        val constrained = stats.lossConstrained()
        val bitrate =
            min(if (constrained) 1_200 else 2_000, stats.measuredBitrateKbps() ?: if (constrained) 1_200 else 2_000)
                .coerceAtMost(stats.bitrateCeilingKbps())
        return if (constrained) {
            VideoConfig(960, 540, 20, bitrate, qualityMode = mode, monitorId = monitorId, routeType = stats.routeType)
        } else {
            VideoConfig(1280, 720, 24, bitrate, qualityMode = mode, monitorId = monitorId, routeType = stats.routeType)
        }
    }
}

class RelaySaverStrategy : QualityStrategy {
    override val mode = QualityMode.RelaySaver

    override fun choose(
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig {
        val bitrate = min(TURN_MOBILE_BITRATE_CEILING_KBPS, stats.measuredBitrateKbps() ?: TURN_MOBILE_BITRATE_CEILING_KBPS)
        return VideoConfig(960, 540, 30, bitrate, qualityMode = mode, monitorId = monitorId, routeType = stats.routeType)
    }
}

class AdaptiveQualityController(
    private val strategies: List<QualityStrategy> =
        listOf(
            BalancedStrategy(),
            QualityFirstStrategy(),
            BatterySaverStrategy(),
            LowLatencyStrategy(),
            RelaySaverStrategy(),
        ),
) {
    fun choose(
        mode: QualityMode,
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig {
        val effectiveMode =
            if (stats.relayOrMobile()) {
                QualityMode.RelaySaver
            } else {
                mode
            }
        return strategies.first { it.mode == effectiveMode }.choose(stats, monitorId)
    }
}
