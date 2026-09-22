package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.HostKeyFingerprint

interface HostKeyPinningStore {
    suspend fun getPinnedFingerprint(
        host: String,
        port: Int,
    ): HostKeyFingerprint?

    suspend fun pinFingerprint(
        host: String,
        port: Int,
        fingerprint: HostKeyFingerprint,
    )

    suspend fun removeFingerprint(
        host: String,
        port: Int,
    )
}

class HostFingerprintValidator {
    fun matches(
        expected: HostKeyFingerprint,
        actual: HostKeyFingerprint,
    ): Boolean =
        expected.algorithm.equals(actual.algorithm, ignoreCase = true) &&
            normalize(expected.value) == normalize(actual.value)

    fun isValidFingerprint(value: String): Boolean = canonicalOpenSshSha256Fingerprint(value) != null

    private fun normalize(value: String): String = value.trim().replace(" ", "")
}

/**
 * Returns the unpadded Base64 digest used by OpenSSH's `SHA256:` host-key
 * fingerprints. Other algorithms and malformed/padded lengths fail closed.
 */
fun canonicalOpenSshSha256Fingerprint(value: String): String? {
    val trimmed = value.trim()
    val digest =
        if (trimmed.startsWith(OPENSSH_SHA256_PREFIX, ignoreCase = true)) {
            trimmed.substring(OPENSSH_SHA256_PREFIX.length)
        } else {
            trimmed
        }
    val unpadded = digest.removeSuffix("=")
    if (digest.length != OPENSSH_SHA256_BASE64_LENGTH && digest.length != OPENSSH_SHA256_PADDED_BASE64_LENGTH) return null
    if (digest.length == OPENSSH_SHA256_PADDED_BASE64_LENGTH && !digest.endsWith('=')) return null
    if (!unpadded.matches(OPENSSH_SHA256_BASE64_PATTERN)) return null
    return unpadded
}

private const val OPENSSH_SHA256_PREFIX = "SHA256:"
private const val OPENSSH_SHA256_BASE64_LENGTH = 43
private const val OPENSSH_SHA256_PADDED_BASE64_LENGTH = 44
private val OPENSSH_SHA256_BASE64_PATTERN = Regex("""^[A-Za-z0-9+/]{43}$""")
