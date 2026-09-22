package dev.aegis.remote.core.security

import java.security.Provider
import java.security.Security

/**
 * AndroidKeyStore's KeyFactory/Signature cannot import an X.509 key that was
 * generated on another device. Pairing and session crypto must use a software
 * provider (Conscrypt, AndroidOpenSSL, SunEC, BouncyCastle).
 */
object JcaSoftwareProviders {
    fun required(
        service: String,
        algorithm: String,
    ): Provider =
        Security
            .getProviders()
            .mapNotNull { provider -> provider.getService(service, algorithm)?.let { implementation -> provider to implementation } }
            .filterNot { (provider, implementation) ->
                provider.name.contains("KeyStore", ignoreCase = true) ||
                    implementation.className.contains("KeyStore", ignoreCase = true)
            }.minByOrNull { (provider, _) -> preference(provider) }
            ?.first
            ?: error("NO_NON_KEYSTORE_${service.uppercase()}_${algorithm.uppercase()}_PROVIDER")

    private fun preference(provider: Provider): Int =
        when {
            provider.name.contains("Conscrypt", ignoreCase = true) -> 0
            provider.name.contains("OpenSSL", ignoreCase = true) -> 1
            provider.name.equals("SunEC", ignoreCase = true) -> 2
            provider.name.contains("BC", ignoreCase = true) || provider.name.contains("BouncyCastle", ignoreCase = true) -> 3
            else -> 4
        }
}
