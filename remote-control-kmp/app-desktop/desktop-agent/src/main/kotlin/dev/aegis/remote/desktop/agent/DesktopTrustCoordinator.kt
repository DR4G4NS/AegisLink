package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceTrustTransport
import dev.aegis.remote.core.pairing.DeviceTrustStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

internal data class DesktopTrustDependencies(
    val state: kotlinx.coroutines.flow.StateFlow<DesktopAgentState>,
    val scope: CoroutineScope,
    val trustStore: DeviceTrustStore,
    val openSshProvisioner: AegisOpenSshManager?,
    val closeDeviceSessionResources: suspend (String) -> Unit,
    val restart: () -> Unit,
    val clock: () -> Long,
    val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
)

/** Owns trust-store recovery, revocation, SSH-key cleanup and record deletion. */
internal class DesktopTrustCoordinator(
    dependencies: DesktopTrustDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val trustStore = dependencies.trustStore
    private val openSshProvisioner = dependencies.openSshProvisioner
    private val closeDeviceSessionResources = dependencies.closeDeviceSessionResources
    private val restart = dependencies.restart
    private val clock = dependencies.clock
    private val updateState = dependencies.updateState
    private val permissionChanges = mutableSetOf<String>()

    @Suppress("TooGenericExceptionCaught") // Trust/OS adapter boundary: fail closed and publish errors; preserve cancellation.
    fun updatePermissions(
        remoteDeviceId: String,
        permissions: DevicePermissions,
    ) {
        if (state.value.trustStoreRecoveryRequired) return
        synchronized(permissionChanges) { if (!permissionChanges.add(remoteDeviceId)) return }
        updateState {
            it.copy(
                permissionChangesInProgress = it.permissionChangesInProgress + remoteDeviceId,
                permissionChangeErrors = it.permissionChangeErrors - remoteDeviceId,
            )
        }
        scope.launch {
            var changedSshDeviceId: String? = null
            try {
                val record = trustStore.authorization(remoteDeviceId)?.takeIf { it.revokedAtEpochMillis == null } ?: return@launch
                // A shell can read and write files. These capabilities intentionally share one control.
                require(permissions.terminal == permissions.sftp)
                val next = permissions.copy(input = permissions.input && permissions.visual)
                if (next == record.permissions) return@launch
                if (next.terminal != record.permissions.terminal || next.sftp != record.permissions.sftp) {
                    val manager = requireNotNull(openSshProvisioner)
                    changedSshDeviceId = requireNotNull(record.publicIdentity).deviceId.value
                    manager.setFileAccess(changedSshDeviceId, next.terminal)
                }
                val latest = trustStore.authorization(remoteDeviceId)?.takeIf { it.revokedAtEpochMillis == null }
                if (latest == null) {
                    changedSshDeviceId?.let { openSshProvisioner?.setFileAccess(it, false) }
                    return@launch
                }
                trustStore.saveAuthorization(latest.copy(permissions = next))
                closeDeviceSessionResources(remoteDeviceId)
                refreshAuthorizedDevices(AgentLogEventCode.Generic, "Device permissions updated: $remoteDeviceId", "info", emptyMap())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                changedSshDeviceId?.let { runCatching { openSshProvisioner?.setFileAccess(it, false) } }
                updateState {
                    it
                        .copy(permissionChangeErrors = it.permissionChangeErrors + (remoteDeviceId to (error.message ?: "AUTH-1008")))
                        .withLog(clock(), "error", "AUTH-1008 Failed to update permissions: ${error.message}", error = error)
                }
            } finally {
                synchronized(permissionChanges) { permissionChanges.remove(remoteDeviceId) }
                updateState { it.copy(permissionChangesInProgress = it.permissionChangesInProgress - remoteDeviceId) }
            }
        }
    }

    fun publishTrustStoreUnavailable(error: Throwable) {
        val reason = (error as? TrustStoreAccessException)?.reason
        updateState {
            it
                .copy(
                    pairingServerRunning = false,
                    pairingUrl = null,
                    pairingCode = null,
                    agentFingerprint = null,
                    pairingQrPayload = null,
                    pairingQrExpiresAtEpochMillis = null,
                    trustStoreRecoveryRequired = true,
                    trustStoreFailureReason = reason,
                ).withLog(
                    now = clock(),
                    level = "error",
                    message =
                        "TRUST-7401 Trust store is unavailable; pairing and trust changes are blocked. " +
                            "Restore a verified backup or explicitly reset and re-pair after visible consent.",
                    eventCode = AgentLogEventCode.TrustStoreUnavailable,
                    context = mapOf("reason" to (reason?.name ?: error.javaClass.simpleName)),
                    error = error,
                )
        }
    }

    /** Restores only a backup which the configured store accepts as secure and decodable. */
    fun restoreTrustStoreFromBackup(backupPath: String) {
        if (!state.value.trustStoreRecoveryRequired || backupPath.isBlank()) return
        recoverTrustStore("restored") { store -> store.restoreFromBackup(Path.of(backupPath.trim())) }
    }

    /** Resets trust only after the desktop UI has presented an explicit confirmation. */
    fun resetTrustStoreAfterConsent() {
        if (!state.value.trustStoreRecoveryRequired) return
        recoverTrustStore("reset") { store -> store.resetAfterConsent() }
    }

    private fun recoverTrustStore(
        action: String,
        recovery: suspend (RecoverableDeviceTrustStore) -> Unit,
    ) {
        val store = trustStore as? RecoverableDeviceTrustStore
        if (store == null) {
            updateState {
                it.withLog(
                    clock(),
                    "error",
                    "TRUST-7402 The configured trust store does not support explicit recovery",
                    AgentLogEventCode.TrustStoreRecoveryFailed,
                    mapOf("action" to action, "reason" to "UNSUPPORTED"),
                )
            }
            return
        }
        scope.launch {
            runCatching { recovery(store) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    updateState {
                        it.withLog(
                            clock(),
                            "error",
                            "TRUST-7402 Trust-store recovery failed; pairing and trust changes remain blocked",
                            AgentLogEventCode.TrustStoreRecoveryFailed,
                            mapOf("action" to action, "reason" to (error as? TrustStoreAccessException)?.reason?.name.orEmpty()),
                            error,
                        )
                    }
                }.onSuccess {
                    updateState {
                        it
                            .copy(
                                trustStoreRecoveryRequired = false,
                                trustStoreFailureReason = null,
                                authorizedDevices = emptyList(),
                            ).withLog(
                                clock(),
                                "warn",
                                "TRUST-7403 Trust store $action after explicit local confirmation; starting a new pairing session",
                                AgentLogEventCode.TrustStoreRecovered,
                                mapOf("action" to action),
                            )
                    }
                    restart()
                }
        }
    }

    fun revoke(remoteDeviceId: String) {
        if (state.value.trustStoreRecoveryRequired || remoteDeviceId.isBlank()) return
        scope.launch {
            val authorization =
                state.value.authorizedDevices.firstOrNull { it.remoteDeviceId.value == remoteDeviceId }
                    ?: trustStore.listAuthorizedDevices().firstOrNull { it.remoteDeviceId.value == remoteDeviceId }
                    ?: return@launch
            // Local Aegis revocation is fail-closed and never waits for a privileged SSH operation.
            closeDeviceSessionResources(remoteDeviceId)
            trustStore.revoke(remoteDeviceId)
            val requiresSshRemoval =
                authorization.approvedTransport == DeviceTrustTransport.LocalPairing &&
                    authorization.publicIdentity
                        ?.deviceId
                        ?.value
                        ?.isNotBlank() == true
            if (requiresSshRemoval) {
                val revokedAuthorization = trustStore.authorization(remoteDeviceId) ?: return@launch
                trustStore.saveAuthorization(
                    revokedAuthorization.copy(
                        sshKeyRemovalPending = true,
                        sshKeyRemovalFailureCode = null,
                    ),
                )
            }
            publishDeviceRevocation(remoteDeviceId)
            if (requiresSshRemoval) retrySshKeyRemoval(remoteDeviceId)
        }
    }

    /** Retries only a persisted, incomplete removal of this device's exact SSH key marker. */
    fun retrySshKeyRemoval(remoteDeviceId: String) {
        if (state.value.trustStoreRecoveryRequired || remoteDeviceId.isBlank() || !markSshKeyRemovalInProgress(remoteDeviceId)) return
        scope.launch {
            try {
                val authorization = trustStore.authorization(remoteDeviceId)
                val deviceId = authorization?.publicIdentity?.deviceId?.value
                if (authorization?.revokedAtEpochMillis == null || !authorization.sshKeyRemovalPending || deviceId.isNullOrBlank()) {
                    return@launch
                }
                val removal =
                    runCatching {
                        val provisioner = openSshProvisioner ?: error("Managed OpenSSH is unavailable")
                        provisioner.removeAuthorizedKey(deviceId)
                    }.rethrowCancellation()
                val removalResult = removal.getOrNull()
                if (removalResult?.let { it.removed && it.deviceId == deviceId } == true) {
                    trustStore.saveAuthorization(
                        authorization.copy(
                            sshKeyRemovalPending = false,
                            sshKeyRemovalFailureCode = null,
                        ),
                    )
                    refreshAuthorizedDevices(
                        AgentLogEventCode.SshKeyRemovalCompleted,
                        "Managed SSH key removal confirmed for revoked device $remoteDeviceId",
                        "info",
                        mapOf("remoteDeviceId" to remoteDeviceId, "action" to "confirmed"),
                    )
                } else {
                    val error = removal.exceptionOrNull()
                    val failureCode = error.sshKeyRemovalFailureCode()
                    trustStore.saveAuthorization(
                        authorization.copy(
                            sshKeyRemovalPending = true,
                            sshKeyRemovalFailureCode = failureCode,
                        ),
                    )
                    refreshAuthorizedDevices(
                        AgentLogEventCode.SshKeyRemovalPending,
                        "$failureCode Managed SSH key removal remains pending for revoked device $remoteDeviceId",
                        "error",
                        mapOf(
                            "remoteDeviceId" to remoteDeviceId,
                            "action" to "pending",
                            "failureCode" to failureCode,
                            "removalResult" to
                                when {
                                    error != null -> "exception"
                                    removalResult?.deviceId != deviceId -> "device-id-mismatch"
                                    else -> "removed=false"
                                },
                        ),
                        error,
                    )
                }
            } finally {
                updateState { it.copy(sshKeyRemovalActionsInProgress = it.sshKeyRemovalActionsInProgress - remoteDeviceId) }
            }
        }
    }

    private suspend fun publishDeviceRevocation(remoteDeviceId: String) {
        val authorizedDevices = trustStore.listAuthorizedDevices()
        updateState {
            it
                .copy(authorizedDevices = authorizedDevices)
                .withLog(
                    now = clock(),
                    level = "warn",
                    message = "Revoked device $remoteDeviceId; local control is blocked",
                    eventCode = AgentLogEventCode.DeviceRevoked,
                    context = mapOf("remoteDeviceId" to remoteDeviceId, "action" to "local-revoked"),
                )
        }
    }

    suspend fun refreshAuthorizedDevices(
        eventCode: AgentLogEventCode,
        message: String,
        level: String,
        context: Map<String, String>,
        error: Throwable? = null,
    ) {
        val authorizedDevices = trustStore.listAuthorizedDevices()
        updateState {
            it
                .copy(authorizedDevices = authorizedDevices)
                .withLog(clock(), level, message, eventCode, context, error)
        }
    }

    private fun markSshKeyRemovalInProgress(remoteDeviceId: String): Boolean {
        var marked = false
        updateState { current ->
            if (remoteDeviceId in current.sshKeyRemovalActionsInProgress) {
                current
            } else {
                marked = true
                current.copy(sshKeyRemovalActionsInProgress = current.sshKeyRemovalActionsInProgress + remoteDeviceId)
            }
        }
        return marked
    }

    /** Permanently deletes a trust record only after it has been revoked. */
    fun deleteRevokedDevice(remoteDeviceId: String) {
        if (state.value.trustStoreRecoveryRequired || remoteDeviceId.isBlank()) return
        updateState {
            it.copy(
                deviceRecordActionsInProgress = it.deviceRecordActionsInProgress + remoteDeviceId,
                lastDeviceRecordAction = null,
            )
        }
        scope.launch {
            val result = runCatching { resolveDeviceRecordDeletion(remoteDeviceId) }.rethrowCancellation()
            val outcome = result.getOrElse { DeviceRecordActionOutcome.Failed }
            val eventCode = deviceRecordDeletionEventCode(outcome)
            val message = deviceRecordDeletionMessage(remoteDeviceId, outcome)
            val authorizedDevices =
                if (outcome == DeviceRecordActionOutcome.Deleted) {
                    trustStore.listAuthorizedDevices()
                } else {
                    null
                }
            updateState {
                it
                    .copy(
                        authorizedDevices = authorizedDevices ?: it.authorizedDevices,
                        deviceRecordActionsInProgress = it.deviceRecordActionsInProgress - remoteDeviceId,
                        lastDeviceRecordAction = DeviceRecordActionResult(remoteDeviceId, outcome, clock()),
                    ).withLog(
                        now = clock(),
                        level = if (outcome == DeviceRecordActionOutcome.Deleted) "info" else "warn",
                        message = message,
                        eventCode = eventCode,
                        context =
                            mapOf(
                                "remoteDeviceId" to remoteDeviceId,
                                "outcome" to outcome.name,
                            ),
                        error = result.exceptionOrNull(),
                    )
            }
        }
    }

    private suspend fun resolveDeviceRecordDeletion(remoteDeviceId: String): DeviceRecordActionOutcome {
        val deletableStore =
            trustStore as? DeletableDeviceTrustStore
                ?: return DeviceRecordActionOutcome.Unsupported
        if (trustStore.authorization(remoteDeviceId)?.sshKeyRemovalPending == true) {
            return DeviceRecordActionOutcome.SshKeyRemovalPending
        }
        return when (deletableStore.deleteRevoked(remoteDeviceId)) {
            DeleteRevokedDeviceResult.Deleted -> DeviceRecordActionOutcome.Deleted
            DeleteRevokedDeviceResult.MustRevokeFirst -> DeviceRecordActionOutcome.MustRevokeFirst
            DeleteRevokedDeviceResult.NotFound -> DeviceRecordActionOutcome.NotFound
        }
    }

    private fun deviceRecordDeletionEventCode(outcome: DeviceRecordActionOutcome): AgentLogEventCode =
        when (outcome) {
            DeviceRecordActionOutcome.Deleted -> AgentLogEventCode.DeviceRecordDeleted

            DeviceRecordActionOutcome.MustRevokeFirst,
            DeviceRecordActionOutcome.NotFound,
            DeviceRecordActionOutcome.Unsupported,
            DeviceRecordActionOutcome.SshKeyRemovalPending,
            -> AgentLogEventCode.DeviceRecordDeletionRefused

            DeviceRecordActionOutcome.Failed -> AgentLogEventCode.DeviceRecordDeletionFailed
        }

    private fun deviceRecordDeletionMessage(
        remoteDeviceId: String,
        outcome: DeviceRecordActionOutcome,
    ): String =
        when (outcome) {
            DeviceRecordActionOutcome.Deleted -> {
                "Permanently deleted revoked device record $remoteDeviceId"
            }

            DeviceRecordActionOutcome.MustRevokeFirst -> {
                "Refused to delete active device record $remoteDeviceId; revoke it first"
            }

            DeviceRecordActionOutcome.NotFound -> {
                "Device record $remoteDeviceId was not found"
            }

            DeviceRecordActionOutcome.Unsupported -> {
                "The configured trust store does not support permanent record deletion"
            }

            DeviceRecordActionOutcome.SshKeyRemovalPending -> {
                "Refused to delete device record $remoteDeviceId while managed SSH key removal is pending"
            }

            DeviceRecordActionOutcome.Failed -> {
                "Failed to delete device record $remoteDeviceId"
            }
        }
}

private fun <T> Result<T>.rethrowCancellation(): Result<T> {
    exceptionOrNull()?.let { error ->
        if (error is CancellationException) throw error
    }
    return this
}
