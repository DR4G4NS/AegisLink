package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.SshCredentialsRef

interface SecureCredentialStore {
    suspend fun putSecret(
        label: String,
        secret: ByteArray,
    ): SshCredentialsRef

    suspend fun getSecret(ref: SshCredentialsRef): ByteArray?

    suspend fun deleteSecret(ref: SshCredentialsRef)
}
