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
import dev.aegis.remote.core.model.withAdvertisedPairingUrls
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

internal data class PairingCoordinatorDependencies(
    val state: StateFlow<AndroidHomeUiState>,
    val scope: CoroutineScope,
    val cleanupScope: CoroutineScope,
    val application: Application,
    val repository: DeviceProfileRepository,
    val credentialStore: SecureCredentialStore,
    val localPairingClient: AndroidLocalPairingClient,
    val localPairingQrPayloadParser: LocalPairingQrPayloadParser,
    val localPairingQrVerifier: AndroidLocalPairingQrVerifier,
    val localPairingCrypto: AndroidLocalPairingCrypto,
    val localPairingIdentityStore: AndroidPairingIdentityStore,
    val deviceIdentityStore: AndroidDeviceIdentityStore,
    val sshEnrollmentKeyStore: AndroidSshEnrollmentKeyStore,
    val sshHealthChecker: AndroidSshHealthChecker,
    val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
)

internal class PairingCoordinator(
    private val dependencies: PairingCoordinatorDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val cleanupScope = dependencies.cleanupScope
    private val application = dependencies.application
    private val repository = dependencies.repository
    private val credentialStore = dependencies.credentialStore
    private val localPairingClient = dependencies.localPairingClient
    private val localPairingQrPayloadParser = dependencies.localPairingQrPayloadParser
    private val localPairingQrVerifier = dependencies.localPairingQrVerifier
    private val localPairingCrypto = dependencies.localPairingCrypto
    private val localPairingIdentityStore = dependencies.localPairingIdentityStore
    private val deviceIdentityStore = dependencies.deviceIdentityStore
    private val sshEnrollmentKeyStore = dependencies.sshEnrollmentKeyStore
    private val sshHealthChecker = dependencies.sshHealthChecker
    private val updateState = dependencies.updateState
    private var pairingJob: Job? = null

    val activeJob: Job?
        get() = pairingJob

    fun submit() {
        submitLocalPairing()
    }

    fun checkStatus() {
        checkLocalPairingStatus()
    }

    fun importQrPayload() {
        importLocalPairingQrPayload()
    }

    fun importQrPayload(
        rawPayload: String,
        autoSubmit: Boolean,
    ) {
        importLocalPairingQrPayload(rawPayload, autoSubmit)
    }

    fun cancel(resetDraft: Boolean) {
        cancelLocalPairing(resetDraft)
    }

    fun close() {
        cancelLocalPairing(resetDraft = false)
    }

    fun log(message: String) {
        pairingLog(message)
    }

    fun initialDraft(): LocalPairingDraft =
        runCatching { localPairingIdentityStore.get() }
            .map { identity -> LocalPairingDraft(phoneName = identity.displayName) }
            .getOrDefault(LocalPairingDraft())

    private fun submitLocalPairing() {
        val draft = state.value.localPairingDraft
        val validationError = draft.validationError()
        if (validationError != null) {
            updateState { it.copy(localPairingStage = LocalPairingStage.Failed, errorMessage = validationError) }
            return
        }
        if (!draft.requestId.isNullOrBlank()) {
            pairingLog("Resuming status polling for existing request ${draft.requestId.logId()}")
            checkLocalPairingStatus()
            return
        }
        if (pairingJob?.isActive == true) {
            pairingLog("Ignored duplicate pairing submit while the current attempt is active")
            return
        }
        startLocalPairing(draft)
    }

    private fun checkLocalPairingStatus() {
        if (pairingJob?.isActive == true) return
        val draft = state.value.localPairingDraft
        val requestId = draft.requestId
        if (requestId.isNullOrBlank()) {
            updateState { it.copy(errorMessage = "Submit the pairing request first") }
            return
        }
        val identity =
            runCatching { localPairingIdentityStore.get() }
                .getOrElse { error ->
                    updateState { it.copy(errorMessage = error.message ?: "Could not load the phone pairing identity") }
                    return
                }
        updateState {
            it.copy(
                localPairingBusy = true,
                localPairingMessage = "Waiting for approval on the PC...",
                localPairingStage = LocalPairingStage.AwaitingApproval,
                errorMessage = null,
            )
        }
        val statusJob =
            scope.launch {
                runCatching { pollLocalPairingStatus(draft, requestId, identity) }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            pairingLog("Status polling cancelled because the pairing flow was closed")
                            throw error
                        }
                        failLocalPairing(
                            error = error,
                            fallback = "Could not check the pairing decision. You can retry without sending another request.",
                            stage =
                                if (error.isTransientLocalPairingFailure()) {
                                    LocalPairingStage.NetworkError
                                } else {
                                    LocalPairingStage.Failed
                                },
                            preserveRequestId = true,
                        )
                    }
            }
        pairingJob = statusJob
        statusJob.invokeOnCompletion {
            if (pairingJob === statusJob) pairingJob = null
        }
    }

    private fun importLocalPairingQrPayload() {
        importLocalPairingQrPayload(state.value.localPairingDraft.qrPayload, autoSubmit = false)
    }

    private fun importLocalPairingQrPayload(
        rawPayload: String,
        autoSubmit: Boolean = false,
    ) {
        runCatching {
            pairingLog(
                "Scanned pairing QR (${rawPayload.length} chars, prefix=${rawPayload.take(16)})",
            )
            localPairingQrVerifier.verify(localPairingQrPayloadParser.parse(PairingQrTransport.decode(rawPayload)))
        }.onSuccess { payload ->
            val currentDraft = state.value.localPairingDraft
            val sameAttempt =
                currentDraft.agentFingerprint == payload.agentFingerprint &&
                    currentDraft.pairingCode == payload.pairingCode &&
                    currentDraft.pairingSecret == payload.pairingSecret
            if (autoSubmit && sameAttempt && pairingJob?.isActive == true) {
                pairingLog("Ignored duplicate camera detection for the active QR pairing attempt")
                return@onSuccess
            }
            if (autoSubmit && sameAttempt && !currentDraft.requestId.isNullOrBlank()) {
                pairingLog(
                    "Camera detected the same QR; resuming request ${currentDraft.requestId.logId()} instead of submitting again",
                )
                checkLocalPairingStatus()
                return@onSuccess
            }

            pairingJob?.cancel()
            pairingJob = null
            val identityName =
                runCatching { localPairingIdentityStore.get().displayName }
                    .getOrDefault(
                        state.value.localPairingDraft.phoneName
                            .ifBlank { "Android device" },
                    )
            val importedDraft =
                LocalPairingDraft(
                    qrPayload = rawPayload.takeUnless { autoSubmit }.orEmpty(),
                    pairingUrl = payload.pairingUrl,
                    pairingUrls = payload.candidatePairingUrls(),
                    pairingCode = payload.pairingCode,
                    phoneName = identityName,
                    agentFingerprint = payload.agentFingerprint,
                    pairingSecret = payload.pairingSecret,
                    qrTokenId = payload.tokenId,
                    issuedAtEpochMillis = payload.issuedAtEpochMillis,
                    expiresAtEpochMillis = payload.expiresAtEpochMillis,
                    hostIdentity = payload.hostIdentity,
                )
            updateState {
                it.copy(
                    localPairingDraft = importedDraft,
                    localPairingBusy = false,
                    localPairingMessage = if (autoSubmit) "QR verified. Contacting the PC..." else "QR verified and ready to pair.",
                    localPairingStage =
                        if (autoSubmit) {
                            LocalPairingStage.VerifyingDesktop
                        } else {
                            LocalPairingStage.Scanning
                        },
                    errorMessage = null,
                )
            }
            if (autoSubmit) startLocalPairing(importedDraft)
        }.onFailure { error ->
            pairingLog("Rejected scanned pairing QR: ${error.message ?: error::class.java.simpleName}", error)
            updateState {
                it.copy(
                    localPairingBusy = false,
                    localPairingMessage = null,
                    localPairingStage = LocalPairingStage.Failed,
                    errorMessage = error.message ?: "Invalid local pairing QR payload",
                )
            }
        }
    }

    private val requestCoordinator: PairingRequestCoordinator by lazy {
        PairingRequestCoordinator(
            dependencies = dependencies,
            pairingLog = ::pairingLog,
            pollLocalPairingStatus = ::pollLocalPairingStatus,
            finishLocalPairingRequest = ::finishLocalPairingRequest,
            failLocalPairing = ::failLocalPairing,
        )
    }

    private fun startLocalPairing(sourceDraft: LocalPairingDraft) {
        if (pairingJob?.isActive == true) {
            pairingLog("Ignored duplicate start while a pairing attempt is active")
            return
        }
        if (!sourceDraft.requestId.isNullOrBlank()) {
            checkLocalPairingStatus()
            return
        }
        val validationError = sourceDraft.validationError()
        if (validationError != null) {
            updateState {
                it.copy(
                    localPairingBusy = false,
                    localPairingStage = LocalPairingStage.Failed,
                    errorMessage = validationError,
                )
            }
            return
        }
        pairingJob?.cancel()
        val attemptJob = requestCoordinator.start(sourceDraft)
        pairingJob = attemptJob
        attemptJob.invokeOnCompletion {
            if (pairingJob === attemptJob) pairingJob = null
        }
    }

    private suspend fun pollLocalPairingStatus(
        draft: LocalPairingDraft,
        requestId: String,
        identity: AndroidPairingIdentity,
    ) {
        var pendingCheckCount = 0
        val outcome =
            awaitLocalPairingDecision(
                client = localPairingClient,
                pairingUrl = draft.pairingUrl,
                requestId = requestId,
                onEvent = { event ->
                    when (event) {
                        is LocalPairingPollingEvent.Pending -> {
                            pendingCheckCount += 1
                            if (pendingCheckCount == 1 || pendingCheckCount % 10 == 0) {
                                pairingLog("Request ${requestId.logId()} is pending approval (status check $pendingCheckCount)")
                            }
                            updateState {
                                it.copy(
                                    localPairingBusy = true,
                                    localPairingMessage = event.response.message,
                                    localPairingStage = LocalPairingStage.AwaitingApproval,
                                    errorMessage = null,
                                )
                            }
                        }

                        is LocalPairingPollingEvent.RetryingConnection -> {
                            pairingLog(
                                "Status check ${event.consecutiveFailureCount} failed for request ${requestId.logId()}; " +
                                    "retrying in ${event.nextDelayMillis} ms",
                                event.failure,
                            )
                            updateState {
                                it.copy(
                                    localPairingBusy = true,
                                    localPairingMessage = "The request is still active. Reconnecting to the PC...",
                                    localPairingStage = LocalPairingStage.RetryingConnection,
                                    errorMessage = null,
                                )
                            }
                        }
                    }
                },
            )
        when (outcome) {
            is LocalPairingPollingOutcome.Terminal -> {
                val status = outcome.response
                pairingLog("Request ${requestId.logId()} reached ${status.status}")
                when (status.status) {
                    LocalPairingRequestStatus.Approved -> {
                        val profile = status.profile ?: error("Desktop approved pairing without profile data")
                        completeApprovedLocalPairing(draft, requestId, identity, profile, status.message)
                    }

                    LocalPairingRequestStatus.Pending -> {
                        error("Pairing poller returned a non-terminal pending response")
                    }

                    LocalPairingRequestStatus.Rejected,
                    LocalPairingRequestStatus.Expired,
                    LocalPairingRequestStatus.Unknown,
                    -> {
                        finishLocalPairingRequest(status.status, status.message, draft)
                    }
                }
            }

            is LocalPairingPollingOutcome.DeadlineExceeded -> {
                val networkFailure = outcome.lastNetworkFailure
                if (networkFailure != null) {
                    failLocalPairing(
                        error = networkFailure,
                        fallback = "The PC could not be reached before the approval window closed.",
                        stage = LocalPairingStage.NetworkError,
                        preserveRequestId = true,
                    )
                } else {
                    finishLocalPairingRequest(
                        LocalPairingRequestStatus.Expired,
                        "The PC approval window expired. Scan the current QR code to try again.",
                        draft,
                    )
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun completeApprovedLocalPairing(
        draft: LocalPairingDraft,
        requestId: String,
        identity: AndroidPairingIdentity,
        profile: LocalPairedProfile,
        message: String,
    ) {
        require(profile.agentFingerprint == draft.agentFingerprint) {
            "Approved desktop fingerprint does not match the scanned QR code"
        }
        val expectedHostIdentity = requireNotNull(draft.hostIdentity) { "QRP-7102: Scanned QR has no host identity" }
        require(profile.hostIdentity?.matches(expectedHostIdentity) == true) {
            "QRP-7108: Approved host identity does not match the signed QR"
        }
        val sshCredentialRef = requireNotNull(draft.sshCredentialRef) { "SSH-7003: Pairing lost the generated SSH key" }
        val sshHostKey = requireNotNull(profile.sshHostKeyFingerprint) { "SSH-7004: The desktop did not return its SSH host key" }
        require(profile.username.isNotBlank()) { "SSH-7005: The desktop did not return the SSH username" }
        val profileId = pairedProfileIdForHost(expectedHostIdentity)
        val profiles = state.value.profiles
        val stableProfile = profiles.firstOrNull { it.id == profileId }
        require(
            stableProfile == null ||
                stableProfile.pairedHostIdentity?.matches(expectedHostIdentity) == true ||
                (
                    stableProfile.pairedHostIdentity == null &&
                        stableProfile.localAgentCertificateFingerprint == draft.agentFingerprint
                ),
        ) { "QRP-7109: Existing host profile identity conflicts with the signed QR" }
        val matchingIdentityProfile =
            profiles.firstOrNull { candidate ->
                candidate.pairedHostIdentity?.matches(expectedHostIdentity) == true
            }
        val legacyProfile =
            profiles
                .firstOrNull { candidate -> candidate.id == DeviceProfileId("paired-${profile.authorizedDeviceId}") }
                ?.takeIf { candidate ->
                    candidate.pairedHostIdentity?.matches(expectedHostIdentity) == true ||
                        (
                            candidate.pairedHostIdentity == null &&
                                candidate.localAgentCertificateFingerprint == draft.agentFingerprint
                        )
                }
        val existing = stableProfile ?: matchingIdentityProfile ?: legacyProfile
        val tokenRef =
            credentialStore.putSecret(
                label = "${profile.displayName}-local-protocol-token",
                secret =
                    localPairingCrypto
                        .derive(
                            draft.pairingSecret,
                            localProtocolTokenPayload(requestId),
                        ).toByteArray(Charsets.UTF_8),
            )
        val saved =
            DeviceProfile(
                id = profileId,
                displayName = profile.displayName,
                localHost = HostAddress(profile.localHost),
                sshPort = profile.sshPort,
                username = profile.username,
                authMethod = AuthMethod.PrivateKey,
                credentialsRef = sshCredentialRef,
                localProtocolTokenRef = tokenRef,
                localAgentCertificateFingerprint = draft.agentFingerprint,
                authorizedDeviceId = profile.authorizedDeviceId,
                hostKeyFingerprint = sshHostKey,
                pairedHostIdentity = expectedHostIdentity,
                wakeOnLanConfig =
                    profile.wakeOnLanAdapters.firstOrNull { it.capability == WakeOnLanCapability.Supported }
                        ?: profile.wakeOnLanAdapters.firstOrNull(),
                availableWakeOnLanConfigs = profile.wakeOnLanAdapters,
                permissions = profile.permissions,
                remoteAccessEnabled = profile.permissions.remoteAccess,
                lastConnectedAtEpochMillis = existing?.lastConnectedAtEpochMillis,
                lastSuccessfulRoute = existing?.lastSuccessfulRoute,
                defaultMonitorId = existing?.defaultMonitorId,
                qualityPreference = existing?.qualityPreference ?: dev.aegis.remote.core.model.QualityMode.Balanced,
                vpnHost = existing?.vpnHost,
            ).withAdvertisedPairingUrls(
                listOfNotNull(draft.pairingUrl.takeIf { it.isNotBlank() }) + draft.pairingUrls,
            )
        repository.saveProfile(saved)
        if (existing != null && existing.id != saved.id) {
            repository.deleteProfile(existing.id)
        }
        existing
            ?.localProtocolTokenRef
            ?.takeIf { it != tokenRef }
            ?.let { oldRef -> runCatching { credentialStore.deleteSecret(oldRef) } }
        existing
            ?.credentialsRef
            ?.takeIf { it != sshCredentialRef }
            ?.let { oldRef -> runCatching { credentialStore.deleteSecret(oldRef) } }
        updateState { current ->
            current.copy(
                localPairingBusy = true,
                screen = AndroidHomeScreenMode.Detail,
                selectedProfileId = saved.id,
                localPairingMessage = application.getString(R.string.ssh_health_after_pairing),
                sshHealth = SshHealthUiState(checking = true, message = application.getString(R.string.ssh_health_after_pairing)),
                errorMessage = null,
            )
        }
        val healthResult = runCatching { sshHealthChecker.check(saved) }
        updateState { current ->
            healthResult.fold(
                onSuccess = { health ->
                    current.copy(
                        localPairingBusy = false,
                        selectedProfileId = saved.id,
                        localPairingDraft = LocalPairingDraft(phoneName = identity.displayName),
                        localPairingMessage = message,
                        localPairingStage = LocalPairingStage.Approved,
                        sshHealth =
                            SshHealthUiState(
                                checking = false,
                                healthy = true,
                                message = application.getString(R.string.ssh_health_ok, health.endpoint, health.elapsedMillis.toInt()),
                            ),
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    val failureMessage =
                        (error as? AndroidRemoteOperationException)?.failure?.let { failure ->
                            buildString {
                                append(failure.code)
                                append(": ")
                                append(failure.summary)
                                failure.nextAction?.let { append(" ").append(it) }
                                append(" Correlation: ").append(failure.correlationId)
                            }
                        } ?: (error.message ?: application.getString(R.string.ssh_health_failed))
                    current.copy(
                        localPairingBusy = false,
                        selectedProfileId = saved.id,
                        localPairingDraft = LocalPairingDraft(phoneName = identity.displayName),
                        localPairingMessage = failureMessage,
                        localPairingStage = LocalPairingStage.NetworkError,
                        sshHealth = SshHealthUiState(checking = false, healthy = false, message = failureMessage),
                        errorMessage = failureMessage,
                    )
                },
            )
        }
    }

    private fun finishLocalPairingRequest(
        status: LocalPairingRequestStatus,
        message: String,
        draft: LocalPairingDraft,
    ) {
        if (status == LocalPairingRequestStatus.Rejected ||
            status == LocalPairingRequestStatus.Expired ||
            status == LocalPairingRequestStatus.Unknown
        ) {
            draft.sshCredentialRef?.let { reference ->
                cleanupScope.launch { runCatching { credentialStore.deleteSecret(reference) } }
            }
        }
        updateState {
            it.copy(
                localPairingBusy = false,
                localPairingDraft =
                    draft.copy(
                        requestId = null,
                        sshCredentialRef = null,
                        sshPublicKey = "",
                    ),
                localPairingMessage = null,
                localPairingStage =
                    when (status) {
                        LocalPairingRequestStatus.Rejected -> LocalPairingStage.Rejected

                        LocalPairingRequestStatus.Expired,
                        LocalPairingRequestStatus.Unknown,
                        -> LocalPairingStage.Expired

                        LocalPairingRequestStatus.Approved -> LocalPairingStage.Approved

                        LocalPairingRequestStatus.Pending -> LocalPairingStage.AwaitingApproval
                    },
                errorMessage =
                    when (status) {
                        LocalPairingRequestStatus.Unknown -> "The PC no longer recognizes this pairing request. $message"

                        LocalPairingRequestStatus.Rejected,
                        LocalPairingRequestStatus.Expired,
                        -> message

                        else -> message
                    },
            )
        }
    }

    private fun failLocalPairing(
        error: Throwable,
        fallback: String,
        stage: LocalPairingStage = LocalPairingStage.Failed,
        preserveRequestId: Boolean = false,
    ) {
        pairingLog("Pairing flow entered $stage: ${error.message ?: error::class.java.simpleName}", error)
        if (!preserveRequestId) {
            state.value.localPairingDraft.sshCredentialRef?.let { reference ->
                cleanupScope.launch { runCatching { credentialStore.deleteSecret(reference) } }
            }
        }
        updateState {
            it.copy(
                localPairingBusy = false,
                localPairingDraft =
                    if (preserveRequestId) {
                        it.localPairingDraft
                    } else {
                        it.localPairingDraft.copy(requestId = null, sshCredentialRef = null, sshPublicKey = "")
                    },
                localPairingMessage = null,
                localPairingStage = stage,
                errorMessage =
                    when (error) {
                        is IllegalArgumentException -> error.message ?: fallback
                        else -> fallback
                    },
            )
        }
    }

    private fun cancelLocalPairing(resetDraft: Boolean) {
        pairingJob?.cancel()
        pairingJob = null
        if (resetDraft) {
            state.value.localPairingDraft.sshCredentialRef?.let { reference ->
                cleanupScope.launch { runCatching { credentialStore.deleteSecret(reference) } }
            }
            updateState {
                it.copy(
                    localPairingDraft = LocalPairingDraft(),
                    localPairingBusy = false,
                    localPairingMessage = null,
                    localPairingStage = LocalPairingStage.Scanning,
                    errorMessage = null,
                )
            }
        }
    }

    private fun pairingLog(
        message: String,
        error: Throwable? = null,
    ) {
        if (error == null) {
            Log.i(LOCAL_PAIRING_LOG_TAG, message)
        } else {
            Log.w(LOCAL_PAIRING_LOG_TAG, message, error)
        }
    }
}
