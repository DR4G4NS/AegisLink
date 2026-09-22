package dev.aegis.remote.core.wol

class MacAddressValidator {
    private val pattern = Regex("""^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$|^[0-9A-Fa-f]{12}$""")

    fun isValid(value: String): Boolean = pattern.matches(value.trim())

    fun toBytes(value: String): ByteArray {
        require(isValid(value)) { "Invalid MAC address" }
        val compact = value.replace(":", "").replace("-", "")
        return ByteArray(6) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

class MagicPacketBuilder(
    private val validator: MacAddressValidator = MacAddressValidator(),
) {
    fun build(macAddress: String): ByteArray {
        val macBytes = validator.toBytes(macAddress)
        return ByteArray(102) { index ->
            if (index < 6) {
                0xFF.toByte()
            } else {
                macBytes[(index - 6) % macBytes.size]
            }
        }
    }
}
