package dev.aegis.remote.android.home

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aegis.remote.android.R
import dev.aegis.remote.android.clipboard.AndroidClipboardBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardProtocolBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardSyncGate
import dev.aegis.remote.android.input.AndroidInputProtocolBridge
import dev.aegis.remote.android.input.AndroidTrackpadGestureMapper
import dev.aegis.remote.android.input.AndroidVisualPointerMapper
import dev.aegis.remote.android.pairing.AndroidLocalPairingClient
import dev.aegis.remote.android.pairing.AndroidLocalPairingCrypto
import dev.aegis.remote.android.pairing.AndroidLocalPairingQrVerifier
import dev.aegis.remote.android.pairing.AndroidPairingIdentity
import dev.aegis.remote.android.pairing.AndroidPairingIdentityStore
import dev.aegis.remote.android.pairing.LocalPairingPollingEvent
import dev.aegis.remote.android.pairing.LocalPairingPollingOutcome
import dev.aegis.remote.android.pairing.PairingQrTransport
import dev.aegis.remote.android.pairing.awaitLocalPairingDecision
import dev.aegis.remote.android.pairing.isTransientLocalPairingFailure
import dev.aegis.remote.android.pairing.resolveVerifiedLocalPairingEndpoint
import dev.aegis.remote.android.routing.AndroidRouteHealthChecker
import dev.aegis.remote.android.routing.AndroidTurnConfigProvider
import dev.aegis.remote.android.routing.DEFAULT_ANDROID_STUN_URLS
import dev.aegis.remote.android.security.AndroidDeviceIdentityStore
import dev.aegis.remote.android.security.AndroidRemoteOperationException
import dev.aegis.remote.android.security.AndroidSshEnrollmentKeyStore
import dev.aegis.remote.android.security.AndroidSshHealthChecker
import dev.aegis.remote.android.security.encodePrivateKeyCredentials
import dev.aegis.remote.android.service.AegisSessionService
import dev.aegis.remote.android.webrtc.AndroidLocalProtocolClient
import dev.aegis.remote.android.webrtc.AndroidRtcLiveStats
import dev.aegis.remote.android.webrtc.AndroidWebRtcViewerSession
import dev.aegis.remote.android.wol.AndroidWakeOnLanSender
import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.model.MacAddress
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.SshCredentialsRef
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.model.WakeOnLanCapability
import dev.aegis.remote.core.model.WakeOnLanConfig
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingQrPayloadParser
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.localPairingDeviceProofPayload
import dev.aegis.remote.core.pairing.localPairingProofPayload
import dev.aegis.remote.core.pairing.localProtocolTokenPayload
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.routing.BasicConnectionRouteManager
import dev.aegis.remote.core.routing.ConnectionRouteManager
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.P256SessionE2ee
import dev.aegis.remote.core.security.SecureCredentialStore
import dev.aegis.remote.core.security.canonicalOpenSshSha256Fingerprint
import dev.aegis.remote.core.session.AdaptiveQualitySessionController
import dev.aegis.remote.core.session.ExponentialBackoffReconnectionManager
import dev.aegis.remote.core.session.PrepareVisualSessionUseCase
import dev.aegis.remote.core.session.RemoteSessionState
import dev.aegis.remote.core.session.ResolveSshRouteUseCase
import dev.aegis.remote.core.session.SshRoutePlan
import dev.aegis.remote.core.session.VisualReconnectionDecision
import dev.aegis.remote.core.session.VisualReconnectionDecisionContext
import dev.aegis.remote.core.session.VisualReconnectionPolicy
import dev.aegis.remote.core.session.VisualSessionPlan
import dev.aegis.remote.core.sftp.SftpPath
import dev.aegis.remote.core.sftp.SftpSession
import dev.aegis.remote.core.sftp.SftpTransferCancellation
import dev.aegis.remote.core.sftp.TransferProgress
import dev.aegis.remote.core.sftp.TransferResumeCapability
import dev.aegis.remote.core.storage.DeviceProfileRepository
import dev.aegis.remote.core.terminal.TerminalInput
import dev.aegis.remote.core.terminal.TerminalKeyStroke
import dev.aegis.remote.core.terminal.TerminalSession
import dev.aegis.remote.core.webrtc.DataChannelClient
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.protocol.ProtocolSignalingClient
import dev.aegis.remote.relayclient.KtorRelayClient
import dev.aegis.remote.relayclient.RelayIdentityLifecycleCoordinator
import dev.aegis.remote.relayclient.RelayIdentityRotationPendingException
import dev.aegis.remote.relayclient.openE2eeProtocolMessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.SurfaceViewRenderer
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal class ProfileCoordinator(
    private val state: StateFlow<AndroidHomeUiState>,
    private val scope: CoroutineScope,
    private val application: Application,
    private val repository: DeviceProfileRepository,
    private val credentialStore: SecureCredentialStore,
    private val sshHealthChecker: AndroidSshHealthChecker,
    private val routeManager: () -> ConnectionRouteManager,
    private val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
) {
    fun saveDraft() {
        val current = state.value
        val draft = current.draft
        val validationError = draft.validationError()
        if (validationError != null) {
            updateState { it.copy(errorMessage = validationError) }
            return
        }

        scope.launch {
            var newlyCreatedCredentialRef: SshCredentialsRef? = null
            runCatching {
                val credentialRef =
                    if (draft.canReuseExistingCredential()) {
                        draft.existingCredentialsRef ?: error("Existing credential reference is missing")
                    } else {
                        val credentialSecret = draft.sshCredentialSecret()
                        try {
                            credentialStore
                                .putSecret(
                                    label = "${draft.displayName}-ssh-${draft.authMethod.name.lowercase()}",
                                    secret = credentialSecret,
                                ).also { newlyCreatedCredentialRef = it }
                        } finally {
                            credentialSecret.fill(0)
                        }
                    }
                val existingProfile = current.profiles.firstOrNull { it.id == draft.editingProfileId }
                val profile = draft.toProfile(credentialRef, existingProfile)
                repository.saveProfile(profile)
                existingProfile
                    ?.credentialsRef
                    ?.takeIf { it != credentialRef }
                    ?.let { oldRef -> runCatching { credentialStore.deleteSecret(oldRef) } }
                updateState {
                    it.copy(
                        screen = AndroidHomeScreenMode.Detail,
                        selectedProfileId = profile.id,
                        draft = AddProfileDraft(),
                        errorMessage = null,
                    )
                }
            }.onFailure { error ->
                newlyCreatedCredentialRef?.let { ref -> runCatching { credentialStore.deleteSecret(ref) } }
                if (error is CancellationException) throw error
                updateState {
                    it.copy(errorMessage = error.message ?: "Could not save the SSH configuration")
                }
            }
        }
    }

    fun editSelectedProfile() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile == null) {
            updateState { it.copy(errorMessage = "Select a profile before editing.") }
            return
        }
        updateState {
            it.copy(
                screen = AndroidHomeScreenMode.AddProfile,
                draft = profile.toEditDraft(),
                errorMessage = null,
                routeDiagnosticsVisible = false,
            )
        }
    }

    fun deleteProfile(id: DeviceProfileId) {
        scope.launch {
            val profile = repository.getProfile(id)
            repository.deleteProfile(id)
            profile
                ?.let { listOfNotNull(it.credentialsRef, it.localProtocolTokenRef).distinct() }
                ?.forEach { reference -> runCatching { credentialStore.deleteSecret(reference) } }
            updateState {
                it.copy(screen = AndroidHomeScreenMode.List, selectedProfileId = null, routeDiagnosticsVisible = false)
            }
        }
    }

    fun testRoutes() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId } ?: return
        updateState { it.copy(routeDiagnosticsVisible = true, routeTesting = true, routeDiagnostics = null) }
        scope.launch {
            val diagnostics = routeManager().detectAvailableRoutes(profile)
            updateState {
                it.copy(
                    routeTesting = false,
                    routeDiagnosticsVisible = true,
                    routeDiagnostics = diagnostics,
                )
            }
        }
    }

    fun checkSelectedSshHealth() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile == null) {
            updateState { it.copy(sshHealth = SshHealthUiState(message = application.getString(R.string.ssh_health_need_profile))) }
            return
        }
        if (profile.credentialsRef == null || profile.hostKeyFingerprint == null || profile.username.isBlank()) {
            updateState {
                it.copy(
                    sshHealth =
                        SshHealthUiState(
                            healthy = false,
                            message = application.getString(R.string.ssh_health_need_key),
                        ),
                )
            }
            return
        }
        updateState {
            it.copy(sshHealth = SshHealthUiState(checking = true, message = application.getString(R.string.ssh_health_checking)))
        }
        scope.launch {
            runCatching { sshHealthChecker.check(profile) }
                .onSuccess { health ->
                    updateState {
                        it.copy(
                            sshHealth =
                                SshHealthUiState(
                                    healthy = true,
                                    message = application.getString(R.string.ssh_health_ok, health.endpoint, health.elapsedMillis.toInt()),
                                ),
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    val message =
                        (error as? AndroidRemoteOperationException)?.failure?.let { failure ->
                            "${failure.code}: ${failure.summary}. ${failure.nextAction.orEmpty()} " +
                                "Correlation: ${failure.correlationId}"
                        } ?: (error.message ?: application.getString(R.string.ssh_health_failed))
                    updateState {
                        it.copy(sshHealth = SshHealthUiState(healthy = false, message = message))
                    }
                }
        }
    }
}
