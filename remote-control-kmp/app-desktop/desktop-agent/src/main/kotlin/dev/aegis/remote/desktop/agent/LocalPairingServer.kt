package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.pairing.LanAuthorizationStatus
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolTextTransport
import java.net.URI

interface LocalPairingServer {
    suspend fun start(
        onPairingRequest: suspend (DesktopPairingRequest) -> PairingRequestDispatchResult,
        onProtocolChannel: suspend (LocalProtocolChannelRequest) -> Unit = {},
        onPairingEvent: (PairingServerEvent) -> Unit = {},
    ): LocalPairingSession

    /** Rotates only the short-lived QR bootstrap values without interrupting active protocol channels. */
    suspend fun refreshPairingSession(): LocalPairingSession

    fun approve(
        requestId: String,
        profile: LocalPairedProfile,
    )

    /**
     * Keeps a pending request alive after the operator clicks Approve so OpenSSH
     * enrollment can finish without the phone seeing Expired on the next poll.
     */
    fun holdPendingApproval(
        requestId: String,
        extraMillis: Long = DEFAULT_PENDING_APPROVAL_HOLD_MILLIS,
    ) {
    }

    fun reject(requestId: String)

    fun stop()

    /**
     * Supplies the current trust status for a paired Android device id so LAN
     * rediscovery can drop a revoked or deleted link without opening a session.
     */
    fun bindDeviceAuthorizationLookup(lookup: suspend (String) -> LanAuthorizationStatus) {}

    fun bindDevicePermissionsLookup(lookup: suspend (String) -> dev.aegis.remote.core.model.DevicePermissions?) {}

    companion object {
        const val DEFAULT_PENDING_APPROVAL_HOLD_MILLIS = 180_000L
    }
}

sealed interface PairingRequestDispatchResult {
    data object Accepted : PairingRequestDispatchResult

    data object AgentUnavailable : PairingRequestDispatchResult
}

enum class PairingServerEventStage {
    PAIRING_HTTP_REQUEST_RECEIVED,
    PAIRING_REQUEST_VALIDATED,
    PAIRING_REQUEST_DISPATCHED,
    PAIRING_REQUEST_VISIBLE,
    PAIRING_REQUEST_DISPATCH_REJECTED,
    PAIRING_STATUS_POLLED,
}

/** Sanitized local-pairing telemetry. It must never contain request secrets or payloads. */
data class PairingServerEvent(
    val requestId: String?,
    val stage: PairingServerEventStage,
    val result: String,
    val remoteHost: String,
    val lifecycleGeneration: Long?,
    val pendingCount: Int,
    val latencyMillis: Long,
)

data class LocalProtocolChannelRequest(
    val sessionId: SessionId,
    val authorizedDeviceId: String,
    val sessionProof: String?,
    val proofTimestampEpochMillis: Long?,
    val channel: ProtocolMessageChannel,
    /** Raw WebSocket transport used exclusively for the LAN E2EE handshake and envelopes. */
    val transport: ProtocolTextTransport? = null,
    /** Persistent host identity that issued the signed pairing QR. */
    val hostIdentity: LocalDeviceIdentity? = null,
)

data class LocalPairingSession(
    val host: String,
    val port: Int,
    val pairingCode: String,
    val agentFingerprint: String,
    val pairingSecret: String,
    val tokenId: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val hostIdentity: DevicePublicIdentity,
    val hostSignature: String,
    val alternativeHosts: List<String> = emptyList(),
    val secure: Boolean = true,
) {
    val url: String = localPairingBaseUrl(host, port, secure)
    val hosts: List<String> =
        (listOf(host) + alternativeHosts)
            .map { it.trim().removePrefix("[").removeSuffix("]") }
            .filter(String::isNotBlank)
            .distinct()
    val urls: List<String> = hosts.map { localPairingBaseUrl(it, port, secure) }
}

internal fun localPairingBaseUrl(
    host: String,
    port: Int,
    secure: Boolean = true,
): String {
    require(port in 1..65_535) { "Local pairing port must be between 1 and 65535" }
    val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
    require(normalizedHost.isNotBlank()) { "Local pairing host must not be blank" }
    return URI(if (secure) "https" else "http", null, normalizedHost, port, null, null, null).toASCIIString()
}
