package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.DeviceTrustTransport
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RemoteDeviceId
import dev.aegis.remote.core.pairing.DeviceTrustStore
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.desktop.webrtc.DesktopProtocolChannelBridge
import dev.aegis.remote.desktop.webrtc.DesktopProtocolMessageHandler
import dev.aegis.remote.desktop.webrtc.DesktopProtocolTelemetryBridge
import dev.aegis.remote.desktop.webrtc.DesktopResolvedTurnCredentials
import dev.aegis.remote.protocol.ProtocolMessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

internal data class DesktopRelaySessionDependencies(
    val state: StateFlow<DesktopAgentState>,
    val scope: CoroutineScope,
    val clock: () -> Long,
    val connectorProvider: () -> DesktopRelayConnector?,
    val remoteAccessEnabled: () -> Boolean,
    val trustStore: DeviceTrustStore,
    val relayProtocolBridges: ConcurrentHashMap<String, DesktopProtocolChannelBridge>,
    val relayTelemetryBridges: ConcurrentHashMap<String, DesktopProtocolTelemetryBridge>,
    val remoteInputHandlers: ConcurrentHashMap<String, DesktopProtocolMessageHandler>,
    val videoStateJobs: ConcurrentHashMap<String, Job>,
    val localProtocolDeviceByKey: ConcurrentHashMap<String, String>,
    val openDesktopProtocolChannel:
        suspend (
            dev.aegis.remote.core.model.SessionId,
            dev.aegis.remote.core.model.DevicePermissions,
            ProtocolMessageChannel,
            String,
            String,
            suspend (dev.aegis.remote.core.model.TurnConfig) -> DesktopResolvedTurnCredentials?,
        ) -> Job,
    val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
)

internal class DesktopRelaySessionCoordinator(
    private val dependencies: DesktopRelaySessionDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val clock = dependencies.clock
    private val connectorProvider = dependencies.connectorProvider
    private val remoteAccessEnabled = dependencies.remoteAccessEnabled
    private val trustStore = dependencies.trustStore
    private val relayProtocolBridges = dependencies.relayProtocolBridges
    private val relayTelemetryBridges = dependencies.relayTelemetryBridges
    private val remoteInputHandlers = dependencies.remoteInputHandlers
    private val videoStateJobs = dependencies.videoStateJobs
    private val localProtocolDeviceByKey = dependencies.localProtocolDeviceByKey
    private val updateState = dependencies.updateState
    private var remoteSessionCoordinator: DesktopRemoteSessionCoordinator? = null

    fun remoteSessionCoordinator(): DesktopRemoteSessionCoordinator? = remoteSessionCoordinator

    fun setRemoteSessionCoordinator(coordinator: DesktopRemoteSessionCoordinator?) {
        remoteSessionCoordinator = coordinator
    }

    suspend fun closeRelaySessionResourcesForIdentityChange() {
        remoteSessionCoordinator = null
        val protocolBridges = relayProtocolBridges.values.toList()
        val telemetryBridges = relayTelemetryBridges.values.toList()
        val inputHandlers = remoteInputHandlers.values.toList()
        val stateJobs = videoStateJobs.values.toList()
        relayProtocolBridges.clear()
        relayTelemetryBridges.clear()
        remoteInputHandlers.clear()
        videoStateJobs.clear()
        stateJobs.forEach(Job::cancel)
        val completed =
            withTimeoutOrNull(DESKTOP_SHUTDOWN_TIMEOUT_MILLIS) {
                telemetryBridges.forEach { bridge ->
                    bridge.stopStatsForwarding()
                    bridge.stopClipboardForwarding()
                }
                inputHandlers.forEach { handler -> handler.close() }
                protocolBridges.forEach { bridge -> bridge.stop() }
                true
            }
        check(completed == true) { "IDN-1014: timed out while closing sessions before identity change" }
    }

    fun approveRelaySession(sessionIdValue: String) {
        decideRelaySession(sessionIdValue, approved = true)
    }

    fun rejectRelaySession(sessionIdValue: String) {
        decideRelaySession(sessionIdValue, approved = false)
    }

    fun onRelayDeviceEvent(event: RelayDeviceEvent) {
        when (event) {
            is RelayDeviceEvent.SessionRequested -> onSessionRequested(event)
            is RelayDeviceEvent.SessionApproved -> logSessionApproved(event)
            is RelayDeviceEvent.SessionRejected -> logSessionRejected(event)
        }
    }

    private fun onSessionRequested(event: RelayDeviceEvent.SessionRequested) {
        if (!remoteAccessEnabled()) {
            scope.launch {
                runCatching { connectorProvider()?.approveSession(event.sessionId, approved = false) }
                    .rethrowCancellation()
            }
            updateState {
                it.withLog(
                    clock(),
                    "warn",
                    "Rejected relay session ${event.sessionId.value} because remote access is disabled",
                    AgentLogEventCode.RelaySessionChanged,
                    mapOf("action" to "rejected-remote-disabled", "sessionId" to event.sessionId.value),
                )
            }
            return
        }
        scope.launch { admitOrQueueSession(event) }
    }

    private suspend fun admitOrQueueSession(event: RelayDeviceEvent.SessionRequested) {
        val sourceIdentity = event.sourceIdentity?.publicIdentity
        val coordinator = remoteSessionCoordinator
        if (sourceIdentity == null || coordinator == null) {
            runCatching { connectorProvider()?.approveSession(event.sessionId, approved = false) }
                .rethrowCancellation()
            updateState {
                it.withLog(
                    clock(),
                    "warn",
                    "Rejected relay session ${event.sessionId.value}: cryptographic session admission unavailable",
                    AgentLogEventCode.RelaySessionChanged,
                    mapOf("action" to "rejected-admission-unavailable", "sessionId" to event.sessionId.value),
                )
            }
            return
        }
        val admission =
            runCatching {
                coordinator.admit(event.sessionId, sourceIdentity, event.expiresAtEpochMillis)
            }.rethrowCancellation()
        admission.onFailure { error ->
            runCatching { connectorProvider()?.approveSession(event.sessionId, approved = false) }
                .rethrowCancellation()
            updateState {
                it.withLog(
                    clock(),
                    "warn",
                    "Rejected relay session ${event.sessionId.value}: ${error.message}",
                    AgentLogEventCode.RelaySessionChanged,
                    mapOf("action" to "rejected-session-admission", "sessionId" to event.sessionId.value),
                    error,
                )
            }
        }
        if (admission.isFailure) return

        val existingAuthorization =
            relayAuthorizationFor(
                sourceRelayDeviceId = event.sourceRelayDeviceId,
                sourceIdentity = sourceIdentity,
            )
        if (existingAuthorization != null) {
            autoApproveTrustedSession(event, coordinator, existingAuthorization)
            return
        }
        queueSessionRequest(event)
    }

    private suspend fun autoApproveTrustedSession(
        event: RelayDeviceEvent.SessionRequested,
        coordinator: DesktopRemoteSessionCoordinator,
        authorization: DeviceAuthorization,
    ) {
        val result =
            runCatching {
                connectorProvider()?.approveSession(event.sessionId, approved = true)
                coordinator.approved(event.sessionId)
                coordinator.establishingE2ee(event.sessionId)
                openApprovedRelayProtocol(event.sessionId, authorization)
            }.rethrowCancellation()
        result
            .onSuccess {
                val authorizedDevices = trustStore.listAuthorizedDevices()
                updateState {
                    it
                        .copy(authorizedDevices = authorizedDevices)
                        .withLog(
                            clock(),
                            "info",
                            "Auto-approved relay session ${event.sessionId.value} from trusted ${event.sourceRelayDeviceId.value}",
                            AgentLogEventCode.RelaySessionChanged,
                            mapOf(
                                "action" to "auto-approved",
                                "sessionId" to event.sessionId.value,
                                "sourceRelayDeviceId" to event.sourceRelayDeviceId.value,
                            ),
                        )
                }
            }.onFailure { error ->
                coordinator.failed(event.sessionId, "AUTO_APPROVAL_FAILED")
                updateState {
                    it.withLog(
                        clock(),
                        "error",
                        "Auto-approval failed for relay session ${event.sessionId.value}: ${error.message}",
                        AgentLogEventCode.RelaySessionChanged,
                        mapOf("action" to "auto-approval-failed", "sessionId" to event.sessionId.value),
                        error,
                    )
                }
            }
    }

    private fun queueSessionRequest(event: RelayDeviceEvent.SessionRequested) {
        val request =
            DesktopRelaySessionRequest(
                sessionId = event.sessionId,
                sourceRelayDeviceId = event.sourceRelayDeviceId,
                targetRelayDeviceId = event.targetRelayDeviceId,
                expiresAtEpochMillis = event.expiresAtEpochMillis,
                receivedAtEpochMillis = clock(),
                sourceDisplayName = event.sourceIdentity?.displayName,
                sourcePublicKeyFingerprint = event.sourceIdentity?.publicKeyFingerprint,
                sourcePublicIdentity = event.sourceIdentity?.publicIdentity,
            )
        updateState {
            it
                .copy(pendingRelaySessions = (it.pendingRelaySessions + request).takeLast(20))
                .withLog(
                    clock(),
                    "info",
                    "Relay session requested by ${request.displayLabel()}: ${event.sessionId.value}",
                    AgentLogEventCode.RelaySessionChanged,
                    mapOf(
                        "action" to "requested",
                        "sessionId" to event.sessionId.value,
                        "sourceLabel" to request.displayLabel(),
                    ),
                )
        }
    }

    private fun logSessionApproved(event: RelayDeviceEvent.SessionApproved) {
        updateState {
            it.withLog(
                clock(),
                "info",
                "Relay session approved: ${event.sessionId.value}",
                AgentLogEventCode.RelaySessionChanged,
                mapOf("action" to "approved", "sessionId" to event.sessionId.value),
            )
        }
    }

    private fun logSessionRejected(event: RelayDeviceEvent.SessionRejected) {
        updateState {
            it.withLog(
                clock(),
                "warn",
                "Relay session rejected: ${event.sessionId.value}",
                AgentLogEventCode.RelaySessionChanged,
                mapOf("action" to "rejected", "sessionId" to event.sessionId.value),
            )
        }
    }

    private fun decideRelaySession(
        sessionIdValue: String,
        approved: Boolean,
    ) {
        val connector = connectorProvider() ?: return
        val request = state.value.pendingRelaySessions.firstOrNull { it.sessionId.value == sessionIdValue } ?: return
        if (request.decisionInProgress) return
        updateState { current ->
            current.copy(
                pendingRelaySessions =
                    current.pendingRelaySessions.map { pending ->
                        if (pending.sessionId == request.sessionId) pending.copy(decisionInProgress = true) else pending
                    },
            )
        }
        scope.launch { decideSession(request, connector, approved) }
    }

    private suspend fun decideSession(
        request: DesktopRelaySessionRequest,
        connector: DesktopRelayConnector,
        approved: Boolean,
    ) {
        var relayDecisionSent = false
        val sourceIdentity = request.sourcePublicIdentity
        if (approved && sourceIdentity == null) {
            runCatching { connector.approveSession(request.sessionId, approved = false) }.rethrowCancellation()
            updateState {
                it
                    .copy(
                        pendingRelaySessions =
                            it.pendingRelaySessions.filterNot { pending -> pending.sessionId == request.sessionId },
                    ).withLog(
                        clock(),
                        "warn",
                        "Rejected relay session ${request.sessionId.value}: the source did not present a cryptographic identity",
                        AgentLogEventCode.RelaySessionChanged,
                        mapOf("action" to "rejected-identity-missing", "sessionId" to request.sessionId.value),
                    )
            }
            return
        }
        val result =
            runCatching {
                connector.approveSession(request.sessionId, approved)
                relayDecisionSent = true
                val coordinator = checkNotNull(remoteSessionCoordinator) { "SESSION_COORDINATOR_UNAVAILABLE" }
                updateSessionDecision(request, coordinator, approved)
                if (approved) {
                    approveAndOpenSession(request, sourceIdentity, coordinator)
                }
                val authorizedDevices = if (approved) trustStore.listAuthorizedDevices() else null
                updateState {
                    it
                        .copy(authorizedDevices = authorizedDevices ?: it.authorizedDevices)
                        .withLog(
                            clock(),
                            if (approved) "info" else "warn",
                            "${if (approved) "Approved" else "Rejected"} relay session ${request.sessionId.value} from ${request.displayLabel()}",
                            AgentLogEventCode.RelaySessionChanged,
                            mapOf(
                                "action" to if (approved) "decision-approved" else "decision-rejected",
                                "sessionId" to request.sessionId.value,
                                "sourceLabel" to request.displayLabel(),
                            ),
                        )
                }
            }.rethrowCancellation()
        result.onFailure { error ->
            if (relayDecisionSent) {
                runCatching { remoteSessionCoordinator?.failed(request.sessionId, "SESSION_ESTABLISHMENT_FAILED") }
                    .rethrowCancellation()
            }
            updateState {
                val recovered =
                    if (!relayDecisionSent) {
                        it.copy(
                            pendingRelaySessions =
                                it.pendingRelaySessions.map { pending ->
                                    if (pending.sessionId == request.sessionId) {
                                        pending.copy(decisionInProgress = false)
                                    } else {
                                        pending
                                    }
                                },
                        )
                    } else {
                        it
                    }
                recovered.withLog(
                    clock(),
                    "error",
                    if (relayDecisionSent) {
                        "Relay approved, but E2EE failed closed: ${error.message}"
                    } else {
                        "Relay session decision failed: ${error.message}"
                    },
                    AgentLogEventCode.RelaySessionChanged,
                    mapOf(
                        "action" to if (relayDecisionSent) "e2ee-establishment-failed" else "decision-failed",
                        "sessionId" to request.sessionId.value,
                    ),
                    error,
                )
            }
        }
    }

    private suspend fun updateSessionDecision(
        request: DesktopRelaySessionRequest,
        coordinator: DesktopRemoteSessionCoordinator,
        approved: Boolean,
    ) {
        if (approved) {
            coordinator.approved(request.sessionId)
        } else {
            coordinator.rejected(request.sessionId)
        }
        updateState {
            it
                .copy(
                    pendingRelaySessions =
                        it.pendingRelaySessions.filterNot { pending -> pending.sessionId == request.sessionId },
                ).withLog(
                    clock(),
                    if (approved) "info" else "warn",
                    "Relay ${if (approved) "approval accepted; establishing E2EE" else "rejection sent"} for ${request.sessionId.value}",
                    AgentLogEventCode.RelaySessionChanged,
                    mapOf(
                        "action" to if (approved) "decision-approved-establishing-e2ee" else "decision-rejected",
                        "sessionId" to request.sessionId.value,
                    ),
                )
        }
    }

    private suspend fun approveAndOpenSession(
        request: DesktopRelaySessionRequest,
        sourceIdentity: DevicePublicIdentity?,
        coordinator: DesktopRemoteSessionCoordinator,
    ) {
        val identity = checkNotNull(sourceIdentity) { "Approved relay identity must be present" }
        val authorization =
            DeviceAuthorization(
                remoteDeviceId = RemoteDeviceId(identity.deviceId.value),
                displayName = request.trustDisplayName(),
                approvedAtEpochMillis = clock(),
                permissions = defaultRemoteRelayPermissions(),
                publicIdentity = identity,
                relayDeviceId = request.sourceRelayDeviceId,
                approvedTransport = DeviceTrustTransport.RelayRemote,
            )
        trustStore.saveAuthorization(authorization)
        coordinator.establishingE2ee(request.sessionId)
        openApprovedRelayProtocol(request.sessionId, authorization)
        trustStore.saveAuthorization(
            authorization.copy(
                lastApprovedCryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
                cryptoSuiteDowngradeFloor = AEGIS_P256_AESGCM_V1.id,
            ),
        )
    }

    private suspend fun relayAuthorizationFor(
        sourceRelayDeviceId: RelayDeviceId,
        sourceIdentity: DevicePublicIdentity?,
    ): DeviceAuthorization? {
        if (sourceIdentity == null) return null
        return trustStore
            .listAuthorizedDevices()
            .firstOrNull {
                it.remoteDeviceId.value == sourceIdentity.deviceId.value &&
                    it.publicIdentity?.matches(sourceIdentity) == true &&
                    it.relayDeviceId == sourceRelayDeviceId &&
                    it.approvedTransport == DeviceTrustTransport.RelayRemote &&
                    it.lastApprovedCryptoSuiteId == AEGIS_P256_AESGCM_V1.id &&
                    it.cryptoSuiteDowngradeFloor == AEGIS_P256_AESGCM_V1.id &&
                    it.revokedAtEpochMillis == null &&
                    it.permissions.remoteAccess
            }
    }

    private suspend fun openApprovedRelayProtocol(
        sessionId: dev.aegis.remote.core.model.SessionId,
        authorization: DeviceAuthorization,
    ) {
        val connector = connectorProvider() ?: return
        val mapKey = "relay:${sessionId.value}"
        localProtocolDeviceByKey[mapKey] = authorization.remoteDeviceId.value
        runCatching {
            val peerIdentity = authorization.publicIdentity ?: error("REMOTE_IDENTITY_REQUIRED_FOR_E2EE")
            val channel = connector.openProtocolMessageChannel(sessionId, peerIdentity)
            remoteSessionCoordinator?.openingSignaling(sessionId)
            dependencies.openDesktopProtocolChannel(
                sessionId,
                authorization.permissions,
                channel,
                mapKey,
                "approved relay",
            ) {
                val credentials = connector.requestTurnCredentials()
                DesktopResolvedTurnCredentials(
                    username = credentials.username,
                    credential = credentials.credential,
                )
            }
        }.onFailure { error ->
            localProtocolDeviceByKey.remove(mapKey)
            if (error is CancellationException) throw error
        }.getOrThrow()
    }

    private fun <T> Result<T>.rethrowCancellation(): Result<T> {
        exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        return this
    }
}
