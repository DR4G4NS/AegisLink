package dev.aegis.remote.relayclient

import dev.aegis.remote.core.relay.RelayRegistration
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Response
import dev.aegis.remote.core.security.IdentityRotationPhase
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.security.PreparedDeviceIdentityRotation
import dev.aegis.remote.core.security.TransactionalDeviceIdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Narrow remote boundary used to test the two-phase identity transaction. */
interface RelayIdentityLifecycleRemote {
    suspend fun registerDeviceV2(
        identity: LocalDeviceIdentity,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayRegistration

    suspend fun rotateDeviceKeyV2(
        currentIdentity: LocalDeviceIdentity,
        replacementIdentity: LocalDeviceIdentity,
        operationId: String,
        activateLocally: Boolean = true,
    ): RelayRegistration

    fun adoptRotatedDeviceKeyV2(
        replacementIdentity: LocalDeviceIdentity,
        registration: RelayRegistration,
    )

    suspend fun revokeDeviceV2(identity: LocalDeviceIdentity): RevokeRelayDeviceV2Response
}

data class RelayIdentityRotationResult(
    val operationId: String,
    val reason: KeyRotationReason,
    val identity: LocalDeviceIdentity,
    val registration: RelayRegistration,
)

/**
 * Commits a replacement locally only after the relay has accepted the old-key
 * proof. A prepared operation is durable and retryable, so a lost HTTP response
 * never causes an unsafe local rollback or a split-brain identity.
 */
class RelayIdentityLifecycleCoordinator(
    private val identityStore: TransactionalDeviceIdentityStore,
    private val remote: RelayIdentityLifecycleRemote,
    private val remoteAttempts: Int = DEFAULT_REMOTE_ATTEMPTS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    init {
        require(remoteAttempts > 0) { "IDENTITY_ROTATION_ATTEMPTS_MUST_BE_POSITIVE" }
        require(retryDelayMillis >= 0L) { "IDENTITY_ROTATION_RETRY_DELAY_INVALID" }
    }

    suspend fun rotate(
        reason: KeyRotationReason,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayIdentityRotationResult {
        require(displayName.isNotBlank()) { "IDENTITY_DISPLAY_NAME_REQUIRED" }
        val prepared = identityStore.pendingRotation() ?: identityStore.prepareRotation(reason)
        return when (prepared.phase) {
            IdentityRotationPhase.Prepared -> {
                rotatePrepared(prepared)
            }

            IdentityRotationPhase.RemoteConfirmed -> {
                recoverConfirmed(
                    prepared = prepared,
                    displayName = displayName,
                    remoteAccessEnabled = remoteAccessEnabled,
                )
            }
        }
    }

    suspend fun revoke(): RevokeRelayDeviceV2Response = remote.revokeDeviceV2(identityStore.getOrCreate())

    /** Explicit operator recovery only; ambiguous network failures stay prepared. */
    suspend fun rollbackPrepared(): Boolean {
        val pending = identityStore.pendingRotation() ?: return false
        check(pending.phase == IdentityRotationPhase.Prepared) { "CONFIRMED_IDENTITY_ROTATION_CANNOT_ROLL_BACK" }
        identityStore.rollbackRotation(pending.operationId)
        return true
    }

    private suspend fun rotatePrepared(prepared: PreparedDeviceIdentityRotation): RelayIdentityRotationResult {
        val registration = rotateRemoteWithRetry(prepared)
        val confirmed =
            try {
                identityStore.markRotationRemoteConfirmed(prepared.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw RelayIdentityRotationPendingException(prepared.operationId, error)
            }
        val committed =
            try {
                identityStore.commitRotation(confirmed.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw RelayIdentityRotationPendingException(confirmed.operationId, error)
            }
        remote.adoptRotatedDeviceKeyV2(committed, registration)
        return RelayIdentityRotationResult(
            operationId = confirmed.operationId,
            reason = confirmed.reason,
            identity = committed,
            registration = registration,
        )
    }

    private suspend fun recoverConfirmed(
        prepared: PreparedDeviceIdentityRotation,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayIdentityRotationResult {
        val registration =
            try {
                remote.registerDeviceV2(
                    identity = prepared.replacementIdentity,
                    displayName = displayName,
                    remoteAccessEnabled = remoteAccessEnabled,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw RelayIdentityRotationPendingException(prepared.operationId, error)
            }
        val committed =
            try {
                identityStore.commitRotation(prepared.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw RelayIdentityRotationPendingException(prepared.operationId, error)
            }
        return RelayIdentityRotationResult(
            operationId = prepared.operationId,
            reason = prepared.reason,
            identity = committed,
            registration = registration,
        )
    }

    private suspend fun rotateRemoteWithRetry(prepared: PreparedDeviceIdentityRotation): RelayRegistration {
        var lastError: Throwable? = null
        repeat(remoteAttempts) { attempt ->
            try {
                return remote.rotateDeviceKeyV2(
                    currentIdentity = prepared.currentIdentity,
                    replacementIdentity = prepared.replacementIdentity,
                    operationId = prepared.operationId,
                    activateLocally = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < remoteAttempts && retryDelayMillis > 0L) delay(retryDelayMillis)
            }
        }
        throw RelayIdentityRotationPendingException(prepared.operationId, lastError)
    }

    private companion object {
        const val DEFAULT_REMOTE_ATTEMPTS = 2
        const val DEFAULT_RETRY_DELAY_MILLIS = 250L
    }
}

class RelayIdentityRotationPendingException(
    val operationId: String,
    cause: Throwable?,
) : IllegalStateException(
        "IDN-1011: identity rotation remains safely prepared; retry operation $operationId",
        cause,
    )
