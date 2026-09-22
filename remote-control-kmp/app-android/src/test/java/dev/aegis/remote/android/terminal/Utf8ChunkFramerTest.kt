package dev.aegis.remote.android.terminal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Utf8ChunkFramerTest {
    @Test
    fun `holds an incomplete code point until the next SSH chunk`() {
        val bytes = "Aegis ✓".encodeToByteArray()
        val framer = Utf8ChunkFramer()

        val first = framer.frame(bytes, bytes.size - 1)
        val second = framer.frame(byteArrayOf(bytes.last()))

        assertEquals("Aegis ", first?.decodeToString())
        assertEquals("✓", second?.decodeToString())
        assertEquals(0, framer.pendingByteCount)
    }

    @Test
    fun `flushes an incomplete suffix unchanged at EOF`() {
        val framer = Utf8ChunkFramer()
        val incomplete = byteArrayOf(0xE2.toByte(), 0x9C.toByte())

        assertNull(framer.frame(incomplete))
        assertContentEquals(incomplete, framer.finish())
        assertEquals(0, framer.pendingByteCount)
    }
}
