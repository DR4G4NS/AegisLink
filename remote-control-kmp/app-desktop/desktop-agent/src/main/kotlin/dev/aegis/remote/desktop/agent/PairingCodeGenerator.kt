package dev.aegis.remote.desktop.agent

import java.security.SecureRandom

class PairingCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val value = random.nextInt(1_000_000)
        return value.toString().padStart(6, '0')
    }
}
