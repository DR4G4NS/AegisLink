package dev.aegis.remote.core.relay

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

const val RELAY_IDENTITY_PROTOCOL_VERSION: Int = 3

interface RelayClient {
    val states: Flow<RelayConnectionState>

    suspend fun connect()

    suspend fun registerDevice(registration: RelayDeviceRegistration): RelayRegistration

    suspend fun createSession(targetRelayDeviceId: RelayDeviceId): RelaySession

    suspend fun approveSession(
        sessionId: SessionId,
        approved: Boolean,
    ): RelaySessionApproval

    suspend fun requestTurnCredentials(): RelayTurnCredentials

    suspend fun openSignalingChannel(sessionId: SessionId): RelaySignalingChannel

    suspend fun openOpaqueChannel(sessionId: SessionId): RelayOpaqueChannel = error("Opaque E2EE relay channel is not implemented by this client")

    suspend fun openDeviceEvents(): RelayDeviceEventChannel

    suspend fun close()
}

interface RelaySignalingChannel {
    val incoming: Flow<SignalingMessage>

    suspend fun send(message: SignalingMessage)

    suspend fun close()
}

interface RelayOpaqueChannel {
    val incoming: Flow<RelayOpaqueFrame>

    suspend fun send(frame: RelayOpaqueFrame)

    suspend fun close()
}

@Serializable
data class RelayOpaqueFrame(
    val kind: RelayPayloadKind,
    val payloadJson: String,
) {
    init {
        require(kind != RelayPayloadKind.LEGACY_PLAINTEXT)
        require(payloadJson.isNotBlank())
    }
}

interface RelayDeviceEventChannel {
    val incoming: Flow<RelayDeviceEvent>

    suspend fun close()
}

@Serializable
data class RelayDeviceRegistration(
    val relayDeviceId: RelayDeviceId?,
    val displayName: String,
    val publicKeyFingerprint: String,
    val remoteAccessEnabled: Boolean,
)

@Serializable
data class RelayDeviceIdentity(
    val relayDeviceId: RelayDeviceId,
    val displayName: String,
    /** Legacy display-only value; v2 authorization must use [publicIdentity]. */
    val publicKeyFingerprint: String,
    val publicIdentity: DevicePublicIdentity? = null,
)

@Serializable
data class RelayDeviceChallengeRequest(
    val protocolVersion: Int = RELAY_IDENTITY_PROTOCOL_VERSION,
    val identity: DevicePublicIdentity,
)

@Serializable
data class RelayDeviceChallengeResponse(
    val protocolVersion: Int = RELAY_IDENTITY_PROTOCOL_VERSION,
    val nonce: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val relayOrigin: String,
)

/** The registration proof uses the algorithm declared by [identity]. */
@Serializable
data class RegisterRelayDeviceV2Request(
    val protocolVersion: Int = RELAY_IDENTITY_PROTOCOL_VERSION,
    val identity: DevicePublicIdentity,
    val challengeNonce: String,
    val relayOrigin: String,
    val timestampEpochMillis: Long,
    val signature: ByteArray,
    val displayName: String,
    val remoteAccessEnabled: Boolean,
)

@Serializable
data class RegisterRelayDeviceV2Response(
    val relayDeviceId: RelayDeviceId,
    val identity: DevicePublicIdentity,
    val authToken: String,
    val expiresAtEpochMillis: Long,
)

/**
 * A key rotation is authorized by the current identity key. [operationId] is
 * signed into the transcript so a response-loss retry is idempotent without
 * weakening proof-of-possession.
 */
@Serializable
data class RotateRelayDeviceKeyV2Request(
    val protocolVersion: Int = RELAY_IDENTITY_PROTOCOL_VERSION,
    val operationId: String,
    val relayDeviceId: RelayDeviceId,
    val currentIdentity: DevicePublicIdentity,
    val replacementIdentity: DevicePublicIdentity,
    val relayOrigin: String,
    val timestampEpochMillis: Long,
    val signature: ByteArray,
)

@Serializable
data class RotateRelayDeviceKeyV2Response(
    val relayDeviceId: RelayDeviceId,
    val identity: DevicePublicIdentity,
    val authToken: String,
    val expiresAtEpochMillis: Long,
    val invalidatedSessionIds: List<SessionId> = emptyList(),
)

@Serializable
data class RevokeRelayDeviceV2Request(
    val protocolVersion: Int = RELAY_IDENTITY_PROTOCOL_VERSION,
    val relayDeviceId: RelayDeviceId,
    val identity: DevicePublicIdentity,
    val relayOrigin: String,
    val timestampEpochMillis: Long,
    val signature: ByteArray,
)

@Serializable
data class RevokeRelayDeviceV2Response(
    val relayDeviceId: RelayDeviceId,
    val deviceId: DeviceId,
    val revokedAtEpochMillis: Long,
    val invalidatedSessionIds: List<SessionId> = emptyList(),
)

/**
 * Canonical, length-prefixed signing payloads for the relay identity protocol.
 * Keeping this in common code prevents platform clients from inventing subtly
 * different string concatenation rules.
 */
object RelayIdentityTranscript {
    fun registration(
        protocolVersion: Int,
        relayOrigin: String,
        identity: DevicePublicIdentity,
        challengeNonce: String,
        timestampEpochMillis: Long,
    ): ByteArray =
        transcript(
            domain = "aegis-relay-registration-v3",
            intBytes(protocolVersion),
            relayOrigin.encodeToByteArray(),
            identity.deviceId.value.encodeToByteArray(),
            identity.algorithm.wireId.encodeToByteArray(),
            identity.publicKeySpki,
            identity.securityLevel.name.encodeToByteArray(),
            challengeNonce.encodeToByteArray(),
            longBytes(timestampEpochMillis),
            longBytes(identity.keyGeneration),
        )

    fun rotation(
        protocolVersion: Int,
        operationId: String,
        relayOrigin: String,
        relayDeviceId: RelayDeviceId,
        currentIdentity: DevicePublicIdentity,
        replacementIdentity: DevicePublicIdentity,
        timestampEpochMillis: Long,
    ): ByteArray =
        transcript(
            domain = "aegis-relay-key-rotation-v3",
            intBytes(protocolVersion),
            operationId.encodeToByteArray(),
            relayOrigin.encodeToByteArray(),
            relayDeviceId.value.encodeToByteArray(),
            currentIdentity.deviceId.value.encodeToByteArray(),
            currentIdentity.algorithm.wireId.encodeToByteArray(),
            currentIdentity.publicKeySpki,
            currentIdentity.securityLevel.name.encodeToByteArray(),
            longBytes(currentIdentity.keyGeneration),
            replacementIdentity.deviceId.value.encodeToByteArray(),
            replacementIdentity.algorithm.wireId.encodeToByteArray(),
            replacementIdentity.publicKeySpki,
            replacementIdentity.securityLevel.name.encodeToByteArray(),
            longBytes(replacementIdentity.keyGeneration),
            longBytes(timestampEpochMillis),
        )

    fun revocation(
        protocolVersion: Int,
        relayOrigin: String,
        relayDeviceId: RelayDeviceId,
        identity: DevicePublicIdentity,
        timestampEpochMillis: Long,
    ): ByteArray =
        transcript(
            domain = "aegis-relay-revocation-v3",
            intBytes(protocolVersion),
            relayOrigin.encodeToByteArray(),
            relayDeviceId.value.encodeToByteArray(),
            identity.deviceId.value.encodeToByteArray(),
            identity.algorithm.wireId.encodeToByteArray(),
            identity.publicKeySpki,
            identity.securityLevel.name.encodeToByteArray(),
            longBytes(identity.keyGeneration),
            longBytes(timestampEpochMillis),
        )

    private fun transcript(
        domain: String,
        vararg fields: ByteArray,
    ): ByteArray {
        val values = arrayOf(domain.encodeToByteArray(), *fields)
        val totalLength = values.sumOf { field -> Int.SIZE_BYTES + field.size }
        val output = ByteArray(totalLength)
        var offset = 0
        values.forEach { field ->
            val length = field.size
            output[offset++] = (length ushr 24).toByte()
            output[offset++] = (length ushr 16).toByte()
            output[offset++] = (length ushr 8).toByte()
            output[offset++] = length.toByte()
            field.copyInto(output, destinationOffset = offset)
            offset += field.size
        }
        return output
    }

    private fun intBytes(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun longBytes(value: Long): ByteArray =
        byteArrayOf(
            (value ushr 56).toByte(),
            (value ushr 48).toByte(),
            (value ushr 40).toByte(),
            (value ushr 32).toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
}

@Serializable
data class RelaySession(
    val sessionId: SessionId,
    val relayDeviceId: RelayDeviceId,
    val expiresAtEpochMillis: Long,
    val targetIdentity: DevicePublicIdentity? = null,
)

@Serializable
data class RelayRegistration(
    val relayDeviceId: RelayDeviceId,
    val authToken: RelayAuthToken,
)

@Serializable
data class RegisterRelayDeviceRequest(
    val registration: RelayDeviceRegistration,
)

@Serializable
data class RegisterRelayDeviceResponse(
    val relayDeviceId: RelayDeviceId,
    val authToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class CreateRelaySessionRequest(
    val sourceRelayDeviceId: RelayDeviceId,
    val targetRelayDeviceId: RelayDeviceId,
)

@Serializable
data class RelaySessionResponse(
    val session: RelaySession,
    val sourceRelayDeviceId: RelayDeviceId,
    val targetRelayDeviceId: RelayDeviceId,
    val sourceIdentity: RelayDeviceIdentity? = null,
    val targetIdentity: RelayDeviceIdentity? = null,
)

@Serializable
data class RelaySessionApprovalRequest(
    val approved: Boolean,
)

@Serializable
data class RelayTurnCredentials(
    val urls: List<String>,
    val username: String,
    val credential: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class RelaySessionApproval(
    val sessionId: SessionId,
    val sourceRelayDeviceId: RelayDeviceId,
    val targetRelayDeviceId: RelayDeviceId,
    val approved: Boolean,
    val decidedAtEpochMillis: Long,
    val sourceIdentity: RelayDeviceIdentity? = null,
    val targetIdentity: RelayDeviceIdentity? = null,
)

@Serializable
enum class RelayPayloadKind {
    E2EE_HANDSHAKE,
    E2EE_ENVELOPE,
    LEGACY_PLAINTEXT,
}

@Serializable
data class RelayWebSocketEnvelope(
    val protocolVersion: Int = 1,
    val sessionId: SessionId,
    val senderRelayDeviceId: RelayDeviceId,
    val payloadKind: RelayPayloadKind = RelayPayloadKind.LEGACY_PLAINTEXT,
    val payloadJson: String,
)

@Serializable
sealed interface RelayDeviceEvent {
    @Serializable
    data class SessionRequested(
        val sessionId: SessionId,
        val sourceRelayDeviceId: RelayDeviceId,
        val targetRelayDeviceId: RelayDeviceId,
        val expiresAtEpochMillis: Long,
        val sourceIdentity: RelayDeviceIdentity? = null,
    ) : RelayDeviceEvent

    @Serializable
    data class SessionApproved(
        val sessionId: SessionId,
        val sourceRelayDeviceId: RelayDeviceId,
        val targetRelayDeviceId: RelayDeviceId,
        val decidedAtEpochMillis: Long,
        val targetIdentity: RelayDeviceIdentity? = null,
    ) : RelayDeviceEvent

    @Serializable
    data class SessionRejected(
        val sessionId: SessionId,
        val sourceRelayDeviceId: RelayDeviceId,
        val targetRelayDeviceId: RelayDeviceId,
        val decidedAtEpochMillis: Long,
        val targetIdentity: RelayDeviceIdentity? = null,
    ) : RelayDeviceEvent
}

@Serializable
sealed interface RelayConnectionState {
    @Serializable data object Disconnected : RelayConnectionState

    @Serializable data object Connecting : RelayConnectionState

    @Serializable data object Connected : RelayConnectionState

    @Serializable data class Failed(
        val reason: String,
    ) : RelayConnectionState
}

@Serializable
data class RelayAuthToken(
    val tokenRef: String,
    val expiresAtEpochMillis: Long,
)
