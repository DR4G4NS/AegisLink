package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
data class MonitorInfo(
    val id: MonitorId,
    val name: String,
    val width: Int,
    val height: Int,
    val scaleFactor: Float = 1f,
    val originX: Int = 0,
    val originY: Int = 0,
    val primary: Boolean = false,
)

@Serializable
enum class QualityMode {
    LowLatency,
    Balanced,
    QualityFirst,
    BatterySaver,
    RelaySaver,
}

@Serializable
data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val codecPreference: String? = null,
    val qualityMode: QualityMode = QualityMode.Balanced,
    val monitorId: MonitorId? = null,
    val routeType: ConnectionRouteType,
)

@Serializable
data class ConnectionStats(
    val rttMs: Long? = null,
    val bitrateKbps: Int? = null,
    val packetLossPercent: Float? = null,
    val framesDropped: Int? = null,
    val encodeMs: Float? = null,
    val decodeMs: Float? = null,
    val fps: Int? = null,
    val resolution: String? = null,
    val networkType: String? = null,
    val routeType: ConnectionRouteType,
    val localCandidateType: String? = null,
    val remoteCandidateType: String? = null,
    val transportProtocol: String? = null,
    val turnTransport: String? = null,
    val availableOutgoingBitrateKbps: Int? = null,
) {
    /** Congestion control may run only when RTT, loss, and a measured bitrate exist. */
    fun hasMeasuredCongestion(): Boolean =
        rttMs != null &&
            packetLossPercent != null &&
            (bitrateKbps != null || availableOutgoingBitrateKbps != null)
}
