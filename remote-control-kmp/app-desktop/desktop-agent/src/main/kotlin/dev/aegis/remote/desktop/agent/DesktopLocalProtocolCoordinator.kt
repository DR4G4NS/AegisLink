package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.TurnConfig
import dev.aegis.remote.core.pairing.localProtocolSessionProofPayload
import dev.aegis.remote.desktop.webrtc.DesktopProtocolChannelBridge
import dev.aegis.remote.desktop.webrtc.DesktopProtocolTelemetryBridge
import dev.aegis.remote.desktop.webrtc.DesktopResolvedTurnCredentials
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.openLanE2eeProtocolMessageChannel
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal data class DesktopLocalProtocolDependencies(
    val trustStore: dev.aegis.remote.core.pairing.DeviceTrustStore,
    val localProtocolAuthenticator: LocalProtocolAuthenticator,
    val clock: () -> Long,
    val localProtocolDeviceByKey: ConcurrentHashMap<String, String>,
    val authenticatedSessionIds: ConcurrentHashMap<String, Long>,
    val activeSessionIds: ConcurrentHashMap<String, String>,
    val relayProtocolBridges: ConcurrentHashMap<String, DesktopProtocolChannelBridge>,
    val relayTelemetryBridges: ConcurrentHashMap<String, DesktopProtocolTelemetryBridge>,
    val videoStateJobs: ConcurrentHashMap<String, Job>,
    val isGenerationActive: (Long) -> Boolean,
    val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
    val openProtocolChannel:
        suspend (
            SessionId,
            DevicePermissions,
            ProtocolMessageChannel,
            String,
            String,
            suspend (TurnConfig) -> DesktopResolvedTurnCredentials?,
        ) -> Job,
)

internal class DesktopLocalProtocolCoordinator(
    private val dependencies: DesktopLocalProtocolDependencies,
) {
    private val trustStore = dependencies.trustStore
    private val localProtocolAuthenticator = dependencies.localProtocolAuthenticator
    private val clock = dependencies.clock
    private val localProtocolDeviceByKey = dependencies.localProtocolDeviceByKey
    private val authenticatedSessionIds = dependencies.authenticatedSessionIds
    private val activeSessionIds = dependencies.activeSessionIds
    private val relayProtocolBridges = dependencies.relayProtocolBridges
    private val relayTelemetryBridges = dependencies.relayTelemetryBridges
    private val videoStateJobs = dependencies.videoStateJobs
    private val isGenerationActive = dependencies.isGenerationActive
    private val updateState = dependencies.updateState
    private val openProtocolChannel = dependencies.openProtocolChannel

    suspend fun handle(
        generation: Long,
        request: LocalProtocolChannelRequest,
    ) {
        if (!isGenerationActive(generation)) {
            closeQuietly(request.channel)
            return
        }
        val authorizationResult = authorize(request)
        val authorization = authorizationResult.authorization
        if (authorization == null) {
            rejectUnauthorized(request, authorizationResult.reason, authorizationResult.clockSkewMillis)
            return
        }
        if (!reserveSession(request, authorization)) return
        val key = "local:${request.sessionId.value}"
        var completed = false
        try {
            val localIdentity = requireNotNull(request.hostIdentity) { "LAN_HOST_IDENTITY_UNAVAILABLE" }
            val peerIdentity = requireNotNull(authorization.publicIdentity) { "LAN_PEER_IDENTITY_UNAVAILABLE" }
            val transport = requireNotNull(request.transport) { "LAN_E2EE_TRANSPORT_UNAVAILABLE" }
            val secureChannel =
                openLanE2eeProtocolMessageChannel(
                    transport = transport,
                    sessionId = request.sessionId.value,
                    localIdentity = localIdentity,
                    expectedPeerIdentity = peerIdentity,
                    localIsSource = false,
                    nowEpochMillis = clock(),
                )
            localProtocolDeviceByKey[key] = authorization.remoteDeviceId.value
            val bridge =
                openProtocolChannel(
                    request.sessionId,
                    authorization.permissions,
                    secureChannel,
                    key,
                    "local LAN/VPN",
                ) { null }
            bridge.join()
            completed = true
        } finally {
            if (!completed) closeQuietly(request.channel)
            cleanup(request, key)
        }
    }

    private suspend fun authorize(request: LocalProtocolChannelRequest): AuthorizationResult {
        val matchingRecord = trustStore.authorization(request.authorizedDeviceId)
        val proofTimestamp = request.proofTimestampEpochMillis
        val clockSkewMillis = proofTimestamp?.let { abs(clock() - it) }
        val proofValid = isProofValid(matchingRecord, request, proofTimestamp)
        val authorization =
            matchingRecord?.takeIf {
                isAuthorized(it, proofTimestamp, clockSkewMillis, proofValid)
            }
        val reason = authorizationFailureReason(matchingRecord, proofTimestamp, clockSkewMillis, proofValid, authorization)
        return AuthorizationResult(authorization, reason, clockSkewMillis)
    }

    private fun isProofValid(
        record: DeviceAuthorization?,
        request: LocalProtocolChannelRequest,
        proofTimestamp: Long?,
    ): Boolean {
        val token = record?.localProtocolToken ?: return false
        val timestamp = proofTimestamp ?: return false
        return localProtocolAuthenticator.verifiesProof(
            token,
            localProtocolSessionProofPayload(request.sessionId.value, request.authorizedDeviceId, timestamp),
            request.sessionProof.orEmpty(),
        )
    }

    private fun isAuthorized(
        record: DeviceAuthorization,
        proofTimestamp: Long?,
        clockSkewMillis: Long?,
        proofValid: Boolean,
    ): Boolean =
        record.revokedAtEpochMillis == null &&
            proofTimestamp != null &&
            clockSkewMillis != null &&
            clockSkewMillis <= LOCAL_PROTOCOL_PROOF_TOLERANCE_MILLIS &&
            proofValid

    private fun authorizationFailureReason(
        record: DeviceAuthorization?,
        proofTimestamp: Long?,
        clockSkewMillis: Long?,
        proofValid: Boolean,
        authorization: DeviceAuthorization?,
    ): String =
        when {
            record == null -> "DEVICE_NOT_FOUND"
            record.revokedAtEpochMillis != null -> "DEVICE_REVOKED"
            proofTimestamp == null -> "PROOF_TIMESTAMP_MISSING"
            clockSkewMillis != null && clockSkewMillis > LOCAL_PROTOCOL_PROOF_TOLERANCE_MILLIS -> "CLOCK_SKEW"
            record.localProtocolToken.isNullOrBlank() -> "LOCAL_TOKEN_MISSING"
            !proofValid -> "PROOF_INVALID"
            authorization == null -> "AUTHORIZATION_FAILED"
            else -> ""
        }

    private suspend fun rejectUnauthorized(
        request: LocalProtocolChannelRequest,
        reason: String,
        clockSkewMillis: Long?,
    ) {
        updateState {
            it.withLog(
                now = clock(),
                level = "warn",
                message = "Local protocol authorization failed for ${request.authorizedDeviceId}: $reason",
                eventCode = AgentLogEventCode.LocalProtocolAuthorizationFailed,
                context =
                    mapOf(
                        "authorizedDeviceId" to request.authorizedDeviceId,
                        "sessionId" to request.sessionId.value,
                        "reason" to reason,
                        "clockSkewMillis" to clockSkewMillis?.toString().orEmpty(),
                    ),
            )
        }
        request.channel.send(
            ProtocolMessage.Control(
                sessionId = request.sessionId,
                command =
                    ControlCommand.Error(
                        code = "unauthorized",
                        message = "This device is not authorized for local protocol access.",
                    ),
            ),
        )
        closeQuietly(request.channel)
    }

    private data class AuthorizationResult(
        val authorization: DeviceAuthorization?,
        val reason: String,
        val clockSkewMillis: Long?,
    )

    private suspend fun reserveSession(
        request: LocalProtocolChannelRequest,
        authorization: DeviceAuthorization,
    ): Boolean {
        val now = clock()
        authenticatedSessionIds.entries.removeIf { now - it.value > LOCAL_PROTOCOL_SESSION_RETENTION_MILLIS }
        if (authenticatedSessionIds.size >= MAX_AUTHENTICATED_LOCAL_PROTOCOL_SESSION_IDS) {
            rejectSession(request, "SES-7301", "Authenticated local session replay cache is at capacity; retry later.")
            return false
        }
        if (authenticatedSessionIds.putIfAbsent(request.sessionId.value, now) != null) {
            rejectSession(request, "SES-7302", "This authenticated local protocol session id was already used.")
            return false
        }
        if (activeSessionIds.size >= MAX_ACTIVE_LOCAL_PROTOCOL_SESSIONS ||
            activeSessionIds.putIfAbsent(request.sessionId.value, authorization.remoteDeviceId.value) != null
        ) {
            rejectSession(request, "SES-7303", "The desktop host is at its local-session capacity.")
            return false
        }
        return true
    }

    private suspend fun rejectSession(
        request: LocalProtocolChannelRequest,
        code: String,
        message: String,
    ): Nothing {
        request.channel.send(
            ProtocolMessage.Control(
                sessionId = request.sessionId,
                command = ControlCommand.Error(code, message),
            ),
        )
        closeQuietly(request.channel)
        error(message)
    }

    private fun cleanup(
        request: LocalProtocolChannelRequest,
        key: String,
    ) {
        videoStateJobs.remove(key)?.cancel()
        relayTelemetryBridges.remove(key)?.let { telemetry ->
            telemetry.stopStatsForwarding()
            telemetry.stopClipboardForwarding()
        }
        relayProtocolBridges.remove(key)
        localProtocolDeviceByKey.remove(key)
        activeSessionIds.remove(request.sessionId.value)
    }

    private suspend fun closeQuietly(channel: ProtocolMessageChannel) {
        runCatching { channel.close() }
    }
}

private const val LOCAL_PROTOCOL_PROOF_TOLERANCE_MILLIS = 30_000L
private const val LOCAL_PROTOCOL_SESSION_RETENTION_MILLIS = 24 * 60 * 60 * 1_000L
private const val MAX_AUTHENTICATED_LOCAL_PROTOCOL_SESSION_IDS = 4_096
private const val MAX_ACTIVE_LOCAL_PROTOCOL_SESSIONS = 8
