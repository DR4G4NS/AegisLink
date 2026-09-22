package dev.aegis.remote.desktop.agent

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

internal object PairingQrTransport {
    private const val PREFIX = "AEGIS3:"

    fun encode(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        val output = ByteArrayOutputStream(json.length)
        return try {
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val buffer = ByteArray(1_024)
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer))
            }
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
        } finally {
            deflater.end()
            output.close()
        }
    }
}
