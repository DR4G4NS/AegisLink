package dev.aegis.remote.desktop.webrtc

import dev.onvoid.webrtc.RTCStatsType
import kotlin.math.max

internal data class DesktopRtcMediaStats(
    val bitrateKbps: Int,
    val packetLossPercent: Float,
    val framesDropped: Int,
    val encodeMs: Float,
    val fps: Int,
    val resolution: String,
)

internal class DesktopRtcMediaStatsSampler {
    private var previousBytesSent: Long? = null
    private var previousAtMillis: Long? = null

    fun sample(
        entries: Collection<DesktopRtcStatsEntry>,
        nowMillis: Long,
    ): DesktopRtcMediaStats? {
        val outbound =
            entries.firstOrNull { entry ->
                entry.type == RTCStatsType.OUTBOUND_RTP && entry.attributes["kind"]?.toString()?.lowercase() != "audio"
            } ?: return null
        val remoteInbound = entries.firstOrNull { it.type == RTCStatsType.REMOTE_INBOUND_RTP }
        val bytesSent = outbound.attributes.long("bytesSent") ?: 0L
        val bitrate = computeBitrate(bytesSent, nowMillis)
        val framesEncoded = outbound.attributes.long("framesEncoded") ?: 0L
        val totalEncodeSeconds = outbound.attributes.number("totalEncodeTime") ?: 0.0
        val encodeMs = if (framesEncoded > 0) (totalEncodeSeconds * 1_000.0 / framesEncoded).toFloat() else 0f
        val packetsLost = max(0L, remoteInbound?.attributes?.long("packetsLost") ?: 0L)
        val packetsReceived = max(0L, remoteInbound?.attributes?.long("packetsReceived") ?: 0L)
        val packetTotal = packetsLost + packetsReceived
        val loss = if (packetTotal == 0L) 0f else packetsLost * 100f / packetTotal
        val width = outbound.attributes.long("frameWidth")?.toInt() ?: 0
        val height = outbound.attributes.long("frameHeight")?.toInt() ?: 0
        return DesktopRtcMediaStats(
            bitrateKbps = bitrate,
            packetLossPercent = loss,
            framesDropped = (outbound.attributes.long("framesDropped") ?: 0L).toInt(),
            encodeMs = encodeMs,
            fps = (outbound.attributes.number("framesPerSecond") ?: 0.0).toInt(),
            resolution = if (width > 0 && height > 0) "${width}x$height" else "unknown",
        )
    }

    private fun computeBitrate(
        bytesSent: Long,
        nowMillis: Long,
    ): Int {
        val previousBytes = previousBytesSent
        val previousAt = previousAtMillis
        previousBytesSent = bytesSent
        previousAtMillis = nowMillis
        if (previousBytes == null || previousAt == null || nowMillis <= previousAt || bytesSent < previousBytes) return 0
        return (((bytesSent - previousBytes) * 8.0) / (nowMillis - previousAt)).toInt()
    }
}

private fun Map<String, Any?>.number(key: String): Double? = (this[key] as? Number)?.toDouble()

private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
