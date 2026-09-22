package dev.aegis.remote.core.wol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MagicPacketBuilderTest {
    @Test
    fun buildsWakeOnLanMagicPacket() {
        val packet = MagicPacketBuilder().build("00:11:22:33:44:55")

        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        assertContentEquals(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55), packet.sliceArray(6 until 12))
    }
}
