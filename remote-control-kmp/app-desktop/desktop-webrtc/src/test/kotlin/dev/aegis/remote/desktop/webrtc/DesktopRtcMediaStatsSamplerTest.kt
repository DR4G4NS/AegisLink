package dev.aegis.remote.desktop.webrtc

import dev.onvoid.webrtc.RTCStatsType
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopRtcMediaStatsSamplerTest {
    @Test
    fun `derives real bitrate loss encode time frames and resolution from rtc stats`() {
        val sampler = DesktopRtcMediaStatsSampler()
        sampler.sample(entries(bytesSent = 1_000), 1_000)
        val stats = sampler.sample(entries(bytesSent = 251_000), 2_000)!!

        assertEquals(2_000, stats.bitrateKbps)
        assertEquals(2.5f, stats.packetLossPercent)
        assertEquals(3, stats.framesDropped)
        assertEquals(2f, stats.encodeMs)
        assertEquals(29, stats.fps)
        assertEquals("1280x720", stats.resolution)
    }

    @Test
    fun `first sample reports zero bitrate instead of configured target`() {
        val stats = DesktopRtcMediaStatsSampler().sample(entries(100_000), 1_000)!!
        assertEquals(0, stats.bitrateKbps)
    }

    private fun entries(bytesSent: Long) =
        listOf(
            DesktopRtcStatsEntry(
                id = "outbound-video",
                type = RTCStatsType.OUTBOUND_RTP,
                attributes =
                    mapOf(
                        "kind" to "video",
                        "bytesSent" to bytesSent,
                        "framesEncoded" to 500L,
                        "totalEncodeTime" to 1.0,
                        "framesDropped" to 3L,
                        "framesPerSecond" to 29.8,
                        "frameWidth" to 1280,
                        "frameHeight" to 720,
                    ),
            ),
            DesktopRtcStatsEntry(
                id = "remote-inbound-video",
                type = RTCStatsType.REMOTE_INBOUND_RTP,
                attributes = mapOf("packetsLost" to 25L, "packetsReceived" to 975L),
            ),
        )
}
