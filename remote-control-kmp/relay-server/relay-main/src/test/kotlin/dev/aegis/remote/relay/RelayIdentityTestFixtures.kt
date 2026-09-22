package dev.aegis.remote.relay

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.relay.RELAY_IDENTITY_PROTOCOL_VERSION
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Response
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayIdentityTranscript
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

internal object RelayIdentityTestFixtures {
    val policy =
        RelayIdentityPolicy(
            relayOrigin = "https://relay.example.test",
            challengeTtlMillis = 1_000L,
            maxClockSkewMillis = 100L,
        )
    val tokenHashSecret = "relay-identity-v2-test-token-hmac-secret".encodeToByteArray()

    fun identity(generation: Long = 1L): TestIdentity {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKeySpki = keyPair.public.encoded
        val deviceId =
            DeviceId(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                        DeviceIdentityCanonicalEncoding.deviceIdPreimage(
                            IdentitySignatureAlgorithm.ED25519,
                            publicKeySpki,
                        ),
                    ),
                ),
            )
        val fingerprint =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKeySpki),
            )
        return TestIdentity(
            keyPair = keyPair,
            identity =
                DevicePublicIdentity(
                    deviceId = deviceId,
                    algorithm = IdentitySignatureAlgorithm.ED25519,
                    publicKeySpki = publicKeySpki,
                    fingerprint = "SHA256:$fingerprint",
                    keyGeneration = generation,
                    securityLevel = IdentitySecurityLevel.OS_KEYSTORE,
                ),
        )
    }

    fun p256Identity(
        generation: Long = 1L,
        curveName: String = "secp256r1",
    ): TestIdentity {
        val keyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply {
                    initialize(ECGenParameterSpec(curveName))
                }.generateKeyPair()
        val spki = keyPair.public.encoded
        val algorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256
        val deviceId =
            DeviceId(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                        DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, spki),
                    ),
                ),
            )
        return TestIdentity(
            keyPair = keyPair,
            identity =
                DevicePublicIdentity(
                    deviceId = deviceId,
                    algorithm = algorithm,
                    publicKeySpki = spki,
                    fingerprint =
                        "SHA256:" +
                            Base64.getUrlEncoder().withoutPadding().encodeToString(
                                MessageDigest.getInstance("SHA-256").digest(spki),
                            ),
                    keyGeneration = generation,
                    securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
                ),
        )
    }

    fun register(
        registry: InMemoryRelayRegistry,
        identity: TestIdentity,
        now: Long,
        displayName: String = "Device",
        remoteAccessEnabled: Boolean = true,
        policy: RelayIdentityPolicy = this.policy,
    ): RegisterRelayDeviceV2Response {
        val challenge =
            registry.issueRegistrationChallenge(
                RelayDeviceChallengeRequest(identity = identity.identity),
                policy,
            )
        return registry.registerV2(
            registrationRequest(
                identity = identity,
                nonce = challenge.nonce,
                timestampEpochMillis = now,
                displayName = displayName,
                remoteAccessEnabled = remoteAccessEnabled,
                relayOrigin = policy.relayOrigin,
            ),
            policy,
        )
    }

    fun registrationRequest(
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

    fun revokeRequest(
        relayDeviceId: RelayDeviceId,
        identity: TestIdentity,
        timestampEpochMillis: Long,
        policy: RelayIdentityPolicy = this.policy,
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

    fun registry(
        clock: () -> Long,
        tokenTtlMillis: Long = 60_000L,
        sessionTtlMillis: Long = 15 * 60 * 1000L,
        store: RelayRegistryStore? = null,
    ): InMemoryRelayRegistry =
        InMemoryRelayRegistry(
            clock = clock,
            tokenTtlMillis = tokenTtlMillis,
            sessionTtlMillis = sessionTtlMillis,
            store = store,
            tokenHashSecret = tokenHashSecret,
        )

    fun sign(
        keyPair: KeyPair,
        payload: ByteArray,
    ): ByteArray =
        Signature
            .getInstance(
                if (keyPair.private.algorithm.equals("EC", true)) "SHA256withECDSA" else "Ed25519",
            ).run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }

    data class TestIdentity(
        val keyPair: KeyPair,
        val identity: DevicePublicIdentity,
    )
}
