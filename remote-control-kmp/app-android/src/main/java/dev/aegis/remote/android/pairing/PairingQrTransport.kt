package dev.aegis.remote.android.pairing

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal object PairingQrTransport {
    private const val PREFIX = "AEGIS3:"
    private const val MAX_JSON_BYTES = 64 * 1_024

    fun decode(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX)) return trimmed
        val compressed =
            runCatching { Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX)) }
                .getOrElse { throw IllegalArgumentException("QRP-7103: Pairing QR transport is malformed", it) }
        val inflater = Inflater(true)
        val output = ByteArrayOutputStream(compressed.size * 2)
        return try {
            inflater.setInput(compressed)
            val buffer = ByteArray(1_024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) {
                    throw IllegalArgumentException("QRP-7103: Pairing QR transport is truncated")
                }
                if (count == 0 && inflater.needsDictionary()) {
                    throw IllegalArgumentException("QRP-7103: Pairing QR transport requires an unsupported dictionary")
                }
                if (output.size() + count > MAX_JSON_BYTES) {
                    throw IllegalArgumentException("QRP-7103: Pairing QR transport is too large")
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } catch (error: DataFormatException) {
            throw IllegalArgumentException("QRP-7103: Pairing QR transport is corrupt", error)
        } finally {
            inflater.end()
            output.close()
        }
    }
}
