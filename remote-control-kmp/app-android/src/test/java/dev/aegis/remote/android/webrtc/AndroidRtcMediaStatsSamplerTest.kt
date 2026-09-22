package dev.aegis.remote.android.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidRtcMediaStatsSamplerTest {
    @Test
    fun `derives only measurements reported by inbound video stats`() {
        val sampler = AndroidRtcMediaStatsSampler()
        sampler.sample(entries(bytesReceived = 1_000, framesDecoded = 10), 1_000)

        val stats = sampler.sample(entries(bytesReceived = 251_000, framesDecoded = 40), 2_000)!!

        assertEquals(2_000, stats.bitrateKbps)
        assertEquals(251_000L, stats.bytesReceived)
        assertEquals(975L, stats.packetsReceived)
        assertEquals(25L, stats.packetsLost)
        assertEquals(2.5f, stats.packetLossPercent)
        assertEquals(4f, stats.jitterMs)
        assertEquals(40L, stats.framesDecoded)
        assertEquals(3, stats.framesDropped)
        assertEquals(25f, stats.decodeMs)
        assertEquals(2, stats.freezeCount)
        assertEquals(500f, stats.totalFreezeDurationMs)
        assertEquals(29, stats.fps)
        assertEquals("1280x720", stats.resolution)
    }

    @Test
    fun `first sample reports bytes but not a synthetic bitrate target or zero`() {
        val stats =
            AndroidRtcMediaStatsSampler().sample(
                listOf(
                    AndroidRtcStatsEntry(
                        id = "inbound-video",
                        type = "inbound-rtp",
                        members = mapOf("kind" to "video", "bytesReceived" to 100_000L),
                    ),
                ),
                nowMillis = 1_000,
            )

        assertEquals(100_000L, stats?.bytesReceived)
        assertNull(stats?.bitrateKbps)
    }

    @Test
    fun `ignores audio and does not invent video measurements`() {
        val stats =
            AndroidRtcMediaStatsSampler().sample(
                listOf(
                    AndroidRtcStatsEntry(
                        id = "inbound-audio",
                        type = "inbound-rtp",
                        members = mapOf("kind" to "audio", "bytesReceived" to 100_000L),
                    ),
                ),
                nowMillis = 1_000,
            )

        assertNull(stats)
    }

    private fun entries(
        bytesReceived: Long,
        framesDecoded: Long,
    ): List<AndroidRtcStatsEntry> =
        listOf(
            AndroidRtcStatsEntry(
                id = "inbound-video",
                type = "inbound-rtp",
                members =
                    mapOf(
                        "kind" to "video",
                        "bytesReceived" to bytesReceived,
                        "packetsLost" to 25L,
                        "packetsReceived" to 975L,
                        "jitter" to 0.004,
                        "framesDecoded" to framesDecoded,
                        "framesDropped" to 3L,
                        "totalDecodeTime" to 1.0,
                        "freezeCount" to 2L,
                        "totalFreezesDuration" to 0.5,
                        "framesPerSecond" to 29.8,
                        "frameWidth" to 1280,
                        "frameHeight" to 720,
                    ),
            ),
        )
}
