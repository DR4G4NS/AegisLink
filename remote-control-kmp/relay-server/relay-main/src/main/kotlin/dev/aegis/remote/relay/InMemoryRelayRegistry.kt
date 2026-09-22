package dev.aegis.remote.relay

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RELAY_IDENTITY_PROTOCOL_VERSION
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Request
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Response
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceChallengeResponse
import dev.aegis.remote.core.relay.RelayDeviceIdentity
import dev.aegis.remote.core.relay.RelayDeviceRegistration
import dev.aegis.remote.core.relay.RelayIdentityTranscript
import dev.aegis.remote.core.relay.RelaySession
import dev.aegis.remote.core.relay.RelaySessionApproval
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Request
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Response
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Request
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Response
import dev.aegis.remote.core.security.ExactP256Curve
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private fun newRandomTokenHashSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

@Suppress("LargeClass")
class InMemoryRelayRegistry(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tokenTtlMillis: Long = 60 * 60 * 1000L,
    private val sessionTtlMillis: Long = 15 * 60 * 1000L,
    private val store: RelayRegistryStore? = null,
    private val challengeStore: RelayRegistrationChallengeStore = InMemoryRelayRegistrationChallengeStore(),
    tokenHashSecret: ByteArray = newRandomTokenHashSecret(),
) {
    private val devices = ConcurrentHashMap<String, RegisteredRelayDevice>()
    private val sessions = ConcurrentHashMap<String, RelayRendezvousSession>()
    private val secureRandom = SecureRandom()
    private val stateLock = Any()
    private val tokenHashSecret = tokenHashSecret.copyOf()

    init {
        require(this.tokenHashSecret.size >= MIN_TOKEN_HASH_SECRET_BYTES) {
            "Relay token HMAC secret must be at least $MIN_TOKEN_HASH_SECRET_BYTES bytes"
        }
        store?.load(clock())?.let { snapshot ->
            devices.putAll(snapshot.devices.associateBy { it.relayDeviceId.value })
            sessions.putAll(snapshot.sessions.associateBy { it.session.sessionId.value })
        }
    }

    /**
     * Legacy in-process registration kept only for existing test fixtures and
     * persisted v1 migration. The public HTTP v1 endpoint is disabled.
     */
    fun register(registration: RelayDeviceRegistration): RegisterRelayDeviceResponse =
        synchronized(stateLock) {
            val now = clock()
            val relayDeviceId = registration.relayDeviceId ?: newRelayDeviceId()
            val existing = devices[relayDeviceId.value]
            if (existing != null) {
                // A legacy fingerprint is display metadata, not proof of possession. Even an
                // exact copy of ID, name, and fingerprint must never be able to replace the
                // existing record or mint a fresh token for it.
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.IdentityKeyMismatch,
                    "Relay device id is already bound to another identity",
                )
            }
            val token = newToken()
            val expiresAt = now + tokenTtlMillis
            devices[relayDeviceId.value] =
                RegisteredRelayDevice(
                    relayDeviceId = relayDeviceId,
                    displayName = registration.displayName,
                    publicKeyFingerprint = registration.publicKeyFingerprint,
                    remoteAccessEnabled = registration.remoteAccessEnabled,
                    authTokenHash = hashToken(token),
                    expiresAtEpochMillis = expiresAt,
                    lastSeenAtEpochMillis = now,
                )
            persist()
            RegisterRelayDeviceResponse(relayDeviceId, token, expiresAt)
        }

    fun authenticate(
        relayDeviceId: RelayDeviceId,
        token: String,
    ): Boolean = authenticationExpiresAt(relayDeviceId, token) != null

    fun authenticationExpiresAt(
        relayDeviceId: RelayDeviceId,
        token: String,
    ): Long? {
        fun expiry(device: RegisteredRelayDevice?): Long? =
            device
                ?.takeIf {
                    it.v2IdentityOrNull() != null &&
                        it.revokedAtEpochMillis == null &&
                        tokenMatches(it.authTokenHash, token) &&
                        it.expiresAtEpochMillis > clock()
                }?.expiresAtEpochMillis
        expiry(devices[relayDeviceId.value])?.let { return it }
        refreshFromStore()
        return expiry(devices[relayDeviceId.value])
    }

    /** Refreshes the process cache while a production Redis mutation lock is held. */
    fun refreshFromStore() {
        val snapshot = store?.load(clock()) ?: return
        synchronized(stateLock) {
            devices.clear()
            devices.putAll(snapshot.devices.associateBy { it.relayDeviceId.value })
            sessions.clear()
            sessions.putAll(snapshot.sessions.associateBy { it.session.sessionId.value })
        }
    }

    fun issueRegistrationChallenge(
        request: RelayDeviceChallengeRequest,
        policy: RelayIdentityPolicy,
    ): RelayDeviceChallengeResponse {
        requireSupportedProtocol(request.protocolVersion)
        validatePublicIdentity(request.identity)
        val now = clock()
        return synchronized(stateLock) {
            val existing = devices.values.firstOrNull { it.deviceId == request.identity.deviceId }
            if (existing != null && !existing.matchesIdentity(request.identity)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.IdentityKeyMismatch,
                    "Device id is already bound to another public key",
                )
            }
            if (existing?.revokedAtEpochMillis != null || isRetiredIdentity(request.identity.deviceId)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.IdentityRevoked,
                    "The requested device identity has been revoked or retired",
                )
            }
            val nonce = newNonce()
            val expiresAt = now + policy.challengeTtlMillis
            val challenge =
                PendingRelayRegistrationChallenge(
                    nonce = nonce,
                    identity = request.identity.deepCopy(),
                    issuedAtEpochMillis = now,
                    expiresAtEpochMillis = expiresAt,
                    relayOrigin = policy.relayOrigin,
                )
            check(challengeStore.put(challenge, policy.challengeTtlMillis)) {
                "Unable to persist a unique registration challenge"
            }
            RelayDeviceChallengeResponse(
                nonce = nonce,
                issuedAtEpochMillis = now,
                expiresAtEpochMillis = expiresAt,
                relayOrigin = policy.relayOrigin,
            )
        }
    }

    fun registerV2(
        request: RegisterRelayDeviceV2Request,
        policy: RelayIdentityPolicy,
    ): RegisterRelayDeviceV2Response {
        requireSupportedProtocol(request.protocolVersion)
        validatePublicIdentity(request.identity)
        val now = clock()
        val challenge =
            challengeStore.take(request.challengeNonce)
                ?: throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.ChallengeMissingOrReused,
                    "Registration challenge is missing or has already been used",
                )
        if (challenge.expiresAtEpochMillis <= now) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.ChallengeExpired,
                "Registration challenge has expired",
            )
        }
        if (request.relayOrigin != policy.relayOrigin || request.relayOrigin != challenge.relayOrigin) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.RelayOriginMismatch,
                "Registration proof was created for another relay origin",
            )
        }
        if (!challenge.identity.matches(request.identity)) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.ChallengeIdentityMismatch,
                "Registration proof does not match the challenged identity",
            )
        }
        requireClockSkew(request.timestampEpochMillis, policy, now)
        val payload =
            RelayIdentityTranscript.registration(
                protocolVersion = request.protocolVersion,
                relayOrigin = request.relayOrigin,
                identity = request.identity,
                challengeNonce = request.challengeNonce,
                timestampEpochMillis = request.timestampEpochMillis,
            )
        if (!verifyIdentity(request.identity, payload, request.signature)) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.InvalidProof,
                "Registration proof is not valid for the challenged identity",
            )
        }

        return synchronized(stateLock) {
            val existing = devices.values.firstOrNull { it.deviceId == request.identity.deviceId }
            if (existing != null && !existing.matchesIdentity(request.identity)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.IdentityKeyMismatch,
                    "Device id is already bound to another public key",
                )
            }
            if (existing?.revokedAtEpochMillis != null || isRetiredIdentity(request.identity.deviceId)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.IdentityRevoked,
                    "The device identity is revoked or retired",
                )
            }

            val relayDeviceId = existing?.relayDeviceId ?: newRelayDeviceId()
            val token = newToken()
            val expiresAt = now + tokenTtlMillis
            devices[relayDeviceId.value] =
                RegisteredRelayDevice(
                    relayDeviceId = relayDeviceId,
                    displayName = request.displayName,
                    publicKeyFingerprint = request.identity.fingerprint,
                    remoteAccessEnabled = request.remoteAccessEnabled,
                    authTokenHash = hashToken(token),
                    expiresAtEpochMillis = expiresAt,
                    lastSeenAtEpochMillis = now,
                    deviceId = request.identity.deviceId,
                    signingPublicKey = request.identity.signingPublicKey.copyOf(),
                    keyAlgorithm = request.identity.keyAlgorithm,
                    keyGeneration = request.identity.keyGeneration,
                    securityLevel = request.identity.securityLevel.name,
                    retiredDeviceIds = existing?.retiredDeviceIds.orEmpty(),
                    lastRotationOperationId = existing?.lastRotationOperationId,
                    lastRotationSourceDeviceId = existing?.lastRotationSourceDeviceId,
                )
            persist()
            RegisterRelayDeviceV2Response(
                relayDeviceId = relayDeviceId,
                identity = request.identity.deepCopy(),
                authToken = token,
                expiresAtEpochMillis = expiresAt,
            )
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount")
    fun rotateKeyV2(
        request: RotateRelayDeviceKeyV2Request,
        policy: RelayIdentityPolicy,
    ): RotateRelayDeviceKeyV2Response {
        requireSupportedProtocol(request.protocolVersion)
        validatePublicIdentity(request.currentIdentity)
        validatePublicIdentity(request.replacementIdentity)
        requireRotationOperationId(request.operationId)
        val now = clock()
        if (request.relayOrigin != policy.relayOrigin) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.RelayOriginMismatch,
                "Key rotation was created for another relay origin",
            )
        }
        requireClockSkew(request.timestampEpochMillis, policy, now)

        return synchronized(stateLock) {
            val current =
                devices[request.relayDeviceId.value]
                    ?: throw RelayIdentityRejectedException(
                        RelayIdentityRejectionCode.UnknownRelayDevice,
                        "Relay device is not registered",
                    )
            if (current.revokedAtEpochMillis != null) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.IdentityRevoked,
                    "Relay device identity is revoked",
                )
            }
            if (
                request.currentIdentity.keyGeneration == Long.MAX_VALUE ||
                request.replacementIdentity.keyGeneration != request.currentIdentity.keyGeneration + 1L
            ) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.InvalidRotation,
                    "Replacement key generation must immediately follow the current generation",
                )
            }
            val payload =
                RelayIdentityTranscript.rotation(
                    protocolVersion = request.protocolVersion,
                    operationId = request.operationId,
                    relayOrigin = request.relayOrigin,
                    relayDeviceId = request.relayDeviceId,
                    currentIdentity = request.currentIdentity,
                    replacementIdentity = request.replacementIdentity,
                    timestampEpochMillis = request.timestampEpochMillis,
                )
            val idempotentRetry =
                current.lastRotationOperationId == request.operationId &&
                    current.lastRotationSourceDeviceId == request.currentIdentity.deviceId &&
                    request.currentIdentity.deviceId in current.retiredDeviceIds &&
                    current.matchesIdentity(request.replacementIdentity)
            if (idempotentRetry) {
                if (!verifyIdentity(request.currentIdentity, payload, request.signature)) {
                    throw RelayIdentityRejectedException(
                        RelayIdentityRejectionCode.RotationUnauthorized,
                        "Retried key rotation proof is not valid for the retired source key",
                    )
                }
                val token = newToken()
                val expiresAt = now + tokenTtlMillis
                devices[request.relayDeviceId.value] =
                    current.copy(
                        authTokenHash = hashToken(token),
                        expiresAtEpochMillis = expiresAt,
                        lastSeenAtEpochMillis = now,
                    )
                persist()
                return@synchronized RotateRelayDeviceKeyV2Response(
                    relayDeviceId = request.relayDeviceId,
                    identity = request.replacementIdentity.deepCopy(),
                    authToken = token,
                    expiresAtEpochMillis = expiresAt,
                    invalidatedSessionIds = emptyList(),
                )
            }
            if (!current.matchesIdentity(request.currentIdentity)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.RotationUnauthorized,
                    "The current identity does not own this relay locator",
                )
            }
            if (request.replacementIdentity.deviceId == request.currentIdentity.deviceId) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.InvalidRotation,
                    "Replacement key must differ from the current key",
                )
            }
            val replacementOwner =
                devices.values.firstOrNull {
                    it.relayDeviceId != request.relayDeviceId && it.deviceId == request.replacementIdentity.deviceId
                }
            if (replacementOwner != null || isRetiredIdentity(request.replacementIdentity.deviceId)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.IdentityKeyMismatch,
                    "Replacement identity is already bound or retired",
                )
            }
            if (!verifyIdentity(request.currentIdentity, payload, request.signature)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.RotationUnauthorized,
                    "Key rotation signature is not valid for the current key",
                )
            }

            val token = newToken()
            val expiresAt = now + tokenTtlMillis
            val invalidatedSessionIds = invalidateSessionsFor(request.relayDeviceId)
            devices[request.relayDeviceId.value] =
                current.copy(
                    publicKeyFingerprint = request.replacementIdentity.fingerprint,
                    authTokenHash = hashToken(token),
                    expiresAtEpochMillis = expiresAt,
                    lastSeenAtEpochMillis = now,
                    deviceId = request.replacementIdentity.deviceId,
                    signingPublicKey = request.replacementIdentity.signingPublicKey.copyOf(),
                    keyAlgorithm = request.replacementIdentity.keyAlgorithm,
                    keyGeneration = request.replacementIdentity.keyGeneration,
                    securityLevel = request.replacementIdentity.securityLevel.name,
                    retiredDeviceIds = (current.retiredDeviceIds + request.currentIdentity.deviceId).distinct(),
                    lastRotationOperationId = request.operationId,
                    lastRotationSourceDeviceId = request.currentIdentity.deviceId,
                )
            persist()
            RotateRelayDeviceKeyV2Response(
                relayDeviceId = request.relayDeviceId,
                identity = request.replacementIdentity.deepCopy(),
                authToken = token,
                expiresAtEpochMillis = expiresAt,
                invalidatedSessionIds = invalidatedSessionIds,
            )
        }
    }

    fun revokeV2(
        request: RevokeRelayDeviceV2Request,
        policy: RelayIdentityPolicy,
    ): RevokeRelayDeviceV2Response {
        requireSupportedProtocol(request.protocolVersion)
        validatePublicIdentity(request.identity)
        val now = clock()
        if (request.relayOrigin != policy.relayOrigin) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.RelayOriginMismatch,
                "Revocation was created for another relay origin",
            )
        }
        requireClockSkew(request.timestampEpochMillis, policy, now)

        return synchronized(stateLock) {
            val current =
                devices[request.relayDeviceId.value]
                    ?: throw RelayIdentityRejectedException(
                        RelayIdentityRejectionCode.UnknownRelayDevice,
                        "Relay device is not registered",
                    )
            if (!current.matchesIdentity(request.identity)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.RevocationUnauthorized,
                    "The current identity does not own this relay locator",
                )
            }
            val payload =
                RelayIdentityTranscript.revocation(
                    protocolVersion = request.protocolVersion,
                    relayOrigin = request.relayOrigin,
                    relayDeviceId = request.relayDeviceId,
                    identity = request.identity,
                    timestampEpochMillis = request.timestampEpochMillis,
                )
            if (!verifyIdentity(request.identity, payload, request.signature)) {
                throw RelayIdentityRejectedException(
                    RelayIdentityRejectionCode.RevocationUnauthorized,
                    "Revocation signature is not valid for the registered key",
                )
            }
            current.revokedAtEpochMillis?.let { revokedAt ->
                return@synchronized RevokeRelayDeviceV2Response(
                    relayDeviceId = request.relayDeviceId,
                    deviceId = request.identity.deviceId,
                    revokedAtEpochMillis = revokedAt,
                    invalidatedSessionIds = emptyList(),
                )
            }

            val invalidatedSessionIds = invalidateSessionsFor(request.relayDeviceId)
            devices[request.relayDeviceId.value] =
                current.copy(
                    remoteAccessEnabled = false,
                    expiresAtEpochMillis = now,
                    revokedAtEpochMillis = now,
                )
            persist()
            RevokeRelayDeviceV2Response(
                relayDeviceId = request.relayDeviceId,
                deviceId = request.identity.deviceId,
                revokedAtEpochMillis = now,
                invalidatedSessionIds = invalidatedSessionIds,
            )
        }
    }

    fun createSession(
        source: RelayDeviceId,
        target: RelayDeviceId,
    ): RelaySessionResponse? {
        val sourceDevice = devices[source.value] ?: return null
        val targetDevice = devices[target.value] ?: return null
        if (
            sourceDevice.v2IdentityOrNull() == null ||
            targetDevice.v2IdentityOrNull() == null ||
            sourceDevice.revokedAtEpochMillis != null ||
            targetDevice.revokedAtEpochMillis != null ||
            sourceDevice.expiresAtEpochMillis <= clock() ||
            targetDevice.expiresAtEpochMillis <= clock() ||
            !targetDevice.remoteAccessEnabled
        ) {
            return null
        }

        val relaySession =
            RelaySession(
                sessionId = SessionId("session-${UUID.randomUUID()}"),
                relayDeviceId = target,
                expiresAtEpochMillis = clock() + sessionTtlMillis,
            )
        sessions[relaySession.sessionId.value] =
            RelayRendezvousSession(
                session = relaySession,
                sourceRelayDeviceId = source,
                targetRelayDeviceId = target,
                approvedAtEpochMillis = null,
                rejectedAtEpochMillis = null,
            )
        persist()
        return RelaySessionResponse(
            session = relaySession,
            sourceRelayDeviceId = source,
            targetRelayDeviceId = target,
            sourceIdentity = sourceDevice.toIdentity(),
            targetIdentity = targetDevice.toIdentity(),
        )
    }

    fun getSession(sessionId: SessionId): RelayRendezvousSession? {
        val session = sessions[sessionId.value] ?: return null
        val sourceDevice = devices[session.sourceRelayDeviceId.value]
        val targetDevice = devices[session.targetRelayDeviceId.value]
        val sourceInvalid = sourceDevice?.v2IdentityOrNull() == null || sourceDevice.revokedAtEpochMillis != null
        val targetInvalid = targetDevice?.v2IdentityOrNull() == null || targetDevice.revokedAtEpochMillis != null
        return if (session.session.expiresAtEpochMillis > clock() && !sourceInvalid && !targetInvalid) {
            session
        } else {
            sessions.remove(sessionId.value)
            persist()
            null
        }
    }

    fun getApprovedSession(sessionId: SessionId): RelayRendezvousSession? {
        val session = getSession(sessionId) ?: return null
        return session.takeIf { it.approvedAtEpochMillis != null && it.rejectedAtEpochMillis == null }
    }

    fun cancelSession(sessionId: SessionId): Boolean =
        synchronized(stateLock) {
            val removed = sessions.remove(sessionId.value) != null
            if (removed) persist()
            removed
        }

    fun decideSession(
        sessionId: SessionId,
        target: RelayDeviceId,
        approved: Boolean,
    ): RelaySessionApproval? {
        val current = getSession(sessionId) ?: return null
        if (current.targetRelayDeviceId != target) return null
        val targetDevice = devices[target.value] ?: return null
        if (targetDevice.v2IdentityOrNull() == null || targetDevice.revokedAtEpochMillis != null) return null
        val decidedAt = clock()
        val updated =
            if (approved) {
                current.copy(approvedAtEpochMillis = decidedAt, rejectedAtEpochMillis = null)
            } else {
                current.copy(approvedAtEpochMillis = null, rejectedAtEpochMillis = decidedAt)
            }
        sessions[sessionId.value] = updated
        persist()
        return RelaySessionApproval(
            sessionId = sessionId,
            sourceRelayDeviceId = current.sourceRelayDeviceId,
            targetRelayDeviceId = current.targetRelayDeviceId,
            approved = approved,
            decidedAtEpochMillis = decidedAt,
            sourceIdentity = devices[current.sourceRelayDeviceId.value]?.toIdentity(),
            targetIdentity = devices[current.targetRelayDeviceId.value]?.toIdentity(),
        )
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun newNonce(): String {
        val bytes = ByteArray(CHALLENGE_NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun newRelayDeviceId(): RelayDeviceId {
        var candidate: RelayDeviceId
        do {
            candidate = RelayDeviceId("relay-${UUID.randomUUID()}")
        } while (devices.containsKey(candidate.value))
        return candidate
    }

    private fun invalidateSessionsFor(relayDeviceId: RelayDeviceId): List<SessionId> {
        val invalidated =
            sessions.values
                .filter { session ->
                    session.sourceRelayDeviceId == relayDeviceId || session.targetRelayDeviceId == relayDeviceId
                }.map { it.session.sessionId }
                .sortedBy { it.value }
        invalidated.forEach { sessionId -> sessions.remove(sessionId.value) }
        return invalidated
    }

    private fun isRetiredIdentity(deviceId: DeviceId): Boolean = devices.values.any { deviceId in it.retiredDeviceIds }

    private fun requireSupportedProtocol(protocolVersion: Int) {
        if (protocolVersion != RELAY_IDENTITY_PROTOCOL_VERSION) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.UnsupportedProtocolVersion,
                "Unsupported relay identity protocol version $protocolVersion",
            )
        }
    }

    private fun requireRotationOperationId(operationId: String) {
        val canonical = runCatching { UUID.fromString(operationId).toString() }.getOrNull()
        if (canonical != operationId) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.InvalidRotation,
                "Key rotation operation id is not a canonical UUID",
            )
        }
    }

    private fun requireClockSkew(
        timestampEpochMillis: Long,
        policy: RelayIdentityPolicy,
        now: Long,
    ) {
        if (abs(now - timestampEpochMillis) > policy.maxClockSkewMillis) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.ClockSkew,
                "Identity proof timestamp is outside the allowed clock skew",
            )
        }
    }

    private fun validatePublicIdentity(identity: DevicePublicIdentity) {
        val derivedDeviceId = deriveDeviceId(identity.algorithm, identity.publicKeySpki)
        if (identity.deviceId != derivedDeviceId) {
            throw RelayIdentityRejectedException(
                RelayIdentityRejectionCode.DeviceIdMismatch,
                "Device id does not match the signing public key",
            )
        }
        runCatching { identityPublicKey(identity) }
            .getOrElse { error ->
                val code =
                    if (error.message == ExactP256Curve.REJECTION_CODE) {
                        RelayIdentityRejectionCode.P256CurveSubstitution
                    } else {
                        RelayIdentityRejectionCode.InvalidIdentity
                    }
                throw RelayIdentityRejectedException(
                    code,
                    "Identity public key does not match its declared algorithm: ${error.message}",
                )
            }
    }

    private fun verifyIdentity(
        identity: DevicePublicIdentity,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean =
        runCatching {
            val signatureName =
                when (identity.algorithm) {
                    IdentitySignatureAlgorithm.ED25519 -> "Ed25519"
                    IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "SHA256withECDSA"
                }
            Signature.getInstance(signatureName).run {
                initVerify(identityPublicKey(identity))
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)

    private fun identityPublicKey(identity: DevicePublicIdentity) =
        when (identity.algorithm) {
            IdentitySignatureAlgorithm.ED25519 -> {
                KeyFactory
                    .getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(identity.publicKeySpki))
            }

            IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> {
                KeyFactory
                    .getInstance("EC")
                    .generatePublic(X509EncodedKeySpec(identity.publicKeySpki))
                    .also(ExactP256Curve::requireSecp256r1)
            }
        }

    private fun deriveDeviceId(
        algorithm: IdentitySignatureAlgorithm,
        publicKeySpki: ByteArray,
    ): DeviceId {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(
                DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, publicKeySpki),
            )
        return DeviceId(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    }

    private fun fingerprint(rawPublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawPublicKey)
        return "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun hashToken(token: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tokenHashSecret, "HmacSHA256"))
        return TOKEN_HMAC_PREFIX +
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    private fun tokenMatches(
        storedHash: String,
        token: String,
    ): Boolean {
        val expected =
            if (storedHash.startsWith(TOKEN_HMAC_PREFIX)) {
                hashToken(token)
            } else {
                legacyHashToken(token)
            }
        return constantTimeEquals(storedHash, expected)
    }

    private fun persist() {
        store?.save(
            RelayRegistrySnapshot(
                devices =
                    devices.values
                        .filter { it.expiresAtEpochMillis > clock() || it.revokedAtEpochMillis != null }
                        .sortedBy { it.relayDeviceId.value },
                sessions =
                    sessions.values
                        .filter { it.session.expiresAtEpochMillis > clock() }
                        .sortedBy { it.session.sessionId.value },
            ),
        )
    }

    private companion object {
        const val CHALLENGE_NONCE_BYTES = 32
        const val MIN_TOKEN_HASH_SECRET_BYTES = 32
        const val TOKEN_HMAC_PREFIX = "hmac-sha256:"
    }
}

interface RelayRegistryStore {
    fun load(nowEpochMillis: Long): RelayRegistrySnapshot?

    fun save(snapshot: RelayRegistrySnapshot)
}

class JsonRelayRegistryStore(
    private val path: Path,
    private val json: Json = relayJson,
) : RelayRegistryStore {
    override fun load(nowEpochMillis: Long): RelayRegistrySnapshot? {
        if (!Files.exists(path)) return null
        val snapshot = json.decodeFromString<RelayRegistrySnapshot>(Files.readString(path))
        return snapshot.copy(
            devices =
                snapshot.devices.filter {
                    it.expiresAtEpochMillis > nowEpochMillis || it.revokedAtEpochMillis != null
                },
            sessions = snapshot.sessions.filter { it.session.expiresAtEpochMillis > nowEpochMillis },
        )
    }

    override fun save(snapshot: RelayRegistrySnapshot) {
        val absolutePath = path.toAbsolutePath()
        val parent = absolutePath.parent
        parent?.let { Files.createDirectories(it) }
        val temp =
            if (parent != null) {
                Files.createTempFile(parent, "${absolutePath.fileName}.", ".tmp")
            } else {
                Files.createTempFile("${absolutePath.fileName}.", ".tmp")
            }
        Files.writeString(temp, json.encodeToString(snapshot))
        try {
            Files.move(temp, absolutePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (atomicMoveUnsupported: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, absolutePath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

@Serializable
data class RelayRegistrySnapshot(
    val devices: List<RegisteredRelayDevice> = emptyList(),
    val sessions: List<RelayRendezvousSession> = emptyList(),
)

private fun legacyHashToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun constantTimeEquals(
    left: String,
    right: String,
): Boolean {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(leftBytes, rightBytes)
}

@Serializable
data class RegisteredRelayDevice(
    val relayDeviceId: RelayDeviceId,
    val displayName: String,
    val publicKeyFingerprint: String,
    val remoteAccessEnabled: Boolean,
    val authTokenHash: String,
    val expiresAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    val deviceId: DeviceId? = null,
    val signingPublicKey: ByteArray? = null,
    val keyAlgorithm: String? = null,
    val keyGeneration: Long? = null,
    val securityLevel: String? = null,
    val retiredDeviceIds: List<DeviceId> = emptyList(),
    val lastRotationOperationId: String? = null,
    val lastRotationSourceDeviceId: DeviceId? = null,
    val revokedAtEpochMillis: Long? = null,
)

fun RegisteredRelayDevice.toIdentity(): RelayDeviceIdentity =
    RelayDeviceIdentity(
        relayDeviceId = relayDeviceId,
        displayName = displayName,
        publicKeyFingerprint = publicKeyFingerprint,
        publicIdentity = v2IdentityOrNull(),
    )

private fun RegisteredRelayDevice.v2IdentityOrNull(): DevicePublicIdentity? {
    val id = deviceId ?: return null
    val publicKey = signingPublicKey ?: return null
    val algorithm = keyAlgorithm ?: return null
    val generation = keyGeneration ?: return null
    val level = securityLevel?.let(IdentitySecurityLevel::valueOf) ?: IdentitySecurityLevel.OS_KEYSTORE
    return DevicePublicIdentity(
        deviceId = id,
        algorithm = IdentitySignatureAlgorithm.fromWireId(algorithm),
        publicKeySpki = publicKey.copyOf(),
        fingerprint = publicKeyFingerprint,
        keyGeneration = generation,
        securityLevel = level,
    )
}

private fun RegisteredRelayDevice.matchesIdentity(identity: DevicePublicIdentity): Boolean = v2IdentityOrNull()?.matches(identity) == true

private fun DevicePublicIdentity.deepCopy(): DevicePublicIdentity =
    copy(
        publicKeySpki = publicKeySpki.copyOf(),
    )

@Serializable
data class PendingRelayRegistrationChallenge(
    val nonce: String,
    val identity: DevicePublicIdentity,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val relayOrigin: String,
)

data class RelayIdentityPolicy(
    val relayOrigin: String = "aegis-relay",
    val challengeTtlMillis: Long = 60_000L,
    val maxClockSkewMillis: Long = 30_000L,
) {
    init {
        require(relayOrigin.isNotBlank()) { "Relay origin must not be blank" }
        require(challengeTtlMillis > 0) { "Challenge TTL must be positive" }
        require(maxClockSkewMillis >= 0) { "Clock skew tolerance must not be negative" }
    }
}

enum class RelayIdentityRejectionCode {
    UnsupportedProtocolVersion,
    InvalidIdentity,
    P256CurveSubstitution,
    DeviceIdMismatch,
    IdentityKeyMismatch,
    IdentityRevoked,
    ChallengeMissingOrReused,
    ChallengeExpired,
    ChallengeIdentityMismatch,
    RelayOriginMismatch,
    ClockSkew,
    InvalidProof,
    UnknownRelayDevice,
    RotationUnauthorized,
    InvalidRotation,
    RevocationUnauthorized,
}

class RelayIdentityRejectedException(
    val code: RelayIdentityRejectionCode,
    override val message: String,
) : IllegalArgumentException(message)

@Serializable
data class RelayRendezvousSession(
    val session: RelaySession,
    val sourceRelayDeviceId: RelayDeviceId,
    val targetRelayDeviceId: RelayDeviceId,
    val approvedAtEpochMillis: Long?,
    val rejectedAtEpochMillis: Long?,
)
