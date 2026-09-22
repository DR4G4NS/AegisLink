package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.quality.AdaptiveQualityController
import dev.aegis.remote.core.webrtc.DataChannelClient

class AdaptiveQualitySessionController(
    private val qualityController: AdaptiveQualityController = AdaptiveQualityController(),
    private val dataChannelClient: DataChannelClient,
    private val minChangeIntervalMillis: Long = 2_000L,
    private val hysteresisSamples: Int = 2,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var lastAppliedConfig: VideoConfig? = null
    private var lastAppliedAtEpochMillis: Long = Long.MIN_VALUE
    private var pendingConfig: VideoConfig? = null
    private var pendingSamples: Int = 0

    init {
        require(minChangeIntervalMillis >= 0)
        require(hysteresisSamples > 0)
    }

    suspend fun evaluateAndApply(
        mode: QualityMode,
        stats: ConnectionStats,
        monitorId: MonitorId?,
    ): VideoConfig? {
        if (!stats.hasMeasuredCongestion()) {
            return null
        }
        val nextConfig = qualityController.choose(mode, stats, monitorId)
        if (nextConfig == lastAppliedConfig) {
            pendingConfig = null
            pendingSamples = 0
            return null
        }

        if (lastAppliedConfig != null) {
            if (pendingConfig == nextConfig) {
                pendingSamples += 1
            } else {
                pendingConfig = nextConfig
                pendingSamples = 1
            }
            if (pendingSamples < hysteresisSamples) return null
        }

        val now = clock()
        if (lastAppliedConfig != null && now - lastAppliedAtEpochMillis < minChangeIntervalMillis) {
            return null
        }

        dataChannelClient.setQuality(nextConfig)
        lastAppliedConfig = nextConfig
        lastAppliedAtEpochMillis = now
        pendingConfig = null
        pendingSamples = 0
        return nextConfig
    }
}
