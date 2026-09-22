package dev.aegis.remote.core.pairing

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.model.PairingToken
import dev.aegis.remote.core.model.WakeOnLanConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface PairingService {
    val states: Flow<PairingState>

    suspend fun startLocalPairing(): PairingToken

    suspend fun approve(request: PairingRequest): DeviceAuthorization

    suspend fun reject(requestId: String)
}

interface LocalPairingClient {
    fun pinServerFingerprint(fingerprint: String): LocalPairingClient = this

    suspend fun fetchPayload(pairingUrl: String): LocalPairingPayload

    suspend fun requestPairing(
        pairingUrl: String,
        request: LocalPairingRequestBody,
    ): LocalPairingResponse

    suspend fun getPairingStatus(
        pairingUrl: String,
        requestId: String,
    ): LocalPairingStatusResponse
}

interface RemotePairingService {
    val states: Flow<PairingState>

    suspend fun requestRemotePairing(
        relayId: String,
        phoneName: String,
    ): PairingRequest

    suspend fun approveRemote(request: PairingRequest): DeviceAuthorization
}

interface DeviceTrustStore {
    suspend fun saveAuthorization(authorization: DeviceAuthorization)

    suspend fun revoke(remoteDeviceId: String)

    suspend fun listAuthorizedDevices(): List<DeviceAuthorization>
}

@Serializable
data class PairingRequest(
    val requestId: String,
    val deviceName: String,
    val fingerprint: String,
    val requestedAtEpochMillis: Long,
    val remote: Boolean,
)

@Serializable
data class LocalPairingPayload(
    val host: String,
    val port: Int,
    val pairingCode: String,
    val agentFingerprint: String,
    val timestampEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nonce: String,
)

@Serializable
data class LocalPairingQrPayload(
    val version: Int = 3,
    val pairingUrl: String,
    val pairingUrls: List<String> = emptyList(),
    val pairingCode: String,
    /** SHA-256 pin of the persistent local TLS certificate. */
    val agentFingerprint: String,
    /** Short-lived bootstrap secret. It is never persisted as a long-term credential. */
    val pairingSecret: String,
    val tokenId: String = "",
    val issuedAtEpochMillis: Long = 0,
    val expiresAtEpochMillis: Long = 0,
    val hostIdentity: DevicePublicIdentity? = null,
    /** Base64URL signature over [localPairingQrSignaturePayload]. */
    val signature: String = "",
) {
    fun candidatePairingUrls(): List<String> =
        (listOf(pairingUrl) + pairingUrls)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
}

class LocalPairingQrPayloadParser(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    fun parse(rawPayload: String): LocalPairingQrPayload {
        val payload = json.decodeFromString(LocalPairingQrPayload.serializer(), rawPayload.trim())
        require(payload.version in 1..3) { "Unsupported local pairing QR payload version ${payload.version}" }
        val pairingUrls = payload.candidatePairingUrls()
        require(pairingUrls.isNotEmpty()) { "Local pairing QR payload must include an HTTP pairing URL" }
        require(pairingUrls.size <= MAX_PAIRING_URLS) { "Local pairing QR payload includes too many pairing URLs" }
        require(pairingUrls.all { it.startsWith("http://") || it.startsWith("https://") }) {
            "Every local pairing QR endpoint must use HTTP or HTTPS"
        }
        if (payload.version == 2) {
            require(payload.pairingUrls.isNotEmpty()) {
                "Local pairing QR payload v2 must include pairingUrls"
            }
        }
        if (payload.version >= 3) {
            require(payload.pairingUrls.isNotEmpty()) { "Local pairing QR payload v3 must include pairingUrls" }
            require(payload.tokenId.matches(QR_TOKEN_ID_PATTERN)) { "Local pairing QR payload has an invalid token id" }
            require(payload.issuedAtEpochMillis > 0) { "Local pairing QR payload is missing its issue time" }
            require(payload.expiresAtEpochMillis > payload.issuedAtEpochMillis) {
                "Local pairing QR payload has an invalid expiry"
            }
            require(payload.expiresAtEpochMillis - payload.issuedAtEpochMillis <= MAX_QR_TOKEN_TTL_MILLIS) {
                "Local pairing QR token lifetime exceeds the allowed maximum"
            }
            requireNotNull(payload.hostIdentity) { "Local pairing QR payload is missing the host identity" }
            require(payload.signature.length in 40..512) { "Local pairing QR payload is missing its host signature" }
        }
        require(payload.pairingCode.isNotBlank()) { "Local pairing QR payload is missing the pairing code" }
        require(payload.agentFingerprint.isNotBlank()) { "Local pairing QR payload is missing the agent fingerprint" }
        require(payload.pairingSecret.length >= 32) { "Local pairing QR payload is missing a secure pairing secret" }
        return payload
    }

    private companion object {
        const val MAX_PAIRING_URLS = 8
        const val MAX_QR_TOKEN_TTL_MILLIS = 120_000L
        val QR_TOKEN_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,128}")
    }
}

@Serializable
data class LocalPairingRequestBody(
    val pairingCode: String,
    val challengeNonce: String,
    val challengeProof: String,
    val deviceName: String,
    val deviceFingerprint: String,
    val qrTokenId: String = "",
    val deviceIdentity: DevicePublicIdentity? = null,
    /** Base64URL proof-of-possession over [localPairingDeviceProofPayload]. */
    val deviceIdentitySignature: String = "",
    /** OpenSSH authorized_keys line. No private SSH material is sent to Windows. */
    val sshPublicKey: String = "",
)

@Serializable
data class LocalPairingResponse(
    val status: LocalPairingRequestStatus,
    val message: String,
    val requestId: String? = null,
)

@Serializable
data class LocalPairingStatusResponse(
    val status: LocalPairingRequestStatus,
    val message: String,
    val profile: LocalPairedProfile? = null,
)

@Serializable
data class LocalPairedProfile(
    val displayName: String,
    val localHost: String,
    val sshPort: Int,
    val agentFingerprint: String,
    val authorizedDeviceId: String,
    val permissions: DevicePermissions = DevicePermissions(),
    val username: String = "",
    val sshHostKeyFingerprint: HostKeyFingerprint? = null,
    val wakeOnLanAdapters: List<WakeOnLanConfig> = emptyList(),
    val hostIdentity: DevicePublicIdentity? = null,
)

fun localPairingProofPayload(
    nonce: String,
    deviceName: String,
    deviceFingerprint: String,
    qrTokenId: String = "",
    sshPublicKey: String = "",
    deviceIdentity: DevicePublicIdentity? = null,
): String =
    buildString {
        append("pairing-v3\n")
        append(nonce).append('\n')
        append(deviceName).append('\n')
        append(deviceFingerprint).append('\n')
        append(qrTokenId).append('\n')
        append(sshPublicKey).append('\n')
        append(deviceIdentity?.deviceId?.value.orEmpty()).append('\n')
        append(deviceIdentity?.algorithm?.wireId.orEmpty()).append('\n')
        append(deviceIdentity?.keyGeneration ?: 0L)
    }

/** Canonical, length-prefixed transcript signed by the persistent Windows host identity. */
fun localPairingQrSignaturePayload(payload: LocalPairingQrPayload): ByteArray =
    canonicalPairingBytes(
        "aegis-local-pairing-qr-v3".encodeToByteArray(),
        payload.version.toString().encodeToByteArray(),
        payload.pairingUrl.encodeToByteArray(),
        payload.candidatePairingUrls().joinToString("\n").encodeToByteArray(),
        payload.pairingCode.encodeToByteArray(),
        payload.agentFingerprint.encodeToByteArray(),
        payload.pairingSecret.encodeToByteArray(),
        payload.tokenId.encodeToByteArray(),
        payload.issuedAtEpochMillis.toString().encodeToByteArray(),
        payload.expiresAtEpochMillis.toString().encodeToByteArray(),
        payload.hostIdentity
            ?.deviceId
            ?.value
            .orEmpty()
            .encodeToByteArray(),
        payload.hostIdentity
            ?.algorithm
            ?.wireId
            .orEmpty()
            .encodeToByteArray(),
        payload.hostIdentity?.publicKeySpki ?: byteArrayOf(),
        payload.hostIdentity
            ?.fingerprint
            .orEmpty()
            .encodeToByteArray(),
        (payload.hostIdentity?.keyGeneration ?: 0L).toString().encodeToByteArray(),
    )

/** Canonical proof that the Android device controls the identity sent with the request. */
fun localPairingDeviceProofPayload(request: LocalPairingRequestBody): ByteArray =
    canonicalPairingBytes(
        "aegis-local-pairing-device-pop-v1".encodeToByteArray(),
        request.pairingCode.encodeToByteArray(),
        request.challengeNonce.encodeToByteArray(),
        request.deviceName.encodeToByteArray(),
        request.deviceFingerprint.encodeToByteArray(),
        request.qrTokenId.encodeToByteArray(),
        request.sshPublicKey.encodeToByteArray(),
        request.deviceIdentity
            ?.deviceId
            ?.value
            .orEmpty()
            .encodeToByteArray(),
        request.deviceIdentity
            ?.algorithm
            ?.wireId
            .orEmpty()
            .encodeToByteArray(),
        request.deviceIdentity?.publicKeySpki ?: byteArrayOf(),
        (request.deviceIdentity?.keyGeneration ?: 0L).toString().encodeToByteArray(),
    )

private fun canonicalPairingBytes(vararg fields: ByteArray): ByteArray {
    val size = fields.sumOf { 4 + it.size }
    val output = ByteArray(size)
    var offset = 0
    fields.forEach { field ->
        val length = field.size
        output[offset++] = (length ushr 24).toByte()
        output[offset++] = (length ushr 16).toByte()
        output[offset++] = (length ushr 8).toByte()
        output[offset++] = length.toByte()
        field.copyInto(output, offset)
        offset += length
    }
    return output
}

fun localProtocolTokenPayload(requestId: String): String = "local-protocol-v1\n$requestId"

fun localProtocolSessionProofPayload(
    sessionId: String,
    deviceId: String,
    timestampEpochMillis: Long,
): String = listOf("local-protocol-session-v1", sessionId, deviceId, timestampEpochMillis.toString()).joinToString("\n")

@Serializable
enum class LocalPairingRequestStatus {
    Pending,
    Approved,
    Rejected,
    Expired,
    Unknown,
}

@Serializable
sealed interface PairingState {
    @Serializable data object Idle : PairingState

    @Serializable data object ShowingCode : PairingState

    @Serializable data class AwaitingApproval(
        val request: PairingRequest,
    ) : PairingState

    @Serializable data class Approved(
        val authorization: DeviceAuthorization,
    ) : PairingState

    @Serializable data object Rejected : PairingState

    @Serializable data class Failed(
        val reason: String,
    ) : PairingState
}

@Serializable
sealed interface PairingEvent {
    @Serializable data object StartLocal : PairingEvent

    @Serializable data object StartRemote : PairingEvent

    @Serializable data class RequestReceived(
        val request: PairingRequest,
    ) : PairingEvent

    @Serializable data class Approve(
        val authorization: DeviceAuthorization,
    ) : PairingEvent

    @Serializable data object Reject : PairingEvent

    @Serializable data class Fail(
        val reason: String,
    ) : PairingEvent
}

class PairingStateMachine {
    fun reduce(
        state: PairingState,
        event: PairingEvent,
    ): PairingState =
        when (state) {
            PairingState.Idle -> {
                when (event) {
                    PairingEvent.StartLocal -> PairingState.ShowingCode
                    PairingEvent.StartRemote -> PairingState.ShowingCode
                    is PairingEvent.Fail -> PairingState.Failed(event.reason)
                    else -> state
                }
            }

            PairingState.ShowingCode -> {
                when (event) {
                    is PairingEvent.RequestReceived -> PairingState.AwaitingApproval(event.request)
                    is PairingEvent.Fail -> PairingState.Failed(event.reason)
                    else -> state
                }
            }

            is PairingState.AwaitingApproval -> {
                when (event) {
                    is PairingEvent.Approve -> PairingState.Approved(event.authorization)
                    PairingEvent.Reject -> PairingState.Rejected
                    is PairingEvent.Fail -> PairingState.Failed(event.reason)
                    else -> state
                }
            }

            is PairingState.Approved,
            PairingState.Rejected,
            is PairingState.Failed,
            -> {
                when (event) {
                    PairingEvent.StartLocal -> PairingState.ShowingCode
                    PairingEvent.StartRemote -> PairingState.ShowingCode
                    else -> state
                }
            }
        }
}
