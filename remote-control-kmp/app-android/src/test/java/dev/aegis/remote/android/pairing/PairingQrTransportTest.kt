package dev.aegis.remote.android.pairing

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairingQrTransportTest {
    @Test
    fun `decoder accepts compressed and legacy json transports`() {
        val json = """{"version":3,"pairingCode":"123456"}"""

        assertEquals(json, PairingQrTransport.decode(compress(json)))
        assertEquals(json, PairingQrTransport.decode(json))
    }

    @Test
    fun `decoder rejects corrupt compressed transports`() {
        assertFailsWith<IllegalArgumentException> { PairingQrTransport.decode("AEGIS3:not-valid-deflate") }
    }

    private fun compress(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        val output = ByteArrayOutputStream()
        return try {
            deflater.setInput(json.toByteArray())
            deflater.finish()
            val buffer = ByteArray(256)
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer))
            }
            "AEGIS3:" + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        } finally {
            deflater.end()
        }
    }
}
