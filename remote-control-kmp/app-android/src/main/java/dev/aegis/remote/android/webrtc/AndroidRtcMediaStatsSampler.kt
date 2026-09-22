package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.model.ConnectionRouteType
import kotlin.math.max

/** A nullable field means libwebrtc did not report that measurement. */
internal data class AndroidRtcLiveStats(
    val rttMs: Long? = null,
    val bitrateKbps: Int? = null,
    val bytesReceived: Long? = null,
    val packetsReceived: Long? = null,
    val packetsLost: Long? = null,
    val packetLossPercent: Float? = null,
    val jitterMs: Float? = null,
    val framesDecoded: Long? = null,
    val framesDropped: Int? = null,
    val decodeMs: Float? = null,
    val freezeCount: Int? = null,
    val totalFreezeDurationMs: Float? = null,
    val fps: Int? = null,
    val resolution: String? = null,
    val networkType: String? = null,
    val routeType: ConnectionRouteType? = null,
    val localCandidateType: String? = null,
    val remoteCandidateType: String? = null,
    val transportProtocol: String? = null,
    val turnTransport: String? = null,
)

internal data class AndroidRtcMediaStats(
    val bitrateKbps: Int? = null,
    val bytesReceived: Long? = null,
    val packetsReceived: Long? = null,
    val packetsLost: Long? = null,
    val packetLossPercent: Float? = null,
    val jitterMs: Float? = null,
    val framesDecoded: Long? = null,
    val framesDropped: Int? = null,
    val decodeMs: Float? = null,
    val freezeCount: Int? = null,
    val totalFreezeDurationMs: Float? = null,
    val fps: Int? = null,
    val resolution: String? = null,
)

internal class AndroidRtcMediaStatsSampler {
    private var previousBytesReceived: Long? = null
    private var previousFramesDecoded: Long? = null
    private var previousAtMillis: Long? = null

    @Suppress("CyclomaticComplexMethod")
    fun sample(
        entries: Collection<AndroidRtcStatsEntry>,
        nowMillis: Long,
    ): AndroidRtcMediaStats? {
        val inbound =
            entries.firstOrNull { entry ->
                entry.type == "inbound-rtp" &&
                    (entry.members["kind"] ?: entry.members["mediaType"])
                        ?.toString()
                        ?.equals("video", ignoreCase = true) == true
            } ?: return null

        val bytesReceived = inbound.members.nonNegativeLong("bytesReceived")
        val framesDecoded = inbound.members.nonNegativeLong("framesDecoded")
        val previousAt = previousAtMillis
        val bitrateKbps = ratePerSecond(previousBytesReceived, bytesReceived, previousAt, nowMillis, scale = 8.0 / 1_000.0)
        val derivedFps = ratePerSecond(previousFramesDecoded, framesDecoded, previousAt, nowMillis, scale = 1.0)

        if (bytesReceived != null || framesDecoded != null) {
            previousBytesReceived = bytesReceived
            previousFramesDecoded = framesDecoded
            previousAtMillis = nowMillis
        }

        val packetsLost = inbound.members.nonNegativeLong("packetsLost")
        val packetsReceived = inbound.members.nonNegativeLong("packetsReceived")
        val packetLossPercent =
            if (packetsLost != null && packetsReceived != null && packetsLost + packetsReceived > 0L) {
                packetsLost * 100f / (packetsLost + packetsReceived)
            } else {
                null
            }
        val totalDecodeTimeSeconds = inbound.members.number("totalDecodeTime")?.takeIf { it >= 0.0 }
        val decodeMs =
            if (totalDecodeTimeSeconds != null && framesDecoded != null && framesDecoded > 0L) {
                (totalDecodeTimeSeconds * 1_000.0 / framesDecoded).toFloat()
            } else {
                null
            }
        val width = inbound.members.positiveInt("frameWidth")
        val height = inbound.members.positiveInt("frameHeight")
        val nativeFps =
            inbound.members
                .number("framesPerSecond")
                ?.takeIf { it >= 0.0 }
                ?.toInt()
        val stats =
            AndroidRtcMediaStats(
                bitrateKbps = bitrateKbps,
                bytesReceived = bytesReceived,
                packetsReceived = packetsReceived,
                packetsLost = packetsLost,
                packetLossPercent = packetLossPercent,
                jitterMs =
                    inbound.members
                        .number("jitter")
                        ?.takeIf { it >= 0.0 }
                        ?.times(1_000.0)
                        ?.toFloat(),
                framesDecoded = framesDecoded,
                framesDropped = inbound.members.nonNegativeInt("framesDropped"),
                decodeMs = decodeMs,
                freezeCount = inbound.members.nonNegativeInt("freezeCount"),
                totalFreezeDurationMs =
                    inbound.members
                        .number("totalFreezesDuration")
                        ?.takeIf { it >= 0.0 }
                        ?.times(1_000.0)
                        ?.toFloat(),
                fps = nativeFps ?: derivedFps,
                resolution = if (width != null && height != null) "${width}x$height" else null,
            )
        return stats.takeIf {
            it.bitrateKbps != null ||
                it.bytesReceived != null ||
                it.packetsReceived != null ||
                it.packetsLost != null ||
                it.packetLossPercent != null ||
                it.jitterMs != null ||
                it.framesDecoded != null ||
                it.framesDropped != null ||
                it.decodeMs != null ||
                it.freezeCount != null ||
                it.totalFreezeDurationMs != null ||
                it.fps != null ||
                it.resolution != null
        }
    }
}

private fun ratePerSecond(
    previous: Long?,
    current: Long?,
    previousAtMillis: Long?,
    nowMillis: Long,
    scale: Double,
): Int? {
    if (previous == null || current == null || previousAtMillis == null) return null
    if (current < previous || nowMillis <= previousAtMillis) return null
    val perSecond = (current - previous) * 1_000.0 / (nowMillis - previousAtMillis) * scale
    return perSecond.coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
}

private fun Map<String, Any?>.number(key: String): Double? = (this[key] as? Number)?.toDouble()

private fun Map<String, Any?>.nonNegativeLong(key: String): Long? =
    (this[key] as? Number)
        ?.toLong()
        ?.let { max(0L, it) }

private fun Map<String, Any?>.nonNegativeInt(key: String): Int? = nonNegativeLong(key)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

private fun Map<String, Any?>.positiveInt(key: String): Int? =
    (this[key] as? Number)
        ?.toLong()
        ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
        ?.toInt()
