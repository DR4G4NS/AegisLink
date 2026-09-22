package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.AeadAlgorithm
import dev.aegis.remote.core.model.CryptoSuite
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.KeyAgreementAlgorithm
import kotlinx.serialization.Serializable

/**
 * A local identity can sign protocol transcripts without exposing its private
 * key. Platform implementations must keep the private material in their
 * platform secure store.
 */
interface LocalDeviceIdentity {
    val publicIdentity: DevicePublicIdentity

    suspend fun sign(payload: ByteArray): ByteArray
}

interface DeviceIdentityStore {
    suspend fun getOrCreate(): LocalDeviceIdentity

    /**
     * Legacy local-only rotation is intentionally unsafe for a relay identity:
     * callers must not activate the replacement before the relay confirms it.
     * Platform stores that support remote identities implement
     * [TransactionalDeviceIdentityStore] and reject this shortcut.
     */
    suspend fun rotate(reason: KeyRotationReason): LocalDeviceIdentity

    suspend fun capabilityReport(): CryptoCapabilityReport
}

enum class IdentityRotationPhase {
    Prepared,
    RemoteConfirmed,
}

data class PreparedDeviceIdentityRotation(
    val operationId: String,
    val reason: KeyRotationReason,
    val phase: IdentityRotationPhase,
    val currentIdentity: LocalDeviceIdentity,
    val replacementIdentity: LocalDeviceIdentity,
) {
    init {
        require(operationId.isNotBlank()) { "IDENTITY_ROTATION_OPERATION_ID_REQUIRED" }
        require(replacementIdentity.publicIdentity.keyGeneration == currentIdentity.publicIdentity.keyGeneration + 1) {
            "IDENTITY_ROTATION_GENERATION_MUST_BE_CONSECUTIVE"
        }
    }
}

/**
 * Durable two-phase local identity storage used by the relay lifecycle
 * coordinator. A prepared replacement remains inactive and the current key
 * remains available for proof-of-possession until [markRotationRemoteConfirmed]
 * has durably recorded the relay response. Confirmed preparations may be
 * committed during process restart; unconfirmed preparations may only roll
 * back.
 */
interface TransactionalDeviceIdentityStore : DeviceIdentityStore {
    suspend fun prepareRotation(reason: KeyRotationReason): PreparedDeviceIdentityRotation

    suspend fun pendingRotation(): PreparedDeviceIdentityRotation?

    suspend fun markRotationRemoteConfirmed(operationId: String): PreparedDeviceIdentityRotation

    suspend fun commitRotation(operationId: String): LocalDeviceIdentity

    suspend fun rollbackRotation(operationId: String)
}

interface CryptoCapabilityProbe {
    suspend fun evaluate(forceRefresh: Boolean = false): CryptoCapabilityReport
}

@Serializable
data class IdentityCapability(
    val algorithm: IdentitySignatureAlgorithm,
    val supported: Boolean,
    val securityLevel: IdentitySecurityLevel,
    val failureCode: String? = null,
)

@Serializable
data class KeyAgreementCapability(
    val algorithm: KeyAgreementAlgorithm,
    val supported: Boolean,
    val failureCode: String? = null,
)

@Serializable
data class AeadCapability(
    val algorithm: AeadAlgorithm,
    val supported: Boolean,
    val failureCode: String? = null,
)

@Serializable
data class CryptoCapabilityFailure(
    val code: String,
    val component: String,
)

@Serializable
data class CryptoCapabilityReport(
    val identityOptions: List<IdentityCapability>,
    val keyAgreementOptions: List<KeyAgreementCapability>,
    val aeadOptions: List<AeadCapability>,
    val supportedSuites: List<CryptoSuite>,
    val recommendedSuite: CryptoSuite?,
    val failures: List<CryptoCapabilityFailure>,
)

enum class KeyRotationReason {
    SuspectedCompromise,
    Scheduled,
    UserRequested,
    Recovery,
}
