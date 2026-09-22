package dev.aegis.remote.relay

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.relay.RELAY_IDENTITY_PROTOCOL_VERSION
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayIdentityTranscript
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayIdentityV2Test {
    @Test
    fun rejectsClaimOfExistingDeviceIdWithDifferentPublicKey() {
        var now = 1_000L
        val registry = registry(clock = { now })
        val legitimate = testIdentity()
        register(registry, legitimate, now = now)
        val attacker = testIdentity()
        val forgedIdentity = attacker.identity.copy(deviceId = legitimate.identity.deviceId)

        val failure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.issueRegistrationChallenge(
                    RelayDeviceChallengeRequest(identity = forgedIdentity),
                    policy,
                )
            }

        assertEquals(RelayIdentityRejectionCode.DeviceIdMismatch, failure.code)
    }

    @Test
    fun copiedDisplayNameAndFingerprintTextCannotClaimExistingRelayLocator() {
        var now = 1_000L
        val registry = registry(clock = { now })
        val legitimate = testIdentity()
        val legitimateRegistration =
            register(
                registry = registry,
                identity = legitimate,
                displayName = "Same visible name SHA256:copied-text",
                now = now,
            )
        val attacker = testIdentity()

        val attackerRegistration =
            register(
                registry = registry,
                identity = attacker,
                displayName = "Same visible name SHA256:copied-text",
                now = now,
            )

        assertNotEquals(legitimateRegistration.relayDeviceId, attackerRegistration.relayDeviceId)
        assertNotEquals(legitimate.identity.deviceId, attacker.identity.deviceId)
    }

    @Test
    fun rejectsExpiredRegistrationToken() {
        var now = 1_000L
        val registry = registry(clock = { now }, tokenTtlMillis = 100L)
        val registered = register(registry, testIdentity(), now = now)

        now += 101L

        assertFalse(registry.authenticate(registered.relayDeviceId, registered.authToken))
    }

    @Test
    fun rejectsReusedChallenge() {
        val now = 1_000L
        val registry = registry(clock = { now })
        val identity = testIdentity()
        val challenge =
            registry.issueRegistrationChallenge(
                RelayDeviceChallengeRequest(identity = identity.identity),
                policy,
            )
        val request = registrationRequest(identity, challenge.nonce, now)

        registry.registerV2(request, policy)
        val failure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.registerV2(request, policy)
            }

        assertEquals(RelayIdentityRejectionCode.ChallengeMissingOrReused, failure.code)
    }

    @Test
    fun rejectsChallengeProofSignedForAnotherRelayOrigin() {
        val now = 1_000L
        val registry = registry(clock = { now })
        val identity = testIdentity()
        val challenge =
            registry.issueRegistrationChallenge(
                RelayDeviceChallengeRequest(identity = identity.identity),
                policy,
            )
        val wrongOrigin = "https://other-relay.example.test"
        val request = registrationRequest(identity, challenge.nonce, now, relayOrigin = wrongOrigin)

        val failure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.registerV2(request, policy)
            }

        assertEquals(RelayIdentityRejectionCode.RelayOriginMismatch, failure.code)
    }

    @Test
    fun rejectsRegistrationProofOutsideClockSkew() {
        val now = 1_000L
        val registry = registry(clock = { now })
        val identity = testIdentity()
        val challenge =
            registry.issueRegistrationChallenge(
                RelayDeviceChallengeRequest(identity = identity.identity),
                policy,
            )
        val request = registrationRequest(identity, challenge.nonce, now + policy.maxClockSkewMillis + 1)

        val failure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.registerV2(request, policy)
            }

        assertEquals(RelayIdentityRejectionCode.ClockSkew, failure.code)
    }

    @Test
    fun rejectsExpiredChallengeBeforeProofVerification() {
        var now = 1_000L
        val shortPolicy = policy.copy(challengeTtlMillis = 100L)
        val registry = registry(clock = { now })
        val identity = testIdentity()
        val challenge =
            registry.issueRegistrationChallenge(
                RelayDeviceChallengeRequest(identity = identity.identity),
                shortPolicy,
            )
        val request = registrationRequest(identity, challenge.nonce, now)
        now += 100L

        val failure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.registerV2(request, shortPolicy)
            }

        assertEquals(RelayIdentityRejectionCode.ChallengeExpired, failure.code)
    }

    @Test
    fun rejectsRotationNotSignedByCurrentKey() {
        val now = 1_000L
        val registry = registry(clock = { now })
        val current = testIdentity(generation = 1L)
        val registration = register(registry, current, now = now)
        val replacement = testIdentity(generation = 2L)
        val attacker = testIdentity()
        val unsignedRequest =
            RotateRelayDeviceKeyV2Request(
                operationId = "00000000-0000-0000-0000-000000000101",
                relayDeviceId = registration.relayDeviceId,
                currentIdentity = current.identity,
                replacementIdentity = replacement.identity,
                relayOrigin = policy.relayOrigin,
                timestampEpochMillis = now,
                signature = ByteArray(0),
            )
        val request =
            unsignedRequest.copy(
                signature =
                    sign(
                        attacker.keyPair,
                        RelayIdentityTranscript.rotation(
                            protocolVersion = unsignedRequest.protocolVersion,
                            operationId = unsignedRequest.operationId,
                            relayOrigin = unsignedRequest.relayOrigin,
                            relayDeviceId = unsignedRequest.relayDeviceId,
                            currentIdentity = unsignedRequest.currentIdentity,
                            replacementIdentity = unsignedRequest.replacementIdentity,
                            timestampEpochMillis = unsignedRequest.timestampEpochMillis,
                        ),
                    ),
            )

        val failure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.rotateKeyV2(request, policy)
            }

        assertEquals(RelayIdentityRejectionCode.RotationUnauthorized, failure.code)
    }

    @Test
    fun authorizedRotationBindsReplacementIdentityAndInvalidatesSessions() {
        val now = 1_000L
        val registry = registry(clock = { now })
        val source = register(registry, testIdentity(), remoteAccessEnabled = false, now = now)
        val current = testIdentity(generation = 1L)
        val target = register(registry, current, remoteAccessEnabled = true, now = now)
        val session = registry.createSession(source.relayDeviceId, target.relayDeviceId)!!
        val replacement = testIdentity(generation = 2L)
        val unsignedRequest =
            RotateRelayDeviceKeyV2Request(
                operationId = "00000000-0000-0000-0000-000000000102",
                relayDeviceId = target.relayDeviceId,
                currentIdentity = current.identity,
                replacementIdentity = replacement.identity,
                relayOrigin = policy.relayOrigin,
                timestampEpochMillis = now,
                signature = ByteArray(0),
            )
        val request =
            unsignedRequest.copy(
                signature =
                    sign(
                        current.keyPair,
                        RelayIdentityTranscript.rotation(
                            protocolVersion = unsignedRequest.protocolVersion,
                            operationId = unsignedRequest.operationId,
                            relayOrigin = unsignedRequest.relayOrigin,
                            relayDeviceId = unsignedRequest.relayDeviceId,
                            currentIdentity = unsignedRequest.currentIdentity,
                            replacementIdentity = unsignedRequest.replacementIdentity,
                            timestampEpochMillis = unsignedRequest.timestampEpochMillis,
                        ),
                    ),
            )

        val response = registry.rotateKeyV2(request, policy)

        assertTrue(response.identity.matches(replacement.identity))
        assertEquals(listOf(session.session.sessionId), response.invalidatedSessionIds)
        assertFalse(registry.authenticate(target.relayDeviceId, target.authToken))
        assertTrue(registry.authenticate(target.relayDeviceId, response.authToken))
        assertNull(registry.getSession(session.session.sessionId))
        val retiredFailure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.issueRegistrationChallenge(
                    RelayDeviceChallengeRequest(identity = current.identity),
                    policy,
                )
            }
        assertEquals(RelayIdentityRejectionCode.IdentityRevoked, retiredFailure.code)
    }

    @Test
    fun rotationRetryWithSameSignedOperationIsIdempotentAfterResponseLoss() {
        var now = 1_000L
        val registry = registry(clock = { now })
        val current = testIdentity(generation = 1L)
        val registered = register(registry, current, now = now)
        val replacement = testIdentity(generation = 2L)
        val operationId = "00000000-0000-0000-0000-000000000103"

        fun request() =
            RotateRelayDeviceKeyV2Request(
                operationId = operationId,
                relayDeviceId = registered.relayDeviceId,
                currentIdentity = current.identity,
                replacementIdentity = replacement.identity,
                relayOrigin = policy.relayOrigin,
                timestampEpochMillis = now,
                signature =
                    sign(
                        current.keyPair,
                        RelayIdentityTranscript.rotation(
                            protocolVersion = RELAY_IDENTITY_PROTOCOL_VERSION,
                            operationId = operationId,
                            relayOrigin = policy.relayOrigin,
                            relayDeviceId = registered.relayDeviceId,
                            currentIdentity = current.identity,
                            replacementIdentity = replacement.identity,
                            timestampEpochMillis = now,
                        ),
                    ),
            )

        val first = registry.rotateKeyV2(request(), policy)
        now += 1L
        val retried = registry.rotateKeyV2(request(), policy)

        assertEquals(first.relayDeviceId, retried.relayDeviceId)
        assertTrue(retried.identity.matches(replacement.identity))
        assertTrue(retried.invalidatedSessionIds.isEmpty())
        assertFalse(registry.authenticate(registered.relayDeviceId, first.authToken))
        assertTrue(registry.authenticate(registered.relayDeviceId, retried.authToken))
    }

    @Test
    fun revokedIdentityCannotAuthenticateOrCreateNewSessions() {
        val now = 1_000L
        val registry = registry(clock = { now })
        val source = register(registry, testIdentity(), remoteAccessEnabled = false, now = now)
        val targetIdentity = testIdentity()
        val target = register(registry, targetIdentity, remoteAccessEnabled = true, now = now)

        val revocation = revokeRequest(target.relayDeviceId, targetIdentity, now)
        registry.revokeV2(revocation, policy)

        assertFalse(registry.authenticate(target.relayDeviceId, target.authToken))
        assertNull(registry.createSession(source.relayDeviceId, target.relayDeviceId))
        val challengeFailure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.issueRegistrationChallenge(
                    RelayDeviceChallengeRequest(identity = targetIdentity.identity),
                    policy,
                )
            }
        assertEquals(RelayIdentityRejectionCode.IdentityRevoked, challengeFailure.code)
    }

    @Test
    fun revocationInvalidatesExistingSession() {
        val now = 1_000L
        val registry = registry(clock = { now })
        val source = register(registry, testIdentity(), remoteAccessEnabled = false, now = now)
        val targetIdentity = testIdentity()
        val target = register(registry, targetIdentity, remoteAccessEnabled = true, now = now)
        val session = registry.createSession(source.relayDeviceId, target.relayDeviceId)!!

        val response = registry.revokeV2(revokeRequest(target.relayDeviceId, targetIdentity, now), policy)

        assertEquals(listOf(session.session.sessionId), response.invalidatedSessionIds)
        assertNull(registry.getSession(session.session.sessionId))
    }

    @Test
    fun revocationRetryReturnsOriginalConfirmation() {
        var now = 1_000L
        val registry = registry(clock = { now })
        val identity = testIdentity()
        val registered = register(registry, identity, now = now)

        val first = registry.revokeV2(revokeRequest(registered.relayDeviceId, identity, now), policy)
        now += 1L
        val retry = registry.revokeV2(revokeRequest(registered.relayDeviceId, identity, now), policy)

        assertEquals(first.relayDeviceId, retry.relayDeviceId)
        assertEquals(first.deviceId, retry.deviceId)
        assertEquals(first.revokedAtEpochMillis, retry.revokedAtEpochMillis)
        assertTrue(retry.invalidatedSessionIds.isEmpty())
    }

    @Test
    fun legacyRegistrationCannotAuthenticateOrOpenRemoteSession() {
        val registry = registry(clock = { 1_000L })
        val legacy =
            registry.register(
                dev.aegis.remote.core.relay.RelayDeviceRegistration(
                    relayDeviceId =
                        dev.aegis.remote.core.model
                            .RelayDeviceId("legacy-device"),
                    displayName = "Legacy",
                    publicKeyFingerprint = "SHA256:text-only",
                    remoteAccessEnabled = true,
                ),
            )

        assertFalse(registry.authenticate(legacy.relayDeviceId, legacy.authToken))
        assertNull(registry.createSession(legacy.relayDeviceId, legacy.relayDeviceId))
    }

    private fun registry(
        clock: () -> Long,
        tokenTtlMillis: Long = 60_000L,
    ): InMemoryRelayRegistry =
        InMemoryRelayRegistry(
            clock = clock,
            tokenTtlMillis = tokenTtlMillis,
            tokenHashSecret = TEST_TOKEN_HASH_SECRET,
        )

    private fun register(
        registry: InMemoryRelayRegistry,
        identity: TestIdentity,
        displayName: String = "Device",
        remoteAccessEnabled: Boolean = true,
        now: Long,
    ): dev.aegis.remote.core.relay.RegisterRelayDeviceV2Response {
        val challenge =
            registry.issueRegistrationChallenge(
                RelayDeviceChallengeRequest(identity = identity.identity),
                policy,
            )
        return registry.registerV2(
            registrationRequest(identity, challenge.nonce, now, displayName, remoteAccessEnabled),
            policy,
        )
    }

    private fun registrationRequest(
        identity: TestIdentity,
        nonce: String,
        timestampEpochMillis: Long,
        displayName: String = "Device",
        remoteAccessEnabled: Boolean = true,
        relayOrigin: String = policy.relayOrigin,
    ): RegisterRelayDeviceV2Request {
        val signature =
            sign(
                identity.keyPair,
                RelayIdentityTranscript.registration(
                    protocolVersion = RELAY_IDENTITY_PROTOCOL_VERSION,
                    relayOrigin = relayOrigin,
                    identity = identity.identity,
                    challengeNonce = nonce,
                    timestampEpochMillis = timestampEpochMillis,
                ),
            )
        return RegisterRelayDeviceV2Request(
            identity = identity.identity,
            challengeNonce = nonce,
            relayOrigin = relayOrigin,
            timestampEpochMillis = timestampEpochMillis,
            signature = signature,
            displayName = displayName,
            remoteAccessEnabled = remoteAccessEnabled,
        )
    }

    private fun revokeRequest(
        relayDeviceId: dev.aegis.remote.core.model.RelayDeviceId,
        identity: TestIdentity,
        timestampEpochMillis: Long,
    ): RevokeRelayDeviceV2Request {
        val signature =
            sign(
                identity.keyPair,
                RelayIdentityTranscript.revocation(
                    protocolVersion = RELAY_IDENTITY_PROTOCOL_VERSION,
                    relayOrigin = policy.relayOrigin,
                    relayDeviceId = relayDeviceId,
                    identity = identity.identity,
                    timestampEpochMillis = timestampEpochMillis,
                ),
            )
        return RevokeRelayDeviceV2Request(
            relayDeviceId = relayDeviceId,
            identity = identity.identity,
            relayOrigin = policy.relayOrigin,
            timestampEpochMillis = timestampEpochMillis,
            signature = signature,
        )
    }

    private fun testIdentity(generation: Long = 1L): TestIdentity {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKeySpki = keyPair.public.encoded
        val deviceId =
            DeviceId(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                        dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding.deviceIdPreimage(
                            dev.aegis.remote.core.model.IdentitySignatureAlgorithm.ED25519,
                            publicKeySpki,
                        ),
                    ),
                ),
            )
        return TestIdentity(
            keyPair = keyPair,
            identity =
                DevicePublicIdentity(
                    deviceId = deviceId,
                    algorithm = dev.aegis.remote.core.model.IdentitySignatureAlgorithm.ED25519,
                    publicKeySpki = publicKeySpki,
                    fingerprint =
                        "SHA256:" +
                            Base64.getUrlEncoder().withoutPadding().encodeToString(
                                MessageDigest.getInstance("SHA-256").digest(publicKeySpki),
                            ),
                    keyGeneration = generation,
                    securityLevel = dev.aegis.remote.core.model.IdentitySecurityLevel.OS_KEYSTORE,
                ),
        )
    }

    private fun sign(
        keyPair: KeyPair,
        payload: ByteArray,
    ): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }

    private data class TestIdentity(
        val keyPair: KeyPair,
        val identity: DevicePublicIdentity,
    )

    private companion object {
        val policy =
            RelayIdentityPolicy(
                relayOrigin = "https://relay.example.test",
                challengeTtlMillis = 1_000L,
                maxClockSkewMillis = 100L,
            )
        val TEST_TOKEN_HASH_SECRET = "relay-identity-v2-test-token-hmac-secret".encodeToByteArray()
    }
}
