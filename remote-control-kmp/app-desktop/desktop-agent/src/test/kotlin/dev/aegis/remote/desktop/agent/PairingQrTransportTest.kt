package dev.aegis.remote.desktop.agent

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingQrTransportTest {
    @Test
    fun `compressed transport preserves the complete signed payload`() {
        val json =
            """{"version":3,"pairingUrls":["https://192.168.1.92:48291"],"signature":"${"signature".repeat(80)}"}"""

        val encoded = PairingQrTransport.encode(json)

        assertTrue(encoded.startsWith("AEGIS3:"))
        assertTrue(encoded.length < json.length)
        assertEquals(json, inflate(encoded.removePrefix("AEGIS3:")))
    }

    private fun inflate(encoded: String): String {
        val inflater = Inflater(true)
        val output = ByteArrayOutputStream()
        return try {
            inflater.setInput(Base64.getUrlDecoder().decode(encoded))
            val buffer = ByteArray(256)
            while (!inflater.finished()) {
                output.write(buffer, 0, inflater.inflate(buffer))
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }
}
