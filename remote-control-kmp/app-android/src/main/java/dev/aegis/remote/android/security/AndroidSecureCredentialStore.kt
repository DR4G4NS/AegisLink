package dev.aegis.remote.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.aegis.remote.core.model.SshCredentialsRef
import dev.aegis.remote.core.security.SecureCredentialStore
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureCredentialStore(
    context: Context,
) : SecureCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override suspend fun putSecret(
        label: String,
        secret: ByteArray,
    ): SshCredentialsRef {
        val ref = SshCredentialsRef("android-keystore:${sanitizeLabel(label)}:${UUID.randomUUID()}")
        val encrypted = encrypt(secret)
        preferences.edit().putString(ref.value, encrypted).apply()
        return ref
    }

    override suspend fun getSecret(ref: SshCredentialsRef): ByteArray? {
        val encrypted = preferences.getString(ref.value, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (error: Throwable) {
            if (!isCredentialKeyInvalidation(error)) throw error
            preferences.edit().clear().commit()
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            throw CredentialStoreInvalidatedException(error)
        }
    }

    override suspend fun deleteSecret(ref: SshCredentialsRef) {
        preferences.edit().remove(ref.value).apply()
    }

    private fun encrypt(secret: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(secret)
        val iv = cipher.iv
        return "${iv.base64()}:${cipherText.base64()}"
    }

    private fun decrypt(value: String): ByteArray? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val iv = parts[0].fromBase64()
        val cipherText = parts[1].fromBase64()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        generator.init(spec, SecureRandom())
        return generator.generateKey()
    }

    private fun sanitizeLabel(label: String): String =
        label
            .lowercase()
            .replace(Regex("""[^a-z0-9_.-]"""), "-")
            .take(48)
            .ifBlank { "secret" }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "aegis_remote_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

class CredentialStoreInvalidatedException(
    cause: Throwable,
) : IllegalStateException("Android credential key was invalidated; SSH/TURN credentials must be entered again", cause)

internal fun isCredentialKeyInvalidation(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is KeyPermanentlyInvalidatedException || current is UnrecoverableKeyException) return true
        current = current.cause
    }
    return false
}
