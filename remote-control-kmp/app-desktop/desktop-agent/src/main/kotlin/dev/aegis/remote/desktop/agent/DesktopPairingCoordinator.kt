package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.DeviceTrustTransport
import dev.aegis.remote.core.model.RemoteDeviceId
import dev.aegis.remote.core.model.WakeOnLanCapability
import dev.aegis.remote.core.pairing.DeviceTrustStore
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import dev.aegis.remote.core.pairing.localProtocolSessionProofPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopPairingDependencies(
    val state: StateFlow<DesktopAgentState>,
    val scope: CoroutineScope,
    val pairingServer: LocalPairingServer,
    val trustStore: DeviceTrustStore,
    val openSshProvisioner: AegisOpenSshManager?,
    val pairingServerIdentityProvider: () -> DevicePublicIdentity?,
    val pairingHostProvider: () -> String,
    val clock: () -> Long,
    val encodeQrPayload: (LocalPairingSession) -> String,
    val onHostIdentityChanged: (DevicePublicIdentity) -> Unit,
    val refreshAuthorizedDevices: suspend (AgentLogEventCode, String, String, Map<String, String>, Throwable?) -> Unit,
    val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
)

internal class DesktopPairingCoordinator(
    private val dependencies: DesktopPairingDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val pairingServer = dependencies.pairingServer
    private val trustStore = dependencies.trustStore
    private val openSshProvisioner = dependencies.openSshProvisioner
    private val pairingServerIdentityProvider = dependencies.pairingServerIdentityProvider
    private val pairingHostProvider = dependencies.pairingHostProvider
    private val clock = dependencies.clock
    private val encodeQrPayload = dependencies.encodeQrPayload
    private val onHostIdentityChanged = dependencies.onHostIdentityChanged
    private val updateState = dependencies.updateState
    private var qrRefreshJob: Job? = null
    private val refreshInProgress = AtomicBoolean(false)

    private data class PairingApprovalInput(
        val localProtocolToken: String,
        val publicIdentity: DevicePublicIdentity,
        val sshPublicKey: String,
        val provisioner: AegisOpenSshManager,
    )

    private class PairingApprovalAttempt {
        var authorization: DeviceAuthorization? = null
        var sshKeyEnrolled: Boolean = false
    }

    fun approvePairing(requestId: String) {
        if (state.value.trustStoreRecoveryRequired) return
        val request = state.value.pendingPairingRequests.firstOrNull { it.requestId == requestId } ?: return
        if (clock() > request.requestedAtEpochMillis + LOCAL_PAIRING_APPROVAL_TTL_MILLIS) {
            rejectExpiredPairing(requestId, request)
            return
        }
        val input = pairingApprovalInput(request)
        if (input == null) {
            pairingServer.reject(requestId)
            updateState {
                it.copy(pendingPairingRequests = it.pendingPairingRequests.filterNot { pending -> pending.requestId == requestId })
            }
            return
        }
        pairingServer.holdPendingApproval(requestId)
        scope.launch { approvePairingRequest(requestId, request, input) }
    }

    private fun rejectExpiredPairing(
        requestId: String,
        request: DesktopPairingRequest,
    ) {
        pairingServer.reject(requestId)
        updateState {
            it
                .copy(pendingPairingRequests = it.pendingPairingRequests.filterNot { pending -> pending.requestId == requestId })
                .withLog(
                    now = clock(),
                    level = "warn",
                    message = "Expired pairing request from ${request.deviceName}",
                    eventCode = AgentLogEventCode.PairingRequestExpired,
                    context = pairingLogContext(request),
                )
        }
    }

    private fun pairingApprovalInput(request: DesktopPairingRequest): PairingApprovalInput? {
        val missing =
            buildList {
                if (request.localProtocolToken == null) add("protocol-token")
                if (request.publicIdentity == null) add("device-identity")
                if (request.sshPublicKey.isNullOrBlank()) add("ssh-public-key")
                if (openSshProvisioner == null) add("managed-openssh")
            }.joinToString()
        if (missing.isBlank()) {
            return PairingApprovalInput(
                localProtocolToken = requireNotNull(request.localProtocolToken),
                publicIdentity = requireNotNull(request.publicIdentity),
                sshPublicKey = requireNotNull(request.sshPublicKey),
                provisioner = requireNotNull(openSshProvisioner),
            )
        }
        updateState {
            it.withLog(
                now = clock(),
                level = "error",
                message = "SSH-7319 Pairing approval is missing required secure bootstrap data: $missing",
                eventCode = AgentLogEventCode.PairingApprovalFailed,
                context = pairingLogContext(request) + ("missing" to missing),
            )
        }
        return null
    }

    private suspend fun approvePairingRequest(
        requestId: String,
        request: DesktopPairingRequest,
        input: PairingApprovalInput,
    ) {
        val attempt = PairingApprovalAttempt()
        val approval =
            runCatching { enrollAndApprovePairing(requestId, request, input, attempt) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
        if (approval.isSuccess) {
            publishApprovedPairing(request, approval.getOrThrow())
        } else {
            recoverFailedPairing(request, input, attempt, approval.exceptionOrNull())
        }
    }

    private suspend fun enrollAndApprovePairing(
        requestId: String,
        request: DesktopPairingRequest,
        input: PairingApprovalInput,
        attempt: PairingApprovalAttempt,
    ): DeviceAuthorization {
        val bootstrap = input.provisioner.enrollAuthorizedKey(input.sshPublicKey, input.publicIdentity.deviceId.value)
        attempt.sshKeyEnrolled = true
        val permissions =
            defaultDesktopPermissions(remote = request.remote).copy(
                wakeOnLan = bootstrap.wakeOnLanConfigs.any { it.capability == WakeOnLanCapability.Supported },
            )
        val authorization =
            DeviceAuthorization(
                remoteDeviceId = RemoteDeviceId("local-${input.publicIdentity.deviceId.value}"),
                displayName = request.deviceName,
                approvedAtEpochMillis = clock(),
                permissions = permissions,
                localProtocolToken = input.localProtocolToken,
                publicIdentity = input.publicIdentity,
                approvedTransport = DeviceTrustTransport.LocalPairing,
            )
        attempt.authorization = authorization
        trustStore.saveAuthorization(authorization)
        pairingServer.approve(
            requestId = requestId,
            profile =
                LocalPairedProfile(
                    displayName = desktopDisplayName(),
                    localHost = request.localHost ?: pairingHostProvider(),
                    sshPort = bootstrap.port,
                    agentFingerprint = checkNotNull(state.value.agentFingerprint),
                    authorizedDeviceId = authorization.remoteDeviceId.value,
                    permissions = permissions,
                    username = bootstrap.username,
                    sshHostKeyFingerprint = bootstrap.hostKeyFingerprint,
                    wakeOnLanAdapters = bootstrap.wakeOnLanConfigs,
                    hostIdentity = requireNotNull(pairingServerIdentityProvider()),
                ),
        )
        return authorization
    }

    private suspend fun publishApprovedPairing(
        request: DesktopPairingRequest,
        authorization: DeviceAuthorization,
    ) {
        val authorizedDevices = trustStore.listAuthorizedDevices()
        updateState {
            it
                .copy(
                    pendingPairingRequests = it.pendingPairingRequests.filterNot { pending -> pending.requestId == request.requestId },
                    authorizedDevices = authorizedDevices,
                ).withLog(
                    now = clock(),
                    level = "info",
                    message = "Approved pairing request from ${request.deviceName}",
                    eventCode = AgentLogEventCode.PairingRequestApproved,
                    context =
                        pairingLogContext(request) +
                            mapOf(
                                "approvalLatencyMillis" to
                                    (clock() - request.requestedAtEpochMillis)
                                        .coerceAtLeast(0)
                                        .toString(),
                                "authorizedDeviceId" to authorization.remoteDeviceId.value,
                            ),
                )
        }
    }

    private suspend fun recoverFailedPairing(
        request: DesktopPairingRequest,
        input: PairingApprovalInput,
        attempt: PairingApprovalAttempt,
        error: Throwable?,
    ) {
        attempt.authorization?.let { authorization ->
            cleanupFailedPairingAuthorization(authorization, input, attempt.sshKeyEnrolled)
        }
        val authorizedDevices = trustStore.listAuthorizedDevices()
        updateState {
            it
                .copy(authorizedDevices = authorizedDevices)
                .withLog(
                    now = clock(),
                    level = "error",
                    message = "Failed to approve pairing request from ${request.deviceName}: ${error?.message}",
                    eventCode = AgentLogEventCode.PairingApprovalFailed,
                    context = pairingLogContext(request),
                    error = error,
                )
        }
    }

    private suspend fun cleanupFailedPairingAuthorization(
        authorization: DeviceAuthorization,
        input: PairingApprovalInput,
        sshKeyEnrolled: Boolean,
    ) {
        val remoteDeviceId = authorization.remoteDeviceId.value
        runCatching { trustStore.revoke(remoteDeviceId) }.rethrowCancellation()
        val revokedAuthorization = trustStore.authorization(remoteDeviceId)
        if (!sshKeyEnrolled || revokedAuthorization == null) {
            if (revokedAuthorization != null) {
                runCatching { (trustStore as? DeletableDeviceTrustStore)?.deleteRevoked(remoteDeviceId) }
                    .rethrowCancellation()
            }
            return
        }
        trustStore.saveAuthorization(
            revokedAuthorization.copy(
                sshKeyRemovalPending = true,
                sshKeyRemovalFailureCode = null,
            ),
        )
        val removal =
            runCatching { input.provisioner.removeAuthorizedKey(input.publicIdentity.deviceId.value) }
                .rethrowCancellation()
        val removalResult = removal.getOrNull()
        if (removalResult?.let { it.removed && it.deviceId == input.publicIdentity.deviceId.value } == true) {
            trustStore.saveAuthorization(
                revokedAuthorization.copy(
                    sshKeyRemovalPending = false,
                    sshKeyRemovalFailureCode = null,
                ),
            )
            runCatching { (trustStore as? DeletableDeviceTrustStore)?.deleteRevoked(remoteDeviceId) }
                .rethrowCancellation()
            return
        }
        val removalError = removal.exceptionOrNull()
        val failureCode = removalError.sshKeyRemovalFailureCode()
        trustStore.saveAuthorization(
            revokedAuthorization.copy(
                sshKeyRemovalPending = true,
                sshKeyRemovalFailureCode = failureCode,
            ),
        )
        dependencies.refreshAuthorizedDevices(
            AgentLogEventCode.SshKeyRemovalPending,
            "$failureCode Managed SSH key removal remains pending after pairing approval failed for revoked device $remoteDeviceId",
            "error",
            mapOf(
                "remoteDeviceId" to remoteDeviceId,
                "action" to "pending",
                "failureCode" to failureCode,
                "removalResult" to
                    when {
                        removalError != null -> "exception"
                        removalResult?.deviceId != input.publicIdentity.deviceId.value -> "device-id-mismatch"
                        else -> "removed=false"
                    },
            ),
            removalError,
        )
    }

    private fun <T> Result<T>.rethrowCancellation(): Result<T> {
        exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        return this
    }

    fun rejectPairing(requestId: String) {
        val request = state.value.pendingPairingRequests.firstOrNull { it.requestId == requestId }
        pairingServer.reject(requestId)
        updateState {
            it
                .copy(pendingPairingRequests = it.pendingPairingRequests.filterNot { pending -> pending.requestId == requestId })
                .withLog(
                    now = clock(),
                    level = "warn",
                    message = "Rejected pairing request from ${request?.deviceName ?: requestId}",
                    eventCode = AgentLogEventCode.PairingRequestRejected,
                    context = request?.let(::pairingLogContext) ?: mapOf("requestId" to requestId),
                )
        }
    }

    fun stop() {
        refreshInProgress.set(false)
        qrRefreshJob?.cancel()
        qrRefreshJob = null
    }

    fun schedule(expiresAtEpochMillis: Long) {
        schedulePairingQrRefresh(expiresAtEpochMillis)
    }

    fun refresh() {
        if (!state.value.pairingServerRunning || !refreshInProgress.compareAndSet(false, true)) return
        updateState { it.copy(pairingQrRefreshing = true) }
        scope.launch {
            runCatching { pairingServer.refreshPairingSession() }
                .onSuccess { session ->
                    onHostIdentityChanged(session.hostIdentity)
                    updateState {
                        it
                            .copy(
                                pairingUrl = session.url,
                                pairingCode = session.pairingCode,
                                agentFingerprint = session.agentFingerprint,
                                pairingQrPayload = encodeQrPayload(session),
                                pairingQrExpiresAtEpochMillis = session.expiresAtEpochMillis,
                            ).withLog(
                                now = clock(),
                                level = "info",
                                message = "Rotated the short-lived local pairing QR",
                                eventCode = AgentLogEventCode.PairingServerStarted,
                                context = mapOf(
                                    "pairingUrl" to session.url,
                                    "expiresAtEpochMillis" to session.expiresAtEpochMillis.toString(),
                                ),
                            )
                    }
                    schedulePairingQrRefresh(session.expiresAtEpochMillis)
                }.onFailure { error ->
                    updateState {
                        it.withLog(
                            now = clock(),
                            level = "error",
                            message = "QRP-7105 Failed to refresh the local pairing QR: ${error.message}",
                            eventCode = AgentLogEventCode.PairingServerStartFailed,
                            error = error,
                        )
                    }
                }
            refreshInProgress.set(false)
            updateState { it.copy(pairingQrRefreshing = false) }
        }
    }

    private fun schedulePairingQrRefresh(expiresAtEpochMillis: Long) {
        qrRefreshJob?.cancel()
        if (expiresAtEpochMillis == Long.MAX_VALUE) {
            qrRefreshJob = null
            return
        }
        qrRefreshJob =
            scope.launch {
                val delayMillis =
                    (expiresAtEpochMillis - clock() - PAIRING_QR_REFRESH_EARLY_MILLIS)
                        .coerceAtLeast(PAIRING_QR_MINIMUM_REFRESH_DELAY_MILLIS)
                delay(delayMillis)
                if (state.value.pairingServerRunning) refresh()
            }
    }
}
