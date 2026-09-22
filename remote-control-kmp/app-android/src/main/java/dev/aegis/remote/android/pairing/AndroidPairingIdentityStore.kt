package dev.aegis.remote.android.pairing

import android.content.Context
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class AndroidPairingIdentity(
    val displayName: String,
    val fingerprint: String,
)

class AndroidPairingIdentityStore(
    context: Context,
    private val random: SecureRandom = SecureRandom(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(): AndroidPairingIdentity {
        val seed =
            preferences
                .getString(IDENTITY_SEED_KEY, null)
                ?.let { value -> runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() }
                ?.takeIf { it.size == IDENTITY_SEED_BYTES }
                ?: ByteArray(IDENTITY_SEED_BYTES).also { generated ->
                    random.nextBytes(generated)
                    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(generated)
                    check(preferences.edit().putString(IDENTITY_SEED_KEY, encoded).commit()) {
                        "Could not persist the local pairing identity"
                    }
                }
        return AndroidPairingIdentity(
            displayName = pairingDeviceDisplayName(Build.MANUFACTURER, Build.MODEL),
            fingerprint = stablePairingFingerprint(seed),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "local_pairing_identity"
        const val IDENTITY_SEED_KEY = "identity_seed_v1"
        const val IDENTITY_SEED_BYTES = 32
    }
}

internal fun stablePairingFingerprint(seed: ByteArray): String {
    require(seed.size >= 16) { "Pairing identity seed is too short" }
    val digest = MessageDigest.getInstance("SHA-256").digest(seed)
    return "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun pairingDeviceDisplayName(
    manufacturer: String?,
    model: String?,
): String {
    val maker = manufacturer.orEmpty().trim()
    val deviceModel = model.orEmpty().trim()
    return when {
        deviceModel.isBlank() -> "Android device"
        maker.isBlank() || deviceModel.startsWith(maker, ignoreCase = true) -> deviceModel
        else -> "$maker $deviceModel"
    }.take(80)
}
