package dev.aegis.remote.relayclient

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.relay.RelayAuthToken
import dev.aegis.remote.core.relay.RelayRegistration
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Response
import dev.aegis.remote.core.security.CryptoCapabilityReport
import dev.aegis.remote.core.security.IdentityRotationPhase
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.security.PreparedDeviceIdentityRotation
import dev.aegis.remote.core.security.TransactionalDeviceIdentityStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RelayIdentityLifecycleCoordinatorTest {
    @Test
    fun commitsOnlyAfterRemoteConfirmationAndThenAdoptsToken() =
        runTest {
            val store = RecordingTransactionalIdentityStore()
            val remote = RecordingIdentityRemote()
            val coordinator = RelayIdentityLifecycleCoordinator(store, remote, retryDelayMillis = 0L)

            val result = coordinator.rotate(KeyRotationReason.UserRequested, "Windows host", true)

            assertEquals(listOf(TEST_OPERATION_ID), remote.rotationOperationIds)
            assertEquals(listOf(false), remote.rotationActivationFlags)
            assertEquals(listOf("mark", "commit"), store.commitEvents)
            assertSame(store.replacement, result.identity)
            assertSame(store.replacement, store.active)
            assertSame(store.replacement, remote.adoptedIdentity)
            assertEquals(result.registration, remote.adoptedRegistration)
            assertEquals(null, store.pendingRotation())
        }

    @Test
    fun ambiguousRemoteFailureRemainsPreparedAndNeverRollsBack() =
        runTest {
            val store = RecordingTransactionalIdentityStore()
            val remote = RecordingIdentityRemote(rotationFailuresBeforeSuccess = Int.MAX_VALUE)
            val coordinator = RelayIdentityLifecycleCoordinator(store, remote, remoteAttempts = 2, retryDelayMillis = 0L)

            val failure =
                assertFailsWith<RelayIdentityRotationPendingException> {
                    coordinator.rotate(KeyRotationReason.SuspectedCompromise, "Android phone", true)
                }

            assertEquals(TEST_OPERATION_ID, failure.operationId)
            assertEquals(listOf(TEST_OPERATION_ID, TEST_OPERATION_ID), remote.rotationOperationIds)
            assertEquals(IdentityRotationPhase.Prepared, store.pendingRotation()?.phase)
            assertSame(store.original, store.active)
            assertFalse(store.rolledBack)
            assertTrue(store.commitEvents.isEmpty())
        }

    @Test
    fun responseLossRetryUsesSameOperationAndCompletes() =
        runTest {
            val store = RecordingTransactionalIdentityStore()
            val remote = RecordingIdentityRemote(rotationFailuresBeforeSuccess = 1)
            val coordinator = RelayIdentityLifecycleCoordinator(store, remote, remoteAttempts = 2, retryDelayMillis = 0L)

            val result = coordinator.rotate(KeyRotationReason.Scheduled, "Windows host", true)

            assertEquals(listOf(TEST_OPERATION_ID, TEST_OPERATION_ID), remote.rotationOperationIds)
            assertEquals(2L, result.identity.publicIdentity.keyGeneration)
            assertEquals(null, store.pendingRotation())
        }

    @Test
    fun restartRecoveryRegistersConfirmedReplacementBeforeLocalCommit() =
        runTest {
            val store = RecordingTransactionalIdentityStore(failFirstCommit = true)
            val remote = RecordingIdentityRemote()
            val coordinator = RelayIdentityLifecycleCoordinator(store, remote, remoteAttempts = 1, retryDelayMillis = 0L)

            assertFailsWith<RelayIdentityRotationPendingException> {
                coordinator.rotate(KeyRotationReason.Recovery, "Windows host", true)
            }
            assertEquals(IdentityRotationPhase.RemoteConfirmed, store.pendingRotation()?.phase)

            val recovered = coordinator.rotate(KeyRotationReason.Recovery, "Windows host", true)

            assertEquals(1, remote.registrationCalls)
            assertSame(store.replacement, remote.lastRegisteredIdentity)
            assertSame(store.replacement, recovered.identity)
            assertEquals(null, store.pendingRotation())
        }
}

private class RecordingTransactionalIdentityStore(
    private var failFirstCommit: Boolean = false,
) : TransactionalDeviceIdentityStore {
    val original = RecordingIdentity(1L)
    val replacement = RecordingIdentity(2L)
    var active: LocalDeviceIdentity = original
    val commitEvents = mutableListOf<String>()
    var rolledBack: Boolean = false
    private var pending: PreparedDeviceIdentityRotation? = null

    override suspend fun getOrCreate(): LocalDeviceIdentity = active

    override suspend fun rotate(reason: KeyRotationReason): LocalDeviceIdentity = error("LOCAL_ONLY_ROTATION_DISABLED")

    override suspend fun capabilityReport(): CryptoCapabilityReport =
        CryptoCapabilityReport(
            identityOptions = emptyList(),
            keyAgreementOptions = emptyList(),
            aeadOptions = emptyList(),
            supportedSuites = emptyList(),
            recommendedSuite = null,
            failures = emptyList(),
        )

    override suspend fun prepareRotation(reason: KeyRotationReason): PreparedDeviceIdentityRotation =
        pending
            ?: PreparedDeviceIdentityRotation(
                operationId = TEST_OPERATION_ID,
                reason = reason,
                phase = IdentityRotationPhase.Prepared,
                currentIdentity = active,
                replacementIdentity = replacement,
            ).also { pending = it }

    override suspend fun pendingRotation(): PreparedDeviceIdentityRotation? = pending

    override suspend fun markRotationRemoteConfirmed(operationId: String): PreparedDeviceIdentityRotation {
        val current = requirePending(operationId)
        commitEvents += "mark"
        return current.copy(phase = IdentityRotationPhase.RemoteConfirmed).also { pending = it }
    }

    override suspend fun commitRotation(operationId: String): LocalDeviceIdentity {
        val current = requirePending(operationId)
        check(current.phase == IdentityRotationPhase.RemoteConfirmed)
        commitEvents += "commit"
        if (failFirstCommit) {
            failFirstCommit = false
            error("SIMULATED_DURABLE_COMMIT_FAILURE")
        }
        active = current.replacementIdentity
        pending = null
        return active
    }

    override suspend fun rollbackRotation(operationId: String) {
        requirePending(operationId)
        rolledBack = true
        pending = null
    }

    private fun requirePending(operationId: String): PreparedDeviceIdentityRotation = requireNotNull(pending).also { require(it.operationId == operationId) }
}

private class RecordingIdentityRemote(
    private var rotationFailuresBeforeSuccess: Int = 0,
) : RelayIdentityLifecycleRemote {
    val rotationOperationIds = mutableListOf<String>()
    val rotationActivationFlags = mutableListOf<Boolean>()
    var registrationCalls: Int = 0
    var lastRegisteredIdentity: LocalDeviceIdentity? = null
    var adoptedIdentity: LocalDeviceIdentity? = null
    var adoptedRegistration: RelayRegistration? = null

    override suspend fun registerDeviceV2(
        identity: LocalDeviceIdentity,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayRegistration {
        registrationCalls += 1
        lastRegisteredIdentity = identity
        return registration("recovered-$registrationCalls")
    }

    override suspend fun rotateDeviceKeyV2(
        currentIdentity: LocalDeviceIdentity,
        replacementIdentity: LocalDeviceIdentity,
        operationId: String,
        activateLocally: Boolean,
    ): RelayRegistration {
        rotationOperationIds += operationId
        rotationActivationFlags += activateLocally
        if (rotationFailuresBeforeSuccess > 0) {
            rotationFailuresBeforeSuccess -= 1
            error("SIMULATED_RESPONSE_LOSS")
        }
        return registration("rotated-${rotationOperationIds.size}")
    }

    override fun adoptRotatedDeviceKeyV2(
        replacementIdentity: LocalDeviceIdentity,
        registration: RelayRegistration,
    ) {
        adoptedIdentity = replacementIdentity
        adoptedRegistration = registration
    }

    override suspend fun revokeDeviceV2(identity: LocalDeviceIdentity): RevokeRelayDeviceV2Response =
        RevokeRelayDeviceV2Response(
            relayDeviceId = RelayDeviceId("relay-device"),
            deviceId = identity.publicIdentity.deviceId,
            revokedAtEpochMillis = 2_000L,
        )

    private fun registration(token: String): RelayRegistration =
        RelayRegistration(
            relayDeviceId = RelayDeviceId("relay-device"),
            authToken = RelayAuthToken(token, 60_000L),
        )
}

private class RecordingIdentity(
    generation: Long,
) : LocalDeviceIdentity {
    override val publicIdentity =
        DevicePublicIdentity(
            deviceId = DeviceId("device-$generation"),
            algorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256,
            publicKeySpki = byteArrayOf(generation.toByte()),
            fingerprint = "SHA256:device-$generation",
            keyGeneration = generation,
            securityLevel = IdentitySecurityLevel.OS_KEYSTORE,
        )

    override suspend fun sign(payload: ByteArray): ByteArray = payload.copyOf()
}

private const val TEST_OPERATION_ID = "00000000-0000-0000-0000-000000000201"
