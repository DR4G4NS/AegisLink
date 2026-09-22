package dev.aegis.remote.android.security

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.security.SecureCredentialStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.nio.ByteBuffer

private val sshCredentialJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

@Serializable
data class AndroidSshPrivateKeyCredentials(
    val privateKey: String,
    val passphrase: String? = null,
)

fun encodePrivateKeyCredentials(
    privateKey: String,
    passphrase: String?,
): ByteArray =
    sshCredentialJson
        .encodeToString(
            AndroidSshPrivateKeyCredentials(
                privateKey = privateKey,
                passphrase = passphrase?.takeIf { it.isNotBlank() },
            ),
        ).toByteArray(Charsets.UTF_8)

@Suppress("ThrowsCount")
suspend fun SSHClient.authenticateProfile(
    profile: DeviceProfile,
    credentialStore: SecureCredentialStore,
) {
    if (profile.username.isBlank()) {
        throw remoteOperationFailure(
            code = AegisFailureCodes.SSH_USERNAME_MISSING,
            component = "android-sshj",
            operation = "authenticate-profile",
            stage = "precondition",
            category = FailureCategory.CONFIGURATION,
            summary = "The paired Windows SSH username is missing",
            retryable = false,
            nextAction = "Repair or repeat pairing so Windows can return the provisioned account name.",
        )
    }
    val pairedProfile = profile.pairedHostIdentity != null || profile.localAgentCertificateFingerprint != null
    if (pairedProfile && profile.authMethod != AuthMethod.PrivateKey) {
        throw remoteOperationFailure(
            code = AegisFailureCodes.SSH_AUTHENTICATION_FAILED,
            component = "android-sshj",
            operation = "authenticate-profile",
            stage = "precondition",
            category = FailureCategory.CONFIGURATION,
            summary = "A paired Aegis profile must use its enrolled private key",
            expected = AuthMethod.PrivateKey.name,
            actual = profile.authMethod.name,
            retryable = false,
            nextAction = "Repair or repeat pairing; password fallback is disabled for paired profiles.",
        )
    }
    val credentialRef =
        profile.credentialsRef
            ?: throw remoteOperationFailure(
                code = AegisFailureCodes.SSH_CREDENTIAL_MISSING,
                component = "android-sshj",
                operation = "authenticate-profile",
                stage = "credential-load",
                category = FailureCategory.CONFIGURATION,
                summary = "No SSH credential reference is stored for this profile",
                retryable = false,
                nextAction = "Pair the device again to generate and enroll a new Android-held key.",
            )
    val secretBytes =
        credentialStore.getSecret(credentialRef)
            ?: throw remoteOperationFailure(
                code = AegisFailureCodes.SSH_CREDENTIAL_MISSING,
                component = "android-sshj",
                operation = "authenticate-profile",
                stage = "credential-load",
                category = FailureCategory.DATA,
                summary = "The Android SSH private key could not be loaded",
                retryable = false,
                nextAction = "Pair the device again; the missing private key cannot be recovered from Windows.",
            )
    try {
        when (profile.authMethod) {
            AuthMethod.Password -> authenticateWithPassword(profile.username, secretBytes)
            AuthMethod.PrivateKey -> authenticateWithPrivateKey(profile.username, secretBytes)
            AuthMethod.Agent -> error("SSH agent authentication is not available on Android")
        }
    } finally {
        secretBytes.fill(0)
    }
}

private fun SSHClient.authenticateWithPassword(
    username: String,
    secretBytes: ByteArray,
) {
    val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(secretBytes))
    val password = CharArray(decoded.remaining())
    decoded.get(password)
    try {
        authPassword(username, password)
    } finally {
        password.fill('\u0000')
        decoded.rewind()
        while (decoded.hasRemaining()) decoded.put('\u0000')
    }
}

private fun SSHClient.authenticateWithPrivateKey(
    username: String,
    secretBytes: ByteArray,
) {
    val secret = secretBytes.toString(Charsets.UTF_8)
    val keyCredentials = sshCredentialJson.decodeFromString(AndroidSshPrivateKeyCredentials.serializer(), secret)
    val privateKey = keyCredentials.privateKey.trim()
    if (privateKey.isBlank()) error("Stored SSH private key is empty")
    val passwordFinder =
        keyCredentials.passphrase
            ?.takeIf { it.isNotBlank() }
            ?.let { StaticPasswordFinder(it.toCharArray()) }
    try {
        authPublickey(username, loadKeys(privateKey, null, passwordFinder))
    } finally {
        passwordFinder?.clear()
    }
}

private class StaticPasswordFinder(
    private val password: CharArray,
) : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray = password.copyOf()

    override fun shouldRetry(resource: Resource<*>?): Boolean = false

    fun clear() = password.fill('\u0000')
}
