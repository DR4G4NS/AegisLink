package dev.aegis.remote.android.home

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.aegis.remote.android.R
import dev.aegis.remote.android.clipboard.AndroidClipboardBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardProtocolBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardSyncGate
import dev.aegis.remote.android.input.AndroidInputProtocolBridge
import dev.aegis.remote.android.input.AndroidTrackpadGestureMapper
import dev.aegis.remote.android.input.AndroidVisualPointerMapper
import dev.aegis.remote.android.pairing.AndroidLanRediscovery
import dev.aegis.remote.android.pairing.AndroidLocalPairingClient
import dev.aegis.remote.android.pairing.AndroidLocalPairingCrypto
import dev.aegis.remote.android.pairing.AndroidLocalPairingQrVerifier
import dev.aegis.remote.android.pairing.AndroidPairingIdentity
import dev.aegis.remote.android.pairing.AndroidPairingIdentityStore
import dev.aegis.remote.android.pairing.LanHostSyncOutcome
import dev.aegis.remote.android.pairing.LocalPairingPollingEvent
import dev.aegis.remote.android.pairing.LocalPairingPollingOutcome
import dev.aegis.remote.android.pairing.PairingQrTransport
import dev.aegis.remote.android.pairing.awaitLocalPairingDecision
import dev.aegis.remote.android.pairing.isTransientLocalPairingFailure
import dev.aegis.remote.android.pairing.resolveVerifiedLocalPairingEndpoint
import dev.aegis.remote.android.routing.AndroidTurnConfigProvider
import dev.aegis.remote.android.routing.DEFAULT_ANDROID_STUN_URLS
import dev.aegis.remote.android.security.AndroidDeviceIdentityStore
import dev.aegis.remote.android.security.AndroidRemoteOperationException
import dev.aegis.remote.android.security.AndroidSshEnrollmentKeyStore
import dev.aegis.remote.android.security.AndroidSshHealthChecker
import dev.aegis.remote.android.security.encodePrivateKeyCredentials
import dev.aegis.remote.android.service.AegisSessionService
import dev.aegis.remote.android.ui.components.labelRes
import dev.aegis.remote.android.webrtc.AndroidLocalProtocolClient
import dev.aegis.remote.android.webrtc.AndroidRtcLiveStats
import dev.aegis.remote.android.webrtc.AndroidWebRtcViewerSession
import dev.aegis.remote.android.wol.AndroidWakeOnLanSender
import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
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
import dev.aegis.remote.core.model.stableAdvertisedEndpoints
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingQrPayloadParser
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.localPairingDeviceProofPayload
import dev.aegis.remote.core.pairing.localPairingProofPayload
import dev.aegis.remote.core.pairing.localProtocolTokenPayload
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.routing.ConnectionRouteManager
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.P256SessionE2ee
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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

class AndroidHomeViewModel internal constructor(
    application: Application,
    // The public application-only constructor remains the compatibility API;
    // this constructor is intentionally limited to the composition root.
    internal val dependencies: AndroidHomeDependencies,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AegisAndroidAppGraph(application).homeDependencies)

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = dependencies.profileRepository
    private val relayConfigRepository = dependencies.relayConfigRepository
    private val credentialStore = dependencies.credentialStore
    private val deviceIdentityStore = dependencies.deviceIdentityStore
    private val localPairingClient = dependencies.localPairingClient
    private val localPairingQrPayloadParser = dependencies.localPairingQrPayloadParser
    private val localPairingQrVerifier = dependencies.localPairingQrVerifier
    private val localPairingCrypto = dependencies.localPairingCrypto
    private val sshEnrollmentKeyStore = dependencies.sshEnrollmentKeyStore
    private val sshHealthChecker = dependencies.sshHealthChecker
    private val terminalClient = dependencies.terminalClient
    private val sftpClient = dependencies.sftpClient
    private val wakeOnLanSender = dependencies.wakeOnLanSender
    private val terminalReconnectionManager = dependencies.terminalReconnectionManager
    private val relayReconnectionManager = dependencies.relayReconnectionManager
    private val turnConfigProvider = dependencies.turnConfigProvider
    private val _state = MutableStateFlow(AndroidHomeUiState())
    val state: StateFlow<AndroidHomeUiState> = _state

    private val relayCoordinator: AndroidRelayCoordinator by lazy {
        AndroidRelayCoordinator(
            AndroidRelayCoordinatorDependencies(
                state = state,
                scope = viewModelScope,
                repository = repository,
                relayConfigRepository = relayConfigRepository,
                deviceIdentityStore = deviceIdentityStore,
                relayReconnectionManager = relayReconnectionManager,
                turnConfigProvider = turnConfigProvider,
                relayClientSlot = dependencies.relayClientSlot,
                relayClientFactory = dependencies.relayClientFactory,
                closeVisualSession = { visualCoordinator.closeVisualSession(leaveScreen = true, terminateRemoteSession = true) },
                stopClipboardSync = { remoteInteractionCoordinator.stopAutomaticClipboardSync(resetState = false) },
                remoteSessionCoordinatorProvider = { relayCoordinator.sessionCoordinator() },
                updateState = { update -> _state.update(update) },
            ),
        )
    }

    private val remoteInteractionCoordinator: AndroidRemoteInteractionCoordinator by lazy {
        AndroidRemoteInteractionCoordinator(
            AndroidRemoteInteractionDependencies(
                state = state,
                scope = viewModelScope,
                inputProtocolBridge = dependencies.inputProtocolBridge,
                clipboardBridge = dependencies.clipboardBridge,
                clipboardProtocolBridge = dependencies.clipboardProtocolBridge,
                clipboardSyncGate = dependencies.clipboardSyncGate,
                visualSessionCoordinatorProvider = { visualCoordinator },
                sendRelayProtocolMessage = relayCoordinator::sendProtocolMessage,
                updateState = { update -> _state.update(update) },
            ),
        )
    }

    private val visualCoordinator: RemoteSessionCoordinator by lazy {
        RemoteSessionCoordinator(
            state = state,
            scope = viewModelScope,
            dependencies =
                RemoteSessionDependencies(
                    application = getApplication(),
                    repository = repository,
                    credentialStore = credentialStore,
                    deviceIdentityStore = deviceIdentityStore,
                    visualProtocolBridge = dependencies.visualProtocolBridge,
                    clipboardBridge = dependencies.clipboardBridge,
                    clipboardSyncGate = dependencies.clipboardSyncGate,
                    trackpadGestureMapper = dependencies.trackpadGestureMapper,
                    visualPointerMapper = dependencies.visualPointerMapper,
                    turnConfigProvider = dependencies.turnConfigProvider,
                    routeManager = ::routeManager,
                    relayClientProvider = { relayCoordinator.client },
                    sessionCoordinatorProvider = { relayCoordinator.sessionCoordinator() },
                    takeRelayE2eeChannel = relayCoordinator::takeRelayE2eeProtocolMessageChannel,
                    clearRelaySessionArtifacts = relayCoordinator::clearRelaySessionArtifacts,
                    onProfileConnected = ::markProfileConnected,
                    onSessionIdle = ::stopForegroundServiceIfIdle,
                    onIncomingClipboardText = { text -> remoteInteractionCoordinator.markIncomingClipboardText(text) },
                    onHostUnlinked = { profileId ->
                        state.value.profiles.firstOrNull { it.id == profileId }?.let { profile ->
                            viewModelScope.launch { unlinkRevokedHost(profile) }
                        }
                    },
                ),
            updateState = { update -> _state.update(update) },
        )
    }

    private val terminalCoordinator: TerminalCoordinator by lazy {
        TerminalCoordinator(
            state = state,
            scope = viewModelScope,
            cleanupScope = cleanupScope,
            terminalClient = terminalClient,
            reconnectionManager = terminalReconnectionManager,
            resolveRoute = ::resolveSshRoute,
            onProfileConnected = ::markProfileConnected,
            onSessionStarted = { AegisSessionService.start(getApplication()) },
            onSessionIdle = ::stopForegroundServiceIfIdle,
            updateState = { update -> _state.update(update) },
        )
    }

    private val sftpCoordinator: SftpCoordinator by lazy {
        SftpCoordinator(
            state = state,
            scope = viewModelScope,
            cleanupScope = cleanupScope,
            sftpClient = sftpClient,
            contentResolver = getApplication<Application>().contentResolver,
            resolveRoute = ::resolveSshRoute,
            onProfileConnected = ::markProfileConnected,
            onSessionStarted = { AegisSessionService.start(getApplication()) },
            onSessionIdle = ::stopForegroundServiceIfIdle,
            updateState = { update -> _state.update(update) },
        )
    }

    private val pairingCoordinator: PairingCoordinator by lazy {
        PairingCoordinator(
            PairingCoordinatorDependencies(
                state = state,
                scope = viewModelScope,
                cleanupScope = cleanupScope,
                application = getApplication(),
                repository = repository,
                credentialStore = credentialStore,
                localPairingClient = localPairingClient,
                localPairingQrPayloadParser = localPairingQrPayloadParser,
                localPairingQrVerifier = localPairingQrVerifier,
                localPairingCrypto = localPairingCrypto,
                localPairingIdentityStore = dependencies.localPairingIdentityStore,
                deviceIdentityStore = deviceIdentityStore,
                sshEnrollmentKeyStore = sshEnrollmentKeyStore,
                sshHealthChecker = sshHealthChecker,
                updateState = { update -> _state.update(update) },
            ),
        )
    }

    private val profileCoordinator: ProfileCoordinator by lazy {
        ProfileCoordinator(
            state = state,
            scope = viewModelScope,
            application = getApplication(),
            repository = repository,
            credentialStore = credentialStore,
            sshHealthChecker = sshHealthChecker,
            routeManager = ::routeManager,
            updateState = { update -> _state.update(update) },
        )
    }

    private val processLifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                visualCoordinator.onApplicationBackground()
            }

            override fun onStart(owner: LifecycleOwner) {
                val backgroundedAt = visualCoordinator.lastBackgroundedAtEpochMillis()
                if (backgroundedAt != null) {
                    visualCoordinator.onApplicationForeground(System.currentTimeMillis() - backgroundedAt)
                }
                viewModelScope.launch { probePairedHostAuthorization() }
            }
        }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        viewModelScope.launch {
            startLanRediscoveryLoop()
        }
        viewModelScope.launch {
            startHostPresenceExpiryLoop()
        }
        viewModelScope.launch {
            delay(1_500L)
            while (true) {
                probePairedHostAuthorization()
                delay(3_000L)
            }
        }
        viewModelScope.launch {
            repository.observeProfiles().collect { profiles ->
                _state.update { current ->
                    current.copy(
                        profiles = profiles,
                        selectedProfileId = current.selectedProfileId?.takeIf { id -> profiles.any { it.id == id } },
                    )
                }
            }
        }
        viewModelScope.launch {
            val config = relayConfigRepository.getRelayConfig()
            val pendingRotation = runCatching { deviceIdentityStore.pendingRotation() }.rethrowCancellation().getOrNull()
            if (config != null || pendingRotation != null) {
                _state.update {
                    it.copy(
                        relay =
                            it.relay.copy(
                                relayUrl = config?.relayUrl.orEmpty(),
                                phoneRelayDeviceId = config?.deviceId?.value.orEmpty(),
                                enabled = config?.enabled == true,
                                identityRotationPending = pendingRotation != null,
                                message =
                                    if (pendingRotation != null) {
                                        "IDN-1011: an interrupted identity rotation is ready to resume"
                                    } else {
                                        "Loaded relay settings. Register again to obtain a fresh token."
                                    },
                            ),
                    )
                }
            }
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        AegisSessionService.stop(getApplication())
        terminalCoordinator.close()
        sftpCoordinator.close()
        pairingCoordinator.close()

        cleanupScope.launch {
            try {
                withTimeoutOrNull(ANDROID_SESSION_CLEANUP_TIMEOUT_MILLIS) {
                    runCatching { visualCoordinator.shutdown() }.rethrowCancellation()
                    runCatching { remoteInteractionCoordinator.shutdown() }.rethrowCancellation()
                    runCatching { relayCoordinator.shutdown() }.rethrowCancellation()
                }
            } finally {
                cleanupScope.cancel()
            }
        }
        super.onCleared()
    }

    private fun stopForegroundServiceIfIdle() {
        if (!terminalCoordinator.hasActiveSession && !sftpCoordinator.hasActiveSession && !visualCoordinator.hasActiveSession) {
            AegisSessionService.stop(getApplication())
        }
    }

    fun dispatch(action: AndroidHomeAction) {
        when (action) {
            AndroidHomeAction.BackToList -> {
                pairingCoordinator.cancel(resetDraft = true)
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.List, selectedProfileId = null, routeDiagnosticsVisible = false)
                }
            }

            is AndroidHomeAction.SelectProfile -> {
                _state.update {
                    it.copy(
                        screen = AndroidHomeScreenMode.Detail,
                        selectedProfileId = action.id,
                        routeDiagnostics = null,
                        routeDiagnosticsVisible = false,
                    )
                }
            }

            AndroidHomeAction.TestRoutes -> {
                profileCoordinator.testRoutes()
            }

            AndroidHomeAction.StartTerminal -> {
                terminalCoordinator.start()
            }

            AndroidHomeAction.DisconnectTerminal -> {
                terminalCoordinator.close()
            }

            is AndroidHomeAction.UpdateTerminalInput -> {
                _state.update {
                    it.copy(terminal = it.terminal.copy(input = action.input))
                }
            }

            is AndroidHomeAction.UpdateTerminalColumns -> {
                _state.update {
                    it.copy(terminal = it.terminal.copy(columns = action.columns))
                }
            }

            is AndroidHomeAction.UpdateTerminalRows -> {
                _state.update {
                    it.copy(terminal = it.terminal.copy(rows = action.rows))
                }
            }

            is AndroidHomeAction.AutoResizeTerminal -> {
                terminalCoordinator.autoResize(action.columns, action.rows)
            }

            AndroidHomeAction.SendTerminalInput -> {
                terminalCoordinator.sendInput()
            }

            is AndroidHomeAction.SendTerminalText -> {
                terminalCoordinator.sendText(action.text)
            }

            is AndroidHomeAction.SendTerminalKey -> {
                terminalCoordinator.sendKey(action.key)
            }

            AndroidHomeAction.ClearTerminalOutput -> {
                _state.update { it.copy(terminal = it.terminal.copy(output = "")) }
            }

            AndroidHomeAction.ResizeTerminal -> {
                terminalCoordinator.resize()
            }

            AndroidHomeAction.StartSftp -> {
                sftpCoordinator.start()
            }

            AndroidHomeAction.RefreshSftp -> {
                sftpCoordinator.refresh()
            }

            AndroidHomeAction.CloseSftp -> {
                sftpCoordinator.close()
            }

            is AndroidHomeAction.OpenSftpEntry -> {
                sftpCoordinator.openEntry(action.entry)
            }

            is AndroidHomeAction.NavigateSftp -> {
                sftpCoordinator.navigate(action.path)
            }

            AndroidHomeAction.GoUpSftp -> {
                sftpCoordinator.goUp()
            }

            AndroidHomeAction.ClearSftpSelection -> {
                _state.update { it.copy(sftp = it.sftp.copy(selectedEntry = null, message = null)) }
            }

            is AndroidHomeAction.UpdateSftpLocalPath -> {
                _state.update {
                    it.copy(sftp = it.sftp.copy(transferLocalPath = action.path, message = null))
                }
            }

            is AndroidHomeAction.UpdateSftpRemotePath -> {
                _state.update {
                    it.copy(sftp = it.sftp.copy(transferRemotePath = action.path, message = null))
                }
            }

            is AndroidHomeAction.UpdateSftpOperationPath -> {
                _state.update {
                    it.copy(sftp = it.sftp.copy(operationPath = action.path, message = null))
                }
            }

            is AndroidHomeAction.UpdateSftpRenameTargetPath -> {
                _state.update {
                    it.copy(sftp = it.sftp.copy(renameTargetPath = action.path, message = null))
                }
            }

            AndroidHomeAction.UploadSftpFile -> {
                sftpCoordinator.uploadFile()
            }

            is AndroidHomeAction.UploadPickedSftpDocument -> {
                sftpCoordinator.uploadPickedDocument(action.uri)
            }

            is AndroidHomeAction.UploadPickedSftpDocuments -> {
                sftpCoordinator.uploadPickedDocuments(action.uris)
            }

            AndroidHomeAction.DownloadSftpFile -> {
                sftpCoordinator.downloadFile()
            }

            is AndroidHomeAction.DownloadPickedSftpDocument -> {
                sftpCoordinator.downloadPickedDocument(action.uri, action.remotePath)
            }

            AndroidHomeAction.CancelSftpTransfer -> {
                sftpCoordinator.cancelTransfer()
            }

            AndroidHomeAction.CreateSftpDirectory -> {
                sftpCoordinator.createDirectory()
            }

            is AndroidHomeAction.CreateSftpFolderNamed -> {
                sftpCoordinator.createFolderNamed(action.name)
            }

            AndroidHomeAction.RenameSftpPath -> {
                sftpCoordinator.renamePath()
            }

            is AndroidHomeAction.RenameSelectedSftpEntry -> {
                sftpCoordinator.renameSelectedEntry(action.expectedPath, action.name)
            }

            AndroidHomeAction.DeleteSftpPath -> {
                sftpCoordinator.deletePath()
            }

            is AndroidHomeAction.DeleteSelectedSftpEntry -> {
                sftpCoordinator.deleteSelectedEntry(action.expectedPath)
            }

            AndroidHomeAction.SendWakeOnLan -> {
                sendWakeOnLan()
            }

            is AndroidHomeAction.SelectWakeOnLanAdapter -> {
                selectWakeOnLanAdapter(action.config)
            }

            AndroidHomeAction.CheckSshHealth -> {
                profileCoordinator.checkSelectedSshHealth()
            }

            AndroidHomeAction.ShowRemoteInput -> {
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.RemoteInput, remoteInput = it.remoteInput.copy(message = null))
                }
            }

            AndroidHomeAction.CloseRemoteInput -> {
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.Detail, remoteInput = RemoteInputUiState())
                }
            }

            is AndroidHomeAction.UpdateRemoteInputText -> {
                _state.update {
                    it.copy(remoteInput = it.remoteInput.copy(text = action.text, dataChannelPayload = null, message = null))
                }
            }

            is AndroidHomeAction.UpdateRemoteInputX -> {
                _state.update {
                    it.copy(remoteInput = it.remoteInput.copy(x = action.x, dataChannelPayload = null, message = null))
                }
            }

            is AndroidHomeAction.UpdateRemoteInputY -> {
                _state.update {
                    it.copy(remoteInput = it.remoteInput.copy(y = action.y, dataChannelPayload = null, message = null))
                }
            }

            is AndroidHomeAction.UpdateRemoteInputMonitorId -> {
                _state.update {
                    it.copy(remoteInput = it.remoteInput.copy(monitorId = action.monitorId, dataChannelPayload = null, message = null))
                }
            }

            AndroidHomeAction.PrepareMouseMovePayload -> {
                remoteInteractionCoordinator.prepareRemoteInputPayload("Mouse move") {
                    val input = state.value.remoteInput
                    val x = input.x.toIntOrNull() ?: throw IllegalArgumentException("Mouse X must be an integer.")
                    val y = input.y.toIntOrNull() ?: throw IllegalArgumentException("Mouse Y must be an integer.")
                    val monitorId = input.monitorId.trim().ifBlank { "primary" }
                    RemoteInputEvent.MouseMove(x = x, y = y, monitorId = MonitorId(monitorId))
                }
            }

            AndroidHomeAction.PrepareLeftMouseDownPayload -> {
                remoteInteractionCoordinator.prepareRemoteInputPayload("Left mouse down") {
                    RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = true)
                }
            }

            AndroidHomeAction.PrepareLeftMouseUpPayload -> {
                remoteInteractionCoordinator.prepareRemoteInputPayload("Left mouse up") {
                    RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = false)
                }
            }

            AndroidHomeAction.PrepareScrollUpPayload -> {
                remoteInteractionCoordinator.prepareRemoteInputPayload("Scroll up") {
                    RemoteInputEvent.Scroll(deltaX = 0f, deltaY = -1f)
                }
            }

            AndroidHomeAction.PrepareScrollDownPayload -> {
                remoteInteractionCoordinator.prepareRemoteInputPayload("Scroll down") {
                    RemoteInputEvent.Scroll(deltaX = 0f, deltaY = 1f)
                }
            }

            AndroidHomeAction.PrepareTextInputPayload -> {
                remoteInteractionCoordinator.prepareRemoteInputPayload("Text input") {
                    val text = state.value.remoteInput.text
                    if (text.isBlank()) throw IllegalArgumentException("Enter text before preparing a text input payload.")
                    RemoteInputEvent.TextInput(text)
                }
            }

            is AndroidHomeAction.PrepareKeyPayload -> {
                remoteInteractionCoordinator.prepareRemoteInputPayload("${action.key} ${if (action.pressed) "down" else "up"}") {
                    RemoteInputEvent.Key(action.key, pressed = action.pressed)
                }
            }

            is AndroidHomeAction.PrepareKeyTap -> {
                remoteInteractionCoordinator.prepareRemoteInputPayloads("${action.key} tap") {
                    listOf(
                        RemoteInputEvent.Key(action.key, pressed = true),
                        RemoteInputEvent.Key(action.key, pressed = false),
                    )
                }
            }

            AndroidHomeAction.ShowClipboard -> {
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.Clipboard, clipboard = it.clipboard.copy(message = null))
                }
            }

            AndroidHomeAction.CloseClipboard -> {
                remoteInteractionCoordinator.stopAutomaticClipboardSync(resetState = true)
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.Detail, clipboard = ClipboardUiState())
                }
            }

            AndroidHomeAction.ReadLocalClipboard -> {
                remoteInteractionCoordinator.readLocalClipboard()
            }

            AndroidHomeAction.WriteLocalClipboard -> {
                remoteInteractionCoordinator.writeLocalClipboard()
            }

            AndroidHomeAction.PrepareClipboardDataChannelPayload -> {
                remoteInteractionCoordinator.prepareClipboardDataChannelPayload()
            }

            is AndroidHomeAction.SetAutomaticClipboardSync -> {
                remoteInteractionCoordinator.setAutomaticClipboardSync(action.enabled)
            }

            is AndroidHomeAction.UpdateClipboardText -> {
                _state.update {
                    it.copy(
                        clipboard =
                            it.clipboard.copy(
                                text = action.text,
                                dataChannelPayload = null,
                                dataChannelSessionId = null,
                                message = null,
                            ),
                    )
                }
            }

            AndroidHomeAction.ShowRelay -> {
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.Relay, relay = it.relay.copy(message = null))
                }
            }

            is AndroidHomeAction.SaveDirectAccess -> {
                saveDirectAccess(action.draft)
            }

            AndroidHomeAction.RemoveDirectAccess -> {
                val profile = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId } ?: return
                viewModelScope.launch {
                    runCatching { repository.saveProfile(profile.copy(vpnHost = null, remoteSshPort = null, advertisedEndpoints = emptyList())) }
                        .rethrowCancellation()
                        .onFailure { error -> _state.update { it.copy(errorMessage = error.message) } }
                }
            }

            AndroidHomeAction.CloseRelay -> {
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.Detail)
                }
            }

            AndroidHomeAction.PrepareVisualSession -> {
                visualCoordinator.prepareVisualSession()
            }

            AndroidHomeAction.CloseVisual -> {
                visualCoordinator.close()
            }

            is AndroidHomeAction.SetVisualFullscreen -> {
                _state.update { state ->
                    state.copy(visual = state.visual.copy(fullscreen = action.enabled))
                }
            }

            is AndroidHomeAction.SetStreamingQuality -> {
                visualCoordinator.setStreamingQuality(action.mode)
            }

            is AndroidHomeAction.AttachVisualRenderer -> {
                visualCoordinator.attachVisualRenderer(action.renderer)
            }

            is AndroidHomeAction.DetachVisualRenderer -> {
                visualCoordinator.detachVisualRenderer(action.renderer)
            }

            is AndroidHomeAction.VisualPointerTap -> {
                visualCoordinator.sendVisualPointer(action.position, VisualPointerPhase.Tap)
            }

            is AndroidHomeAction.VisualPointerDragStart -> {
                visualCoordinator.sendVisualPointer(action.position, VisualPointerPhase.DragStart)
            }

            is AndroidHomeAction.VisualPointerDragMove -> {
                visualCoordinator.sendVisualPointer(action.position, VisualPointerPhase.DragMove)
            }

            AndroidHomeAction.VisualPointerDragEnd -> {
                visualCoordinator.sendInputEvents(listOf(RemoteInputEvent.MouseButton(MouseButtonType.Left, pressed = false)))
            }

            is AndroidHomeAction.VisualPointerScroll -> {
                val wheelNotches = action.deltaY.coerceIn(-3f, 3f)
                if (wheelNotches != 0f) visualCoordinator.sendInputEvents(listOf(RemoteInputEvent.Scroll(0f, wheelNotches)))
            }

            is AndroidHomeAction.TrackpadMove -> {
                visualCoordinator.trackpadMove(action.deltaX, action.deltaY)
            }

            is AndroidHomeAction.TrackpadScroll -> {
                visualCoordinator.trackpadScroll(action.deltaX, action.deltaY)
            }

            is AndroidHomeAction.TrackpadClick -> {
                visualCoordinator.trackpadClick(action.button)
            }

            is AndroidHomeAction.TrackpadSetButton -> {
                visualCoordinator.trackpadSetButton(action.button, action.pressed)
            }

            AndroidHomeAction.ResetTrackpadGesture -> {
                visualCoordinator.resetTrackpadGesture()
            }

            is AndroidHomeAction.VisualKeyTap -> {
                visualCoordinator.sendInputEvents(
                    listOf(
                        RemoteInputEvent.Key(action.key, pressed = true),
                        RemoteInputEvent.Key(action.key, pressed = false),
                    ),
                )
            }

            AndroidHomeAction.SendVisualText -> {
                val text = state.value.remoteInput.text
                val events = text.toRemoteTextInputEvents()
                if (events.isEmpty()) return@dispatch
                visualCoordinator.sendInputEvents(events)
                _state.update { it.copy(remoteInput = it.remoteInput.copy(text = "", message = null)) }
            }

            is AndroidHomeAction.SelectVisualMonitor -> {
                visualCoordinator.selectVisualMonitor(action.monitorId)
            }

            is AndroidHomeAction.UpdateRelayDraft -> {
                _state.update {
                    it.copy(relay = action.relay)
                }
            }

            AndroidHomeAction.RegisterRelayDevice -> {
                relayCoordinator.register()
            }

            AndroidHomeAction.RotateRelayIdentity -> {
                relayCoordinator.rotate()
            }

            AndroidHomeAction.CreateRelaySession -> {
                relayCoordinator.createSession()
            }

            AndroidHomeAction.RequestTurnCredentials -> {
                relayCoordinator.requestTurnCredentials()
            }

            AndroidHomeAction.ShowAddProfile -> {
                _state.update {
                    it.copy(screen = AndroidHomeScreenMode.AddProfile, selectedProfileId = null, draft = AddProfileDraft())
                }
            }

            AndroidHomeAction.EditSelectedProfile -> {
                profileCoordinator.editSelectedProfile()
            }

            AndroidHomeAction.ShowLocalPairing -> {
                val resumableDraft =
                    state.value.localPairingDraft.takeIf {
                        state.value.screen == AndroidHomeScreenMode.LocalPairing && !it.requestId.isNullOrBlank()
                    }
                if (resumableDraft != null) {
                    pairingCoordinator.log("Retry action resumes request ${resumableDraft.requestId.logId()} without submitting a duplicate")
                    _state.update {
                        it.copy(
                            screen = AndroidHomeScreenMode.LocalPairing,
                            localPairingBusy = true,
                            localPairingMessage = "Reconnecting to the existing approval request...",
                            localPairingStage = LocalPairingStage.RetryingConnection,
                            errorMessage = null,
                        )
                    }
                    val finishingJob = pairingCoordinator.activeJob
                    if (finishingJob?.isActive == true) {
                        val requestId = resumableDraft.requestId
                        finishingJob.invokeOnCompletion {
                            viewModelScope.launch {
                                val latest = state.value
                                if (
                                    latest.screen == AndroidHomeScreenMode.LocalPairing &&
                                    latest.localPairingDraft.requestId == requestId
                                ) {
                                    pairingCoordinator.checkStatus()
                                }
                            }
                        }
                    } else {
                        pairingCoordinator.checkStatus()
                    }
                    return
                }
                pairingCoordinator.cancel(resetDraft = false)
                val draft = pairingCoordinator.initialDraft()
                _state.update {
                    it.copy(
                        screen = AndroidHomeScreenMode.LocalPairing,
                        selectedProfileId = null,
                        localPairingDraft = draft,
                        localPairingBusy = false,
                        localPairingMessage = null,
                        localPairingStage = LocalPairingStage.Scanning,
                        errorMessage = null,
                    )
                }
            }

            is AndroidHomeAction.UpdateDraft -> {
                _state.update { it.copy(draft = action.draft, errorMessage = null) }
            }

            is AndroidHomeAction.UpdateLocalPairingDraft -> {
                _state.update {
                    it.copy(localPairingDraft = action.draft, errorMessage = null)
                }
            }

            AndroidHomeAction.SaveDraft -> {
                profileCoordinator.saveDraft()
            }

            AndroidHomeAction.ImportLocalPairingQrPayload -> {
                pairingCoordinator.importQrPayload()
            }

            is AndroidHomeAction.ImportScannedLocalPairingQrPayload -> {
                pairingCoordinator.importQrPayload(action.payload, autoSubmit = true)
            }

            is AndroidHomeAction.LocalPairingQrScanFailed -> {
                _state.update {
                    it.copy(
                        localPairingBusy = false,
                        localPairingMessage = null,
                        localPairingStage = LocalPairingStage.Failed,
                        errorMessage = action.message,
                    )
                }
            }

            AndroidHomeAction.SubmitLocalPairing -> {
                pairingCoordinator.submit()
            }

            AndroidHomeAction.CheckLocalPairingStatus -> {
                pairingCoordinator.checkStatus()
            }

            is AndroidHomeAction.DeleteProfile -> {
                profileCoordinator.deleteProfile(action.id)
            }
        }
    }

    private fun routeManager(): ConnectionRouteManager =
        dependencies.routeManagerFactory(
            AndroidRouteManagerContext(
                relayClientProvider = { relayCoordinator.client },
                isRelayRegistered = { state.value.relay.registered },
                hasApprovedRelaySession = { relayDeviceId -> state.value.relay.approvedSessionIdFor(relayDeviceId) != null },
                selectedIceRoute = { visualCoordinator.lastSelectedIceRoute() },
            ),
        )

    private suspend fun resolveSshRoute(profile: DeviceProfile): SshRoutePlan {
        if (profile.localProtocolTokenRef != null) {
            val outcome = AndroidLanRediscovery(AndroidLocalPairingClient(), getApplication()).probeAuthorization(profile)
            check(outcome != LanHostSyncOutcome.Ignored && outcome != LanHostSyncOutcome.HostUnlinked) {
                getApplication<Application>().getString(R.string.direct_open_pc)
            }
            applyLanHostSync(profile, outcome)
            val refreshed = (outcome as? LanHostSyncOutcome.HostUpdated)?.profile ?: profile
            check(refreshed.permissions.terminal && refreshed.permissions.sftp) {
                getApplication<Application>().getString(R.string.direct_access_denied)
            }
        }
        return ResolveSshRouteUseCase(routeManager()).invoke(profile)
    }

    @Suppress("TooGenericExceptionCaught") // UI operation boundary: report failure while preserving coroutine cancellation.
    private fun saveDirectAccess(draft: DirectAccessDraft) {
        val profile = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId } ?: return
        val application = getApplication<Application>()
        val candidate =
            runCatching { draft.applyTo(profile) }.getOrElse {
                _state.update { it.copy(relay = it.relay.copy(message = application.getString(R.string.direct_invalid_address))) }
                return
            }
        if (candidate.localAgentCertificateFingerprint == null || candidate.authorizedDeviceId == null) {
            _state.update { it.copy(relay = it.relay.copy(message = application.getString(R.string.direct_pair_first))) }
            return
        }
        _state.update { it.copy(relay = it.relay.copy(busy = true, message = null)) }
        viewModelScope.launch {
            try {
                repository.saveProfile(candidate)
                val outcome = AndroidLanRediscovery(AndroidLocalPairingClient(), application).probeAuthorization(candidate, remoteOnly = true)
                applyLanHostSync(candidate, outcome)
                val message =
                    when (outcome) {
                        LanHostSyncOutcome.Ignored -> R.string.direct_saved_unreachable
                        LanHostSyncOutcome.HostUnlinked -> R.string.direct_access_denied
                        else -> R.string.direct_verified
                    }
                _state.update { it.copy(relay = it.relay.copy(busy = false, message = application.getString(message))) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showDirectAccessCheckFailure()
            }
        }
    }

    private fun showDirectAccessCheckFailure() {
        val message = getApplication<Application>().getString(R.string.direct_check_failed)
        _state.update { it.copy(relay = it.relay.copy(busy = false, message = message)) }
    }

    private suspend fun startLanRediscoveryLoop() {
        val rediscovery = AndroidLanRediscovery(AndroidLocalPairingClient(), getApplication())
        val localFingerprint =
            runCatching { deviceIdentityStore.getOrCreate().publicIdentity.fingerprint }
                .rethrowCancellation()
                .getOrNull()
        while (true) {
            val announce = runCatching { rediscovery.receiveAnnounce(2_000) }.rethrowCancellation().getOrNull()
            if (announce == null) continue
            state.value.profiles.toList().forEach { profile ->
                if (profile.localAgentCertificateFingerprint.isNullOrBlank()) return@forEach
                val outcome =
                    runCatching { rediscovery.syncAnnouncedHost(profile, announce, localFingerprint) }
                        .rethrowCancellation()
                        .getOrDefault(LanHostSyncOutcome.Ignored)
                applyLanHostSync(profile, outcome)
            }
        }
    }

    /** Ages LAN presence so a PC that stops announcing flips to offline within a few seconds. */
    private suspend fun startHostPresenceExpiryLoop() {
        while (true) {
            delay(HOST_PRESENCE_TICK_MILLIS)
            val now = System.currentTimeMillis()
            _state.update { current ->
                // A live stream (LAN or relay) is proof of presence even when no multicast announce arrives.
                val streamingId = current.selectedProfileId?.takeIf { current.visual.streaming }
                val refreshed =
                    streamingId?.let { HostPresenceTracker.markSeen(current.hostPresence, it, now) } ?: current.hostPresence
                val expired = HostPresenceTracker.expire(refreshed, now)
                if (expired === current.hostPresence) current else current.copy(hostPresence = expired)
            }
            val selected = state.value.profiles.firstOrNull { it.id == state.value.selectedProfileId }
            if (selected?.localProtocolTokenRef != null && state.value.presenceOf(selected.id).status == HostPresenceStatus.Offline) {
                if (terminalCoordinator.hasActiveSession) terminalCoordinator.close()
                if (sftpCoordinator.hasActiveSession) sftpCoordinator.close()
            }
        }
    }

    private fun markHostSeen(
        id: DeviceProfileId,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        _state.update { it.copy(hostPresence = HostPresenceTracker.markSeen(it.hostPresence, id, nowEpochMillis)) }
    }

    private suspend fun probePairedHostAuthorization() =
        coroutineScope {
            state.value.profiles
                .toList()
                .map { profile ->
                    async {
                        if (profile.authorizedDeviceId.isNullOrBlank() || profile.localAgentCertificateFingerprint.isNullOrBlank()) return@async
                        // Keep a full probe cycle below the presence expiry, with an independent certificate pin per PC.
                        val client = AndroidLocalPairingClient(connectTimeoutMillis = 800, readTimeoutMillis = 1_200)
                        val rediscovery = AndroidLanRediscovery(client, getApplication())
                        val outcome =
                            runCatching { rediscovery.probeAuthorization(profile) }
                                .rethrowCancellation()
                                .getOrDefault(LanHostSyncOutcome.Ignored)
                        if (outcome == LanHostSyncOutcome.Ignored) {
                            _state.update { it.copy(hostPresence = HostPresenceTracker.markUnreachable(it.hostPresence, profile.id)) }
                        }
                        applyLanHostSync(profile, outcome)
                    }
                }.awaitAll()
            Unit
        }

    private suspend fun applyLanHostSync(
        profile: DeviceProfile,
        outcome: LanHostSyncOutcome,
    ) {
        val current = repository.getProfile(profile.id) ?: return
        // A response started before an edit/re-pair must never restore old settings or credentials.
        if (current.authorizedDeviceId != profile.authorizedDeviceId ||
            current.localAgentCertificateFingerprint != profile.localAgentCertificateFingerprint ||
            current.localProtocolTokenRef != profile.localProtocolTokenRef
        ) {
            return
        }
        when (outcome) {
            is LanHostSyncOutcome.HostUpdated -> {
                markHostSeen(profile.id)
                val updated =
                    current.copy(
                        permissions = outcome.profile.permissions,
                        localHost = if (outcome.profile.localHost != profile.localHost) outcome.profile.localHost else current.localHost,
                    )
                repository.saveProfile(updated.copy(advertisedEndpoints = updated.stableAdvertisedEndpoints()))
                closeDisallowedHostSessions(profile.id, outcome.profile.permissions)
            }

            LanHostSyncOutcome.HostUnlinked -> {
                if (profile.id == state.value.selectedProfileId) {
                    terminalCoordinator.close()
                    sftpCoordinator.close()
                }
                unlinkRevokedHost(current)
            }

            LanHostSyncOutcome.HostReachable -> {
                markHostSeen(profile.id)
            }

            LanHostSyncOutcome.Ignored -> {
                Unit
            }
        }
    }

    private fun closeDisallowedHostSessions(
        profileId: DeviceProfileId,
        permissions: DevicePermissions,
    ) {
        if (profileId != state.value.selectedProfileId) return
        if (!permissions.terminal && terminalCoordinator.hasActiveSession) terminalCoordinator.close()
        if (!permissions.sftp && sftpCoordinator.hasActiveSession) sftpCoordinator.close()
    }

    private suspend fun unlinkRevokedHost(profile: DeviceProfile) {
        runCatching { repository.deleteProfile(profile.id) }.rethrowCancellation()
        listOfNotNull(profile.credentialsRef, profile.localProtocolTokenRef)
            .distinct()
            .forEach { reference -> runCatching { credentialStore.deleteSecret(reference) } }
        val application = getApplication<Application>()
        _state.update { current ->
            val viewingRemoved = current.selectedProfileId == profile.id
            current.copy(
                screen = if (viewingRemoved) AndroidHomeScreenMode.List else current.screen,
                selectedProfileId = current.selectedProfileId.takeUnless { it == profile.id },
                errorMessage = application.getString(R.string.device_unlinked_by_pc, profile.displayName),
            )
        }
    }

    private fun sendWakeOnLan() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId } ?: return
        if (!profile.permissions.wakeOnLan) {
            _state.update {
                it.copy(wakeOnLan = WolUiState(message = getApplication<Application>().getString(R.string.wol_not_authorized)))
            }
            return
        }
        val config = profile.wakeOnLanConfig
        if (config == null) {
            _state.update {
                it.copy(wakeOnLan = WolUiState(message = getApplication<Application>().getString(R.string.wol_not_configured)))
            }
            return
        }
        if (config.capability != WakeOnLanCapability.Supported) {
            _state.update {
                it.copy(
                    wakeOnLan =
                        WolUiState(
                            packetSent = false,
                            message = config.capabilityReason ?: getApplication<Application>().getString(R.string.wol_not_capable),
                        ),
                )
            }
            return
        }
        _state.update {
            it.copy(
                wakeOnLan = WolUiState(sending = true, message = getApplication<Application>().getString(R.string.wol_sending)),
            )
        }
        viewModelScope.launch {
            runCatching { wakeOnLanSender.send(config) }
                .rethrowCancellation()
                .onSuccess { result ->
                    val reachable = awaitWakeReachability(profile)
                    _state.update {
                        it.copy(
                            wakeOnLan =
                                WolUiState(
                                    sending = false,
                                    packetSent = result.packetSent,
                                    hostReachable = reachable,
                                    message =
                                        if (reachable) {
                                            getApplication<Application>().getString(R.string.wol_sent_reachable)
                                        } else {
                                            getApplication<Application>().getString(R.string.wol_sent_unconfirmed)
                                        },
                                ),
                        )
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            wakeOnLan =
                                WolUiState(
                                    sending = false,
                                    packetSent = false,
                                    message = error.message ?: getApplication<Application>().getString(R.string.wol_failed),
                                ),
                        )
                    }
                }
        }
    }

    private fun selectWakeOnLanAdapter(config: WakeOnLanConfig) {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId } ?: return
        if (config !in profile.availableWakeOnLanConfigs) {
            _state.update {
                it.copy(wakeOnLan = WolUiState(message = getApplication<Application>().getString(R.string.wol_adapter_mismatch)))
            }
            return
        }
        viewModelScope.launch {
            repository.saveProfile(profile.copy(wakeOnLanConfig = config))
            _state.update {
                it.copy(
                    wakeOnLan =
                        WolUiState(
                            message =
                                getApplication<Application>().getString(
                                    R.string.wol_adapter_selected,
                                    config.adapterName ?: config.adapterId ?: config.macAddress.value,
                                ),
                        ),
                )
            }
        }
    }

    private suspend fun awaitWakeReachability(profile: DeviceProfile): Boolean =
        withContext(Dispatchers.IO) {
            repeat(WOL_CONFIRMATION_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(WOL_CONFIRMATION_INTERVAL_MILLIS)
                val reachable =
                    runCatching {
                        Socket().use { socket ->
                            socket.connect(
                                InetSocketAddress(profile.localHost.host, profile.sshPort),
                                WOL_CONNECT_TIMEOUT_MILLIS,
                            )
                        }
                    }.isSuccess
                if (reachable) return@withContext true
            }
            false
        }

    private suspend fun markProfileConnected(
        profileId: DeviceProfileId,
        routeType: dev.aegis.remote.core.model.ConnectionRouteType,
    ) {
        repository.getProfile(profileId)?.let { current ->
            repository.saveProfile(
                current.copy(
                    lastConnectedAtEpochMillis = System.currentTimeMillis(),
                    lastSuccessfulRoute = routeType,
                ),
            )
        }
    }
}

data class AndroidHomeUiState(
    val profiles: List<DeviceProfile> = emptyList(),
    val selectedProfileId: DeviceProfileId? = null,
    val routeDiagnosticsVisible: Boolean = false,
    val routeTesting: Boolean = false,
    val routeDiagnostics: RouteDiagnostics? = null,
    val screen: AndroidHomeScreenMode = AndroidHomeScreenMode.List,
    val draft: AddProfileDraft = AddProfileDraft(),
    val localPairingDraft: LocalPairingDraft = LocalPairingDraft(),
    val localPairingBusy: Boolean = false,
    val localPairingMessage: String? = null,
    val localPairingStage: LocalPairingStage = LocalPairingStage.Scanning,
    val terminal: TerminalUiState = TerminalUiState(),
    val sshHealth: SshHealthUiState = SshHealthUiState(),
    val visual: VisualUiState = VisualUiState(),
    val sftp: SftpUiState = SftpUiState(),
    val wakeOnLan: WolUiState = WolUiState(),
    val remoteInput: RemoteInputUiState = RemoteInputUiState(),
    val clipboard: ClipboardUiState = ClipboardUiState(),
    val relay: RelayUiState = RelayUiState(),
    val hostPresence: Map<DeviceProfileId, HostPresence> = emptyMap(),
    val errorMessage: String? = null,
) {
    fun presenceOf(id: DeviceProfileId): HostPresence = hostPresence[id] ?: HostPresence()
}

data class SshHealthUiState(
    val checking: Boolean = false,
    val healthy: Boolean? = null,
    val message: String? = null,
)

data class TerminalUiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val resizing: Boolean = false,
    val output: String = "",
    val input: String = "",
    val columns: String = "80",
    val rows: String = "24",
)

data class VisualUiState(
    val busy: Boolean = false,
    val prepared: Boolean = false,
    val streaming: Boolean = false,
    val videoState: String? = null,
    val route: String? = null,
    val video: String? = null,
    val ice: String? = null,
    val stats: String? = null,
    val monitors: String? = null,
    val monitorOptions: List<MonitorInfo> = emptyList(),
    val selectedMonitorId: MonitorId? = null,
    val inputEnabled: Boolean = false,
    val clipboardEnabled: Boolean = false,
    val dataChannelPayload: String? = null,
    val dataChannelSessionId: String? = null,
    val fullscreen: Boolean = false,
    val qualityMode: dev.aegis.remote.core.model.QualityMode = dev.aegis.remote.core.model.QualityMode.Balanced,
    val qualityChanging: Boolean = false,
    val message: String? = null,
)

data class VisualPointerPosition(
    val touchX: Float,
    val touchY: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
)

internal enum class VisualPointerPhase {
    Tap,
    DragStart,
    DragMove,
}

internal data class VisualStartResult(
    val payload: String,
    val startedNativeSession: Boolean,
    val error: String? = null,
)

data class SftpUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val path: String = ".",
    val entries: List<dev.aegis.remote.core.model.FileEntry> = emptyList(),
    val selectedEntry: dev.aegis.remote.core.model.FileEntry? = null,
    val transferLocalPath: String = "",
    val transferRemotePath: String = "",
    val transferBusy: Boolean = false,
    val transferProgressText: String? = null,
    val operationPath: String = "",
    val renameTargetPath: String = "",
    val operationBusy: Boolean = false,
    val capabilityNotice: String? = null,
    val message: String? = null,
)

data class WolUiState(
    val sending: Boolean = false,
    val packetSent: Boolean = false,
    val hostReachable: Boolean? = null,
    val message: String? = null,
)

data class RemoteInputUiState(
    val busy: Boolean = false,
    val x: String = "960",
    val y: String = "540",
    val monitorId: String = "primary",
    val text: String = "",
    val dataChannelPayload: String? = null,
    val dataChannelSessionId: String? = null,
    val lastEventLabel: String? = null,
    val message: String? = null,
)

data class ClipboardUiState(
    val busy: Boolean = false,
    val automaticSyncEnabled: Boolean = false,
    val text: String = "",
    val dataChannelPayload: String? = null,
    val dataChannelSessionId: String? = null,
    val lastAutomaticSyncText: String? = null,
    val message: String? = null,
)

data class RelayUiState(
    val relayUrl: String = "",
    val phoneRelayDeviceId: String = "",
    val pcRelayDeviceId: String = "",
    val phoneName: String = "Android Phone",
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val registered: Boolean = false,
    val identityRotationPending: Boolean = false,
    val tokenExpiresAtEpochMillis: Long? = null,
    val lastSessionId: String? = null,
    val lastSessionTargetRelayDeviceId: String? = null,
    val lastSessionApproved: Boolean = false,
    val approvedSessionIdsByRelayDevice: Map<String, String> = emptyMap(),
    val turnUrls: List<String> = emptyList(),
    val turnCredentialRef: String? = null,
    val turnCredentialsExpiresAtEpochMillis: Long? = null,
    val message: String? = null,
)

internal fun RelayUiState.approvedSessionIdFor(relayDeviceId: RelayDeviceId?): String? {
    if (relayDeviceId == null) return null
    return approvedSessionIdsByRelayDevice[relayDeviceId.value]?.takeIf { it.isNotBlank() }
}

enum class AndroidHomeScreenMode {
    List,
    Detail,
    AddProfile,
    LocalPairing,
    Terminal,
    Visual,
    Sftp,
    RemoteInput,
    Clipboard,
    Relay,
}

enum class LocalPairingStage {
    Scanning,
    VerifyingDesktop,
    SubmittingRequest,
    AwaitingApproval,
    RetryingConnection,
    Approved,
    Rejected,
    Expired,
    NetworkError,
    Failed,
}

sealed interface AndroidHomeAction {
    data class SelectProfile(
        val id: DeviceProfileId,
    ) : AndroidHomeAction

    data class DeleteProfile(
        val id: DeviceProfileId,
    ) : AndroidHomeAction

    data class UpdateDraft(
        val draft: AddProfileDraft,
    ) : AndroidHomeAction

    data class UpdateLocalPairingDraft(
        val draft: LocalPairingDraft,
    ) : AndroidHomeAction

    data class UpdateTerminalInput(
        val input: String,
    ) : AndroidHomeAction

    data class UpdateTerminalColumns(
        val columns: String,
    ) : AndroidHomeAction

    data class UpdateTerminalRows(
        val rows: String,
    ) : AndroidHomeAction

    data class AutoResizeTerminal(
        val columns: Int,
        val rows: Int,
    ) : AndroidHomeAction

    data class SendTerminalKey(
        val key: TerminalKeyStroke,
    ) : AndroidHomeAction

    data class SendTerminalText(
        val text: String,
    ) : AndroidHomeAction

    data class OpenSftpEntry(
        val entry: dev.aegis.remote.core.model.FileEntry,
    ) : AndroidHomeAction

    data class NavigateSftp(
        val path: String,
    ) : AndroidHomeAction

    data class UpdateClipboardText(
        val text: String,
    ) : AndroidHomeAction

    data class SetAutomaticClipboardSync(
        val enabled: Boolean,
    ) : AndroidHomeAction

    data class UpdateRelayDraft(
        val relay: RelayUiState,
    ) : AndroidHomeAction

    data class ImportScannedLocalPairingQrPayload(
        val payload: String,
    ) : AndroidHomeAction

    data class LocalPairingQrScanFailed(
        val message: String,
    ) : AndroidHomeAction

    data class UpdateSftpLocalPath(
        val path: String,
    ) : AndroidHomeAction

    data class UpdateSftpRemotePath(
        val path: String,
    ) : AndroidHomeAction

    data class UpdateSftpOperationPath(
        val path: String,
    ) : AndroidHomeAction

    data class UpdateSftpRenameTargetPath(
        val path: String,
    ) : AndroidHomeAction

    data class UploadPickedSftpDocument(
        val uri: String,
    ) : AndroidHomeAction

    data class UploadPickedSftpDocuments(
        val uris: List<String>,
    ) : AndroidHomeAction

    data class DownloadPickedSftpDocument(
        val uri: String,
        val remotePath: String,
    ) : AndroidHomeAction

    data class CreateSftpFolderNamed(
        val name: String,
    ) : AndroidHomeAction

    data class RenameSelectedSftpEntry(
        val expectedPath: String,
        val name: String,
    ) : AndroidHomeAction

    data class UpdateRemoteInputText(
        val text: String,
    ) : AndroidHomeAction

    data class UpdateRemoteInputX(
        val x: String,
    ) : AndroidHomeAction

    data class UpdateRemoteInputY(
        val y: String,
    ) : AndroidHomeAction

    data class UpdateRemoteInputMonitorId(
        val monitorId: String,
    ) : AndroidHomeAction

    data class PrepareKeyPayload(
        val key: KeyCode,
        val pressed: Boolean,
    ) : AndroidHomeAction

    data class PrepareKeyTap(
        val key: KeyCode,
    ) : AndroidHomeAction

    data class SelectWakeOnLanAdapter(
        val config: WakeOnLanConfig,
    ) : AndroidHomeAction

    data class AttachVisualRenderer(
        val renderer: SurfaceViewRenderer,
    ) : AndroidHomeAction

    data class DetachVisualRenderer(
        val renderer: SurfaceViewRenderer,
    ) : AndroidHomeAction

    data class VisualPointerTap(
        val position: VisualPointerPosition,
    ) : AndroidHomeAction

    data class VisualPointerDragStart(
        val position: VisualPointerPosition,
    ) : AndroidHomeAction

    data class VisualPointerDragMove(
        val position: VisualPointerPosition,
    ) : AndroidHomeAction

    data class VisualPointerScroll(
        val deltaY: Float,
    ) : AndroidHomeAction

    data class TrackpadMove(
        val deltaX: Float,
        val deltaY: Float,
    ) : AndroidHomeAction

    data class TrackpadScroll(
        val deltaX: Float,
        val deltaY: Float,
    ) : AndroidHomeAction

    data class TrackpadClick(
        val button: MouseButtonType,
    ) : AndroidHomeAction

    data class TrackpadSetButton(
        val button: MouseButtonType,
        val pressed: Boolean,
    ) : AndroidHomeAction

    data object ResetTrackpadGesture : AndroidHomeAction

    data class VisualKeyTap(
        val key: KeyCode,
    ) : AndroidHomeAction

    data object SendVisualText : AndroidHomeAction

    data class SelectVisualMonitor(
        val monitorId: MonitorId,
    ) : AndroidHomeAction

    data object VisualPointerDragEnd : AndroidHomeAction

    data object BackToList : AndroidHomeAction

    data object TestRoutes : AndroidHomeAction

    data object StartTerminal : AndroidHomeAction

    data object SendTerminalInput : AndroidHomeAction

    data object ClearTerminalOutput : AndroidHomeAction

    data object ResizeTerminal : AndroidHomeAction

    data object DisconnectTerminal : AndroidHomeAction

    data object PrepareVisualSession : AndroidHomeAction

    data object CloseVisual : AndroidHomeAction

    data class SetVisualFullscreen(
        val enabled: Boolean,
    ) : AndroidHomeAction

    data class SetStreamingQuality(
        val mode: dev.aegis.remote.core.model.QualityMode,
    ) : AndroidHomeAction

    data object StartSftp : AndroidHomeAction

    data object RefreshSftp : AndroidHomeAction

    data object GoUpSftp : AndroidHomeAction

    data object ClearSftpSelection : AndroidHomeAction

    data object CloseSftp : AndroidHomeAction

    data object UploadSftpFile : AndroidHomeAction

    data object DownloadSftpFile : AndroidHomeAction

    data object CancelSftpTransfer : AndroidHomeAction

    data object CreateSftpDirectory : AndroidHomeAction

    data object RenameSftpPath : AndroidHomeAction

    data object DeleteSftpPath : AndroidHomeAction

    data class DeleteSelectedSftpEntry(
        val expectedPath: String,
    ) : AndroidHomeAction

    data object SendWakeOnLan : AndroidHomeAction

    data object CheckSshHealth : AndroidHomeAction

    data object ShowRemoteInput : AndroidHomeAction

    data object CloseRemoteInput : AndroidHomeAction

    data object PrepareMouseMovePayload : AndroidHomeAction

    data object PrepareLeftMouseDownPayload : AndroidHomeAction

    data object PrepareLeftMouseUpPayload : AndroidHomeAction

    data object PrepareScrollUpPayload : AndroidHomeAction

    data object PrepareScrollDownPayload : AndroidHomeAction

    data object PrepareTextInputPayload : AndroidHomeAction

    data object ShowClipboard : AndroidHomeAction

    data object CloseClipboard : AndroidHomeAction

    data object ReadLocalClipboard : AndroidHomeAction

    data object WriteLocalClipboard : AndroidHomeAction

    data object PrepareClipboardDataChannelPayload : AndroidHomeAction

    data object ShowRelay : AndroidHomeAction

    data class SaveDirectAccess(
        val draft: DirectAccessDraft,
    ) : AndroidHomeAction

    data object RemoveDirectAccess : AndroidHomeAction

    data object CloseRelay : AndroidHomeAction

    data object RegisterRelayDevice : AndroidHomeAction

    data object RotateRelayIdentity : AndroidHomeAction

    data object CreateRelaySession : AndroidHomeAction

    data object RequestTurnCredentials : AndroidHomeAction

    data object ShowAddProfile : AndroidHomeAction

    data object EditSelectedProfile : AndroidHomeAction

    data object ShowLocalPairing : AndroidHomeAction

    data object SaveDraft : AndroidHomeAction

    data object ImportLocalPairingQrPayload : AndroidHomeAction

    data object SubmitLocalPairing : AndroidHomeAction

    data object CheckLocalPairingStatus : AndroidHomeAction
}

data class AddProfileDraft(
    val editingProfileId: DeviceProfileId? = null,
    val existingCredentialsRef: SshCredentialsRef? = null,
    val originalAuthMethod: AuthMethod? = null,
    val displayName: String = "",
    val localHost: String = "",
    val vpnHost: String = "",
    val relayDeviceId: String = "",
    val sshPort: String = "22",
    val username: String = "",
    val sshPassword: String = "",
    val hostKeyFingerprint: String = "",
    val macAddress: String = "",
    val wolBroadcastAddress: String = "255.255.255.255",
    val remoteAccessEnabled: Boolean = false,
    val authMethod: AuthMethod = AuthMethod.Password,
    val sshPrivateKey: String = "",
    val sshPrivateKeyPassphrase: String = "",
)

data class LocalPairingDraft(
    val qrPayload: String = "",
    val pairingUrl: String = "",
    val pairingUrls: List<String> = emptyList(),
    val pairingCode: String = "",
    val phoneName: String = "Android Phone",
    val sshUsername: String = "",
    val sshPassword: String = "",
    val sshHostKeyFingerprint: String = "",
    val requestId: String? = null,
    val discoveredHost: String? = null,
    val agentFingerprint: String? = null,
    val pairingSecret: String = "",
    val qrTokenId: String = "",
    val issuedAtEpochMillis: Long = 0,
    val expiresAtEpochMillis: Long = 0,
    val hostIdentity: DevicePublicIdentity? = null,
    val sshCredentialRef: SshCredentialsRef? = null,
    val sshPublicKey: String = "",
)

internal fun AddProfileDraft.validationError(): String? =
    basicValidationError()
        ?: credentialValidationError()
        ?: connectionValidationError()

private fun AddProfileDraft.basicValidationError(): String? =
    when {
        displayName.isBlank() -> "Display name is required"
        localHost.isBlank() -> "LAN host or IP is required"
        username.isBlank() -> "SSH username is required"
        else -> null
    }

private fun AddProfileDraft.credentialValidationError(): String? =
    when {
        authMethod == AuthMethod.Password && sshPassword.isBlank() && !canReuseExistingCredential() -> "SSH password is required"
        authMethod == AuthMethod.PrivateKey && sshPrivateKey.isBlank() && !canReuseExistingCredential() -> "SSH private key is required"
        authMethod == AuthMethod.Agent -> "SSH agent authentication is not available on Android"
        else -> null
    }

private fun AddProfileDraft.connectionValidationError(): String? =
    when {
        hostKeyFingerprint.isBlank() -> {
            "SSH host key fingerprint is required"
        }

        canonicalOpenSshSha256Fingerprint(hostKeyFingerprint) == null -> {
            "SSH host key fingerprint must be an OpenSSH SHA256 fingerprint"
        }

        sshPort.toIntOrNull()?.let { it in 1..65535 } != true -> {
            "SSH port must be between 1 and 65535"
        }

        else -> {
            null
        }
    }

internal fun LocalPairingDraft.validationError(): String? =
    when {
        pairingUrl.isBlank() -> "Desktop pairing URL is required"
        pairingCode.isBlank() -> "Pairing code is required"
        phoneName.isBlank() -> "Phone name is required"
        agentFingerprint.isNullOrBlank() -> "Scan the desktop QR code to verify the desktop identity"
        pairingSecret.length < 32 -> "Scan the desktop QR code to obtain a secure pairing capability"
        else -> null
    }

internal fun RelayUiState.validationError(): String? =
    when {
        relayUrl.isBlank() -> "Relay URL is required"
        !relayUrl.startsWith("http://") && !relayUrl.startsWith("https://") -> "Relay URL must start with http:// or https://"
        phoneName.isBlank() -> "Phone name is required"
        else -> null
    }

internal fun AddProfileDraft.canReuseExistingCredential(): Boolean =
    editingProfileId != null &&
        existingCredentialsRef != null &&
        originalAuthMethod == authMethod &&
        when (authMethod) {
            AuthMethod.Password -> sshPassword.isBlank()
            AuthMethod.PrivateKey -> sshPrivateKey.isBlank()
            AuthMethod.Agent -> false
        }

internal fun DeviceProfile.toEditDraft(): AddProfileDraft {
    val fingerprint = hostKeyFingerprint?.let { "${it.algorithm}:${it.value}" }.orEmpty()
    return AddProfileDraft(
        editingProfileId = id,
        existingCredentialsRef = credentialsRef,
        originalAuthMethod = authMethod,
        displayName = displayName,
        localHost = localHost.host,
        vpnHost = vpnHost?.host.orEmpty(),
        relayDeviceId = relayDeviceId?.value.orEmpty(),
        sshPort = sshPort.toString(),
        username = username,
        hostKeyFingerprint = fingerprint,
        macAddress = wakeOnLanConfig?.macAddress?.value.orEmpty(),
        wolBroadcastAddress = wakeOnLanConfig?.broadcastAddress ?: "255.255.255.255",
        remoteAccessEnabled = remoteAccessEnabled,
        authMethod = authMethod,
    )
}

internal fun AddProfileDraft.toProfile(
    credentialsRef: SshCredentialsRef,
    existingProfile: DeviceProfile? = null,
): DeviceProfile {
    val now = System.currentTimeMillis()
    val relayId = relayDeviceId.trim().takeIf { it.isNotBlank() }?.let(::RelayDeviceId)
    val wolConfig =
        macAddress.trim().takeIf { it.isNotBlank() }?.let { mac ->
            WakeOnLanConfig(
                macAddress = MacAddress(mac),
                broadcastAddress = wolBroadcastAddress.ifBlank { "255.255.255.255" },
            )
        }
    val canonicalHostKeyFingerprint =
        requireNotNull(canonicalOpenSshSha256Fingerprint(hostKeyFingerprint)) {
            "SSH host key fingerprint must be an OpenSSH SHA256 fingerprint"
        }
    return DeviceProfile(
        id = editingProfileId ?: DeviceProfileId("profile-$now"),
        displayName = displayName.trim(),
        localHost = HostAddress(localHost.trim()),
        vpnHost = vpnHost.trim().takeIf { it.isNotBlank() }?.let { host -> HostAddress(host, existingProfile?.vpnHost?.port) },
        relayDeviceId = relayId,
        sshPort = sshPort.toInt(),
        username = username.trim(),
        authMethod = authMethod,
        credentialsRef = credentialsRef,
        localProtocolTokenRef = existingProfile?.localProtocolTokenRef,
        localAgentCertificateFingerprint = existingProfile?.localAgentCertificateFingerprint,
        authorizedDeviceId = existingProfile?.authorizedDeviceId,
        pairedHostIdentity = existingProfile?.pairedHostIdentity,
        remoteSshPort = existingProfile?.remoteSshPort,
        availableWakeOnLanConfigs = existingProfile?.availableWakeOnLanConfigs.orEmpty(),
        hostKeyFingerprint = HostKeyFingerprint("SHA256", canonicalHostKeyFingerprint),
        wakeOnLanConfig = wolConfig,
        permissions =
            existingProfile?.permissions?.copy(
                wakeOnLan = wolConfig != null,
                remoteAccess = remoteAccessEnabled,
            ) ?: DevicePermissions(
                terminal = true,
                visual = true,
                input = true,
                sftp = true,
                clipboard = true,
                wakeOnLan = wolConfig != null,
                remoteAccess = remoteAccessEnabled,
            ),
        remoteAccessEnabled = remoteAccessEnabled,
        lastConnectedAtEpochMillis = existingProfile?.lastConnectedAtEpochMillis,
        lastSuccessfulRoute = existingProfile?.lastSuccessfulRoute,
        defaultMonitorId = existingProfile?.defaultMonitorId,
        qualityPreference = existingProfile?.qualityPreference ?: dev.aegis.remote.core.model.QualityMode.Balanced,
    ).let { saved -> saved.copy(advertisedEndpoints = saved.stableAdvertisedEndpoints()) }
}

internal fun AddProfileDraft.sshCredentialSecret(): ByteArray =
    when (authMethod) {
        AuthMethod.Password -> {
            sshPassword.toByteArray(Charsets.UTF_8)
        }

        AuthMethod.PrivateKey -> {
            encodePrivateKeyCredentials(
                privateKey = sshPrivateKey,
                passphrase = sshPrivateKeyPassphrase,
            )
        }

        AuthMethod.Agent -> {
            error("SSH agent authentication is not available on Android")
        }
    }

internal fun VisualSessionPlan.toVisualUiState(
    sessionId: String,
    dataChannelPayload: String?,
    application: Application,
    relaySendSucceeded: Boolean = false,
    relaySendError: String? = null,
    inputEnabled: Boolean = false,
    clipboardEnabled: Boolean = false,
): VisualUiState =
    VisualUiState(
        busy = false,
        prepared = true,
        streaming = false,
        videoState = if (relaySendSucceeded) application.getString(R.string.status_preparing) else null,
        route = application.getString(route.type.labelRes()),
        video = videoConfig.label(),
        ice = ice.label(),
        selectedMonitorId = videoConfig.monitorId,
        qualityMode = videoConfig.qualityMode,
        inputEnabled = inputEnabled,
        clipboardEnabled = clipboardEnabled,
        dataChannelPayload = dataChannelPayload,
        dataChannelSessionId = sessionId,
        message =
            when {
                relaySendError != null -> application.getString(R.string.visual_prepare_failed)
                else -> application.getString(R.string.visual_preparing)
            },
    )

internal fun VisualUiState.withVideoState(
    state: VideoSessionState,
    application: Application,
): VisualUiState =
    when (state) {
        VideoSessionState.Idle -> {
            copy(streaming = false, videoState = application.getString(R.string.visual_waiting), message = null)
        }

        VideoSessionState.Negotiating -> {
            copy(
                streaming = false,
                videoState = application.getString(R.string.status_preparing),
                message = application.getString(R.string.visual_preparing),
            )
        }

        VideoSessionState.Streaming -> {
            copy(
                streaming = true,
                videoState = application.getString(R.string.visual_streaming),
                message = application.getString(R.string.status_connected),
            )
        }

        VideoSessionState.Reconnecting -> {
            copy(
                streaming = false,
                videoState = application.getString(R.string.status_reconnecting),
                message = application.getString(R.string.visual_reconnecting),
            )
        }

        is VideoSessionState.Failed -> {
            copy(streaming = false, videoState = application.getString(R.string.status_failed), message = state.reason)
        }
    }

internal fun VisualUiState.withProtocolMessage(message: ProtocolMessage): VisualUiState =
    when (message) {
        is ProtocolMessage.Stats -> {
            // The legacy cross-peer ConnectionStats envelope has non-nullable
            // fields and cannot distinguish "unavailable" from zero. Keep it
            // for adaptive-quality compatibility, but present only Android's
            // measured nullable RTC stats to the user.
            this
        }

        is ProtocolMessage.Monitors -> {
            copy(
                monitorOptions = message.monitors,
                monitors =
                    message.monitors.joinToString { monitor ->
                        "${monitor.name} ${monitor.width}x${monitor.height}${if (monitor.primary) " primary" else ""}"
                    },
            )
        }

        is ProtocolMessage.Control -> {
            when (val command = message.command) {
                is ControlCommand.Error -> copy(message = "${command.code}: ${command.message}", videoState = "Control error")
                else -> this
            }
        }

        else -> {
            this
        }
    }

@Suppress("CyclomaticComplexMethod")
internal fun VisualUiState.withNativeStats(stats: AndroidRtcLiveStats): VisualUiState =
    copy(
        stats =
            buildList {
                val ice =
                    buildString {
                        stats.routeType?.let { append("ICE ${it.name}") }
                        if (stats.localCandidateType != null || stats.remoteCandidateType != null) {
                            if (isNotEmpty()) append(' ')
                            append("${stats.localCandidateType ?: "?"}->${stats.remoteCandidateType ?: "?"}")
                        }
                        stats.transportProtocol?.let { append("/$it") }
                        stats.turnTransport?.let { append(" TURN-$it") }
                    }
                if (ice.isNotEmpty()) add(ice)
                stats.rttMs?.let { add("RTT ${it}ms") }
                stats.bitrateKbps?.let { add("${it}kbps") }
                stats.packetLossPercent?.let { add("loss ${"%.2f".format(it)}%") }
                stats.jitterMs?.let { add("jitter ${"%.2f".format(it)}ms") }
                stats.framesDropped?.let { add("dropped $it") }
                stats.freezeCount?.let { add("freezes $it") }
                stats.decodeMs?.let { add("decode ${"%.2f".format(it)}ms") }
                stats.fps?.let { add("${it}fps") }
                stats.resolution?.let(::add)
            }.joinToString(" | "),
    )

internal class AndroidVisualDataChannelClient(
    private val sessionId: SessionId,
    private val session: AndroidWebRtcViewerSession,
) : DataChannelClient {
    override suspend fun sendInput(event: RemoteInputEvent) {
        session.sendProtocolMessage(ProtocolMessage.Input(sessionId, event))
    }

    override suspend fun sendClipboardText(text: String) {
        sendInput(RemoteInputEvent.ClipboardSync(text))
    }

    override suspend fun selectMonitor(monitorId: MonitorId) {
        session.selectMonitor(monitorId.value)
    }

    override suspend fun setQuality(config: VideoConfig) {
        session.setQuality(config)
    }

    override suspend fun sendPing(sentAtEpochMillis: Long) {
        sendControl(ControlCommand.Ping(sentAtEpochMillis))
    }

    override suspend fun sendPong(
        pingSentAtEpochMillis: Long,
        receivedAtEpochMillis: Long,
    ) {
        sendControl(ControlCommand.Pong(pingSentAtEpochMillis, receivedAtEpochMillis))
    }

    override suspend fun sendError(
        code: String,
        message: String,
    ) {
        sendControl(ControlCommand.Error(code, message))
    }

    private suspend fun sendControl(command: ControlCommand) {
        session.sendProtocolMessage(ProtocolMessage.Control(sessionId, command))
    }
}

internal fun VideoConfig.label(): String =
    "${width}x$height @ ${fps}fps, ${bitrateKbps}kbps, quality=$qualityMode, " +
        "monitor=${monitorId?.value ?: "default"}, route=$routeType"

private fun StunTurnConfig.label(): String {
    val parts =
        buildList {
            if (stunUrls.isNotEmpty()) add("STUN: ${stunUrls.joinToString()}")
            turnConfig?.let { turn ->
                val credential = turn.credentialRef ?: "inline credential"
                add("TURN: ${turn.urls.joinToString()} | credential=$credential | expires=${turn.expiresAtEpochMillis ?: "not set"}")
            }
        }
    return parts.ifEmpty { listOf("No ICE servers required for LAN/VPN route") }.joinToString("\n")
}

internal fun DeviceProfile.localProtocolAuthorizedDeviceId(): String? =
    authorizedDeviceId?.takeIf(String::isNotBlank)
        ?: id.value.removePrefix("paired-").takeIf { token ->
            id.value.startsWith("paired-") && !id.value.startsWith("paired-host-") && token.isNotBlank()
        }

internal fun pairedProfileIdForHost(identity: DevicePublicIdentity): DeviceProfileId {
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.deviceId.value.toByteArray(Charsets.UTF_8))
    val stableToken =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
            .take(32)
    return DeviceProfileId("paired-host-$stableToken")
}

internal fun ConnectionRoute.supportsLocalProtocol(): Boolean = this is ConnectionRoute.LanRoute || this is ConnectionRoute.VpnRoute

internal const val LOCAL_PAIRING_PORT = 48291
internal const val LOCAL_PAIRING_LOG_TAG = "AegisPairing"
private const val ANDROID_SESSION_CLEANUP_TIMEOUT_MILLIS = 5_000L
private const val HOST_PRESENCE_TICK_MILLIS = 2_000L
internal const val VISUAL_ICE_RESTART_GRACE_MILLIS = 12_000L
internal const val RELAY_RECONNECT_APPROVAL_TIMEOUT_MILLIS = 30_000L
private const val WOL_CONFIRMATION_ATTEMPTS = 6
private const val WOL_CONFIRMATION_INTERVAL_MILLIS = 2_000L
private const val WOL_CONNECT_TIMEOUT_MILLIS = 1_000

internal fun String.toRemoteTextInputEvents(): List<RemoteInputEvent> {
    if (isEmpty()) return emptyList()
    val events = mutableListOf<RemoteInputEvent>()
    val normalized = replace("\r\n", "\n").replace('\r', '\n')
    val parts = normalized.split('\n')
    parts.forEachIndexed { index, part ->
        if (part.isNotEmpty()) {
            events += RemoteInputEvent.TextInput(part)
        }
        if (index != parts.lastIndex) {
            events += RemoteInputEvent.Key(KeyCode.Enter, pressed = true)
            events += RemoteInputEvent.Key(KeyCode.Enter, pressed = false)
        }
    }
    return events
}

private fun TransferProgress.label(): String {
    val total = totalBytes
    val progress =
        if (total != null && total > 0) {
            val percent = (bytesTransferred * 100 / total).coerceIn(0, 100)
            "$bytesTransferred / $total bytes ($percent%)"
        } else {
            "$bytesTransferred bytes"
        }
    return if (done) "$progress done" else progress
}

internal fun String?.logId(): String = this?.take(8)?.ifBlank { "unknown" } ?: "unknown"
