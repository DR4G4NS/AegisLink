package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.pairing.DeviceTrustStore
import dev.aegis.remote.core.pairing.LanAuthorizationStatus
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.localProtocolSessionProofPayload
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.storage.AppSettingsRepository
import dev.aegis.remote.core.storage.RelayConfigRepository
import dev.aegis.remote.core.webrtc.VideoSessionState
import dev.aegis.remote.desktop.clipboard.DesktopClipboardBridgeFactory
import dev.aegis.remote.desktop.input.DesktopRemoteInputExecutorFactory
import dev.aegis.remote.desktop.webrtc.DesktopNativeWebRtcSenderSession
import dev.aegis.remote.desktop.webrtc.DesktopProtocolChannelBridge
import dev.aegis.remote.desktop.webrtc.DesktopProtocolMessageHandler
import dev.aegis.remote.desktop.webrtc.DesktopProtocolSignalingClient
import dev.aegis.remote.desktop.webrtc.DesktopProtocolTelemetryBridge
import dev.aegis.remote.desktop.webrtc.DesktopResolvedTurnCredentials
import dev.aegis.remote.desktop.webrtc.DesktopWebRtcMonitorProvider
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DesktopAgent(
    private val pairingServer: LocalPairingServer,
    private val dependencies: DesktopAgentDependencies,
) {
    /** Compatibility bridge for existing embedders; application wiring uses DesktopAgentFactory. */
    @Deprecated("Use DesktopAgentFactory.create or DesktopAgentFactory.production")
    constructor(
        pairingServer: LocalPairingServer,
        trustStore: DeviceTrustStore = FileDeviceTrustStore(),
        relayConnector: DesktopRelayConnector? = KtorDesktopRelayConnector.fromEnvironment(),
        monitorProvider: MonitorProvider = DesktopWebRtcMonitorProvider(),
        capabilityDetector: DesktopCapabilityDetector = DesktopPlatformCapabilityDetector(),
        inputExecutorFactory: DesktopRemoteInputExecutorFactory = DesktopRemoteInputExecutorFactory(),
        clipboardBridgeFactory: DesktopClipboardBridgeFactory = DesktopClipboardBridgeFactory(),
        settingsRepository: AppSettingsRepository = FileDesktopAppSettingsRepository(),
        relayConfigRepository: RelayConfigRepository =
            (settingsRepository as? RelayConfigRepository) ?: FileDesktopAppSettingsRepository(),
        relayConnectorFactory: (RelayConfig) -> DesktopRelayConnector = { config ->
            KtorDesktopRelayConnector(
                relayUrl = config.relayUrl,
                relayDeviceId = config.deviceId,
                remoteAccessEnabled = config.enabled,
            )
        },
        autostartManager: DesktopAutostartManager = UserDesktopAutostartManager(),
        manualConnectionInfoProvider: DesktopManualConnectionInfoProvider =
            SystemDesktopManualConnectionInfoProvider(),
        openSshProvisioner: AegisOpenSshManager? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        localProtocolAuthenticator: LocalProtocolAuthenticator = LocalProtocolAuthenticator(),
        relayReconnectionPolicy: DesktopRelayReconnectPolicy = BoundedDesktopRelayReconnectPolicy(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(
        pairingServer = pairingServer,
        dependencies =
            DesktopAgentDependencies(
                trustStore = trustStore,
                relayConnector = relayConnector,
                monitorProvider = monitorProvider,
                capabilityDetector = capabilityDetector,
                inputExecutorFactory = inputExecutorFactory,
                clipboardBridgeFactory = clipboardBridgeFactory,
                settingsRepository = settingsRepository,
                relayConfigRepository = relayConfigRepository,
                relayConnectorFactory = relayConnectorFactory,
                autostartManager = autostartManager,
                manualConnectionInfoProvider = manualConnectionInfoProvider,
                openSshProvisioner = openSshProvisioner,
                clock = clock,
                localProtocolAuthenticator = localProtocolAuthenticator,
                relayReconnectionPolicy = relayReconnectionPolicy,
                scope = scope,
            ),
    )

    private val trustStore = dependencies.trustStore
    private val monitorProvider = dependencies.monitorProvider
    private val capabilityDetector = dependencies.capabilityDetector
    private val inputExecutorFactory = dependencies.inputExecutorFactory
    private val clipboardBridgeFactory = dependencies.clipboardBridgeFactory
    private val settingsRepository = dependencies.settingsRepository
    private val relayConfigRepository = dependencies.relayConfigRepository
    private val relayConnectorFactory = dependencies.relayConnectorFactory
    private val autostartManager = dependencies.autostartManager
    private val manualConnectionInfoProvider = dependencies.manualConnectionInfoProvider
    private val openSshProvisioner = dependencies.openSshProvisioner
    private val clock = dependencies.clock
    private val localProtocolAuthenticator = dependencies.localProtocolAuthenticator
    private val relayReconnectionPolicy = dependencies.relayReconnectionPolicy
    private val lifecycleJob = SupervisorJob(dependencies.scope.coroutineContext[Job])
    private val scope = CoroutineScope(dependencies.scope.coroutineContext + lifecycleJob)
    private val lifecycleLock = Any()
    private var started = false
    private var stopped = false
    private var lifecycleGeneration = 0L
    private var startupJob: Job? = null
    private val _state = MutableStateFlow(DesktopAgentState())
    val state: StateFlow<DesktopAgentState> = _state

    private val settingsCoordinator: DesktopSettingsCoordinator by lazy {
        DesktopSettingsCoordinator(
            DesktopSettingsDependencies(
                scope = scope,
                settingsRepository = settingsRepository,
                relayConfigRepository = relayConfigRepository,
                monitorProvider = monitorProvider,
                capabilityDetector = capabilityDetector,
                manualConnectionInfoProvider = manualConnectionInfoProvider,
                openSshProvisioner = openSshProvisioner,
                autostartManager = autostartManager,
                clock = clock,
                telemetryBridges = { relayTelemetryBridges.values.toList() },
                inputHandlers = { remoteInputHandlers.values.toList() },
                updateState = { update -> _state.update(update) },
            ),
        )
    }
    private val trustCoordinator: DesktopTrustCoordinator by lazy {
        DesktopTrustCoordinator(
            DesktopTrustDependencies(
                state = state,
                scope = scope,
                trustStore = trustStore,
                openSshProvisioner = openSshProvisioner,
                closeDeviceSessionResources = ::closeDeviceSessionResources,
                restart = ::start,
                clock = clock,
                updateState = { update -> _state.update(update) },
            ),
        )
    }
    private val pairingCoordinator: DesktopPairingCoordinator by lazy {
        DesktopPairingCoordinator(
            DesktopPairingDependencies(
                state = state,
                scope = scope,
                pairingServer = pairingServer,
                trustStore = trustStore,
                openSshProvisioner = openSshProvisioner,
                pairingServerIdentityProvider = ::pairingServerIdentity,
                pairingHostProvider = { pairingHostFromState() },
                clock = clock,
                encodeQrPayload = { session -> session.toQrPayloadJson(pairingJson) },
                onHostIdentityChanged = { identity -> currentPairingHostIdentity = identity },
                refreshAuthorizedDevices = { eventCode, message, level, context, error ->
                    trustCoordinator.refreshAuthorizedDevices(eventCode, message, level, context, error)
                },
                updateState = { update -> _state.update(update) },
            ),
        )
    }
    private var activeRelayConnector: DesktopRelayConnector? = dependencies.relayConnector
    private var relayConfigOwnedBySettings: Boolean = false
    private val relayProtocolBridges = ConcurrentHashMap<String, DesktopProtocolChannelBridge>()
    private val relayTelemetryBridges = ConcurrentHashMap<String, DesktopProtocolTelemetryBridge>()
    private val remoteInputHandlers = ConcurrentHashMap<String, DesktopProtocolMessageHandler>()
    private val videoStateJobs = ConcurrentHashMap<String, Job>()
    private val localProtocolDeviceByKey = ConcurrentHashMap<String, String>()
    private val authenticatedLocalProtocolSessionIds = ConcurrentHashMap<String, Long>()
    private val activeLocalProtocolSessionIds = ConcurrentHashMap<String, String>()

    private val relaySessionCoordinator: DesktopRelaySessionCoordinator by lazy {
        DesktopRelaySessionCoordinator(
            DesktopRelaySessionDependencies(
                state = state,
                scope = scope,
                clock = clock,
                connectorProvider = { activeRelayConnector },
                remoteAccessEnabled = { desiredRemoteAccessEnabled },
                trustStore = trustStore,
                relayProtocolBridges = relayProtocolBridges,
                relayTelemetryBridges = relayTelemetryBridges,
                remoteInputHandlers = remoteInputHandlers,
                videoStateJobs = videoStateJobs,
                localProtocolDeviceByKey = localProtocolDeviceByKey,
                openDesktopProtocolChannel = { sessionId, permissions, channel, mapKey, label, turnResolver ->
                    desktopSessionCoordinator.openProtocolChannel(
                        sessionId = sessionId,
                        permissions = permissions,
                        channel = channel,
                        mapKey = mapKey,
                        label = label,
                        turnCredentialResolver = turnResolver,
                    )
                },
                updateState = { update -> _state.update(update) },
            ),
        )
    }

    private val relayOperationsCoordinator: DesktopRelayOperationsCoordinator by lazy {
        DesktopRelayOperationsCoordinator(
            DesktopRelayOperationsDependencies(
                state = state,
                scope = scope,
                clock = clock,
                reconnectionPolicy = relayReconnectionPolicy,
                connectorProvider = { activeRelayConnector },
                setConnector = { connector -> activeRelayConnector = connector },
                relayConfigOwnedBySettings = { relayConfigOwnedBySettings },
                setRelayConfigOwnedBySettings = { owned -> relayConfigOwnedBySettings = owned },
                remoteAccessEnabled = { desiredRemoteAccessEnabled },
                setRemoteAccessEnabled = { enabled -> desiredRemoteAccessEnabled = enabled },
                relayConfigRepository = relayConfigRepository,
                relayConnectorFactory = relayConnectorFactory,
                relaySessionCoordinator = relaySessionCoordinator,
                updateState = { update -> _state.update(update) },
            ),
        )
    }

    private val desktopSessionCoordinator: DesktopSessionCoordinator by lazy {
        DesktopSessionCoordinator(
            DesktopSessionDependencies(
                state = state,
                scope = scope,
                monitorProvider = monitorProvider,
                inputExecutorFactory = inputExecutorFactory,
                clipboardBridgeFactory = clipboardBridgeFactory,
                clock = clock,
                relayProtocolBridges = relayProtocolBridges,
                relayTelemetryBridges = relayTelemetryBridges,
                remoteInputHandlers = remoteInputHandlers,
                videoStateJobs = videoStateJobs,
                localProtocolDeviceByKey = localProtocolDeviceByKey,
                remoteSessionCoordinatorProvider = { relayOperationsCoordinator.remoteSessionCoordinator() },
                updateState = { update -> _state.update(update) },
            ),
        )
    }

    private val localProtocolCoordinator: DesktopLocalProtocolCoordinator by lazy {
        DesktopLocalProtocolCoordinator(
            DesktopLocalProtocolDependencies(
                trustStore = trustStore,
                localProtocolAuthenticator = localProtocolAuthenticator,
                clock = clock,
                localProtocolDeviceByKey = localProtocolDeviceByKey,
                authenticatedSessionIds = authenticatedLocalProtocolSessionIds,
                activeSessionIds = activeLocalProtocolSessionIds,
                relayProtocolBridges = relayProtocolBridges,
                relayTelemetryBridges = relayTelemetryBridges,
                videoStateJobs = videoStateJobs,
                isGenerationActive = ::lifecycleActive,
                updateState = { update -> _state.update(update) },
                openProtocolChannel = ::openDesktopProtocolChannel,
            ),
        )
    }

    @Volatile
    private var currentPairingHostIdentity: DevicePublicIdentity? = null

    @Volatile
    private var currentPairingSession: LocalPairingSession? = null

    private val lanAnnouncer =
        LanMulticastAnnouncer(
            payload = { currentPairingSession?.toLanAnnouncePayload() },
        )

    private var desiredRemoteAccessEnabled: Boolean = dependencies.relayConnector?.remoteAccessEnabled == true
    private val pairingJson =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    fun start() {
        val job =
            synchronized(lifecycleLock) {
                if (started || stopped) return
                started = true
                val generation = ++lifecycleGeneration
                scope
                    .launch(start = CoroutineStart.LAZY) {
                        startInternal(generation)
                    }.also { startupJob = it }
            }
        job.start()
    }

    private suspend fun startInternal(generation: Long) {
        try {
            if (!lifecycleActive(generation)) return
            val authorizedDevices =
                runCatching { trustStore.listAuthorizedDevices() }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        trustCoordinator.publishTrustStoreUnavailable(error)
                        markStartupFailed(generation)
                        return
                    }
            runCatching {
                check(lifecycleActive(generation)) { "Desktop agent startup was cancelled" }
                pairingServer.bindDeviceAuthorizationLookup { deviceId ->
                    val record = trustStore.authorization(deviceId)
                    when {
                        record == null -> LanAuthorizationStatus.NOT_FOUND
                        record.revokedAtEpochMillis != null -> LanAuthorizationStatus.REVOKED
                        else -> LanAuthorizationStatus.ACTIVE
                    }
                }
                pairingServer.bindDevicePermissionsLookup { deviceId ->
                    trustStore.authorization(deviceId)?.takeIf { it.revokedAtEpochMillis == null }?.permissions
                }
                val pairingSession =
                    pairingServer.start(
                        onPairingRequest = { request -> onPairingRequest(generation, request) },
                        onProtocolChannel = { request -> onLocalProtocolChannel(generation, request) },
                        onPairingEvent = { event -> onPairingEvent(generation, event) },
                    )
                if (!lifecycleActive(generation)) {
                    pairingServer.stop()
                    return@runCatching
                }
                currentPairingHostIdentity = pairingSession.hostIdentity
                currentPairingSession = pairingSession
                val startupSettings = settingsCoordinator.loadStartupSettings(pairingSession.url)
                check(lifecycleActive(generation)) { "Desktop agent startup was cancelled" }
                val storedRelayConfig = startupSettings.storedRelayConfig
                if (activeRelayConnector == null && storedRelayConfig != null) {
                    activeRelayConnector = relayConnectorFactory(storedRelayConfig)
                    desiredRemoteAccessEnabled = storedRelayConfig.enabled
                    relayConfigOwnedBySettings = true
                }
                _state.update {
                    it.withStartedPairingSession(
                        pairingSession = pairingSession,
                        startupSettings = startupSettings,
                        authorizedDevices = authorizedDevices,
                        activeRelayConnector = activeRelayConnector,
                        remoteAccessEnabled = desiredRemoteAccessEnabled,
                        pairingQrPayload = pairingSession.toQrPayloadJson(pairingJson),
                        clock = clock,
                    )
                }
                pairingCoordinator.schedule(pairingSession.expiresAtEpochMillis)
                runCatching { lanAnnouncer.start() }
                relayOperationsCoordinator.registerIfConfigured()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (!lifecycleActive(generation)) return@onFailure
                runCatching { pairingServer.stop() }
                currentPairingHostIdentity = null
                currentPairingSession = null
                runCatching { lanAnnouncer.stop() }
                _state.update {
                    it.withLog(
                        now = clock(),
                        level = "error",
                        message = "Failed to start pairing server: ${error.message}",
                        eventCode = AgentLogEventCode.PairingServerStartFailed,
                        error = error,
                    )
                }
                markStartupFailed(generation)
            }
        } finally {
            val currentJob = currentCoroutineContext()[Job]
            synchronized(lifecycleLock) {
                if (startupJob === currentJob) startupJob = null
            }
        }
    }

    fun restoreTrustStoreFromBackup(backupPath: String) = trustCoordinator.restoreTrustStoreFromBackup(backupPath)

    fun updateDevicePermissions(
        remoteDeviceId: String,
        permissions: dev.aegis.remote.core.model.DevicePermissions,
    ) = trustCoordinator.updatePermissions(remoteDeviceId, permissions)

    fun resetTrustStoreAfterConsent() = trustCoordinator.resetTrustStoreAfterConsent()

    fun stop() {
        val pendingStartup: Job?
        synchronized(lifecycleLock) {
            if (stopped) return
            stopped = true
            started = false
            lifecycleGeneration += 1
            pendingStartup = startupJob
            startupJob = null
        }
        pendingStartup?.cancel()
        // Stop network-facing SSH sockets before any potentially slow native cleanup.
        runBlocking {
            withTimeoutOrNull(DESKTOP_SHUTDOWN_TIMEOUT_MILLIS) { runCatching { openSshProvisioner?.shutdown() } }
        }
        pairingCoordinator.stop()
        currentPairingHostIdentity = null
        currentPairingSession = null
        runCatching { lanAnnouncer.stop() }
        pairingServer.stop()
        relayOperationsCoordinator.stop()
        val protocolBridges = relayProtocolBridges.values.toList()
        val telemetryBridges = relayTelemetryBridges.values.toList()
        val inputHandlers = remoteInputHandlers.values.toList()
        val stateJobs = videoStateJobs.values.toList()
        relayProtocolBridges.clear()
        relayTelemetryBridges.clear()
        remoteInputHandlers.clear()
        videoStateJobs.clear()
        localProtocolDeviceByKey.clear()
        authenticatedLocalProtocolSessionIds.clear()
        activeLocalProtocolSessionIds.clear()
        stateJobs.forEach(Job::cancel)
        runBlocking {
            // Waiting for a pending startup is best-effort: a startup hung on a
            // slow system probe must not consume the whole shutdown budget and
            // skip resource cleanup (leaking the OpenSSH child or the relay
            // connector). Cleanup below always runs under its own timeout.
            withTimeoutOrNull(DESKTOP_SHUTDOWN_TIMEOUT_MILLIS) { pendingStartup?.join() }
            withTimeoutOrNull(DESKTOP_SHUTDOWN_TIMEOUT_MILLIS) {
                telemetryBridges.forEach { bridge ->
                    bridge.stopStatsForwarding()
                    bridge.stopClipboardForwarding()
                }
                inputHandlers.forEach { handler -> runCatching { handler.close() } }
                protocolBridges.forEach { bridge -> runCatching { bridge.stop() } }
                runCatching { activeRelayConnector?.close() }
            }
        }
        _state.update {
            it
                .copy(
                    pairingServerRunning = false,
                    pairingUrl = null,
                    pairingCode = null,
                    agentFingerprint = null,
                    pairingQrPayload = null,
                    pairingQrExpiresAtEpochMillis = null,
                    pairingQrRefreshing = false,
                    relayConnected = false,
                    remoteAccessEnabled = false,
                    pendingPairingRequests = emptyList(),
                    pendingRelaySessions = emptyList(),
                ).withLog(clock(), "info", "Local pairing server stopped")
        }
        lifecycleJob.cancel()
    }

    private fun lifecycleActive(generation: Long): Boolean =
        synchronized(lifecycleLock) {
            started && !stopped && lifecycleGeneration == generation && lifecycleJob.isActive
        }

    private fun markStartupFailed(generation: Long) {
        synchronized(lifecycleLock) {
            if (!stopped && lifecycleGeneration == generation) started = false
        }
    }

    fun refreshPairingQr() = pairingCoordinator.refresh()

    fun approvePairing(requestId: String) = pairingCoordinator.approvePairing(requestId)

    fun rejectPairing(requestId: String) = pairingCoordinator.rejectPairing(requestId)

    fun revoke(remoteDeviceId: String) = trustCoordinator.revoke(remoteDeviceId)

    fun retrySshKeyRemoval(remoteDeviceId: String) = trustCoordinator.retrySshKeyRemoval(remoteDeviceId)

    fun deleteRevokedDevice(remoteDeviceId: String) = trustCoordinator.deleteRevokedDevice(remoteDeviceId)

    private suspend fun closeDeviceSessionResources(remoteDeviceId: String) {
        val activeKeys = localProtocolDeviceByKey.filterValues { it == remoteDeviceId }.keys.toList()
        activeKeys.forEach { key ->
            localProtocolDeviceByKey.remove(key)
            relayTelemetryBridges.remove(key)?.let { telemetry ->
                telemetry.stopStatsForwarding()
                telemetry.stopClipboardForwarding()
            }
            videoStateJobs.remove(key)?.cancel()
            remoteInputHandlers.remove(key)?.let { handler -> runCatching { handler.close() } }
            relayProtocolBridges.remove(key)?.stop()
        }
        activeLocalProtocolSessionIds.entries.removeIf { it.value == remoteDeviceId }
    }

    fun approveRelaySession(sessionIdValue: String) = relayOperationsCoordinator.approveRelaySession(sessionIdValue)

    fun rejectRelaySession(sessionIdValue: String) = relayOperationsCoordinator.rejectRelaySession(sessionIdValue)

    fun setRemoteAccessEnabled(enabled: Boolean) = relayOperationsCoordinator.setRemoteAccessEnabled(enabled)

    fun configureRelay(
        relayUrl: String,
        relayDeviceId: String,
    ) = relayOperationsCoordinator.configureRelay(relayUrl, relayDeviceId)

    fun clearRelayConfiguration() = relayOperationsCoordinator.clearRelayConfiguration()

    fun rotateRelayIdentity() = relayOperationsCoordinator.rotateRelayIdentity()

    fun setClipboardSyncEnabled(enabled: Boolean) = settingsCoordinator.setClipboardSyncEnabled(enabled)

    fun setRemoteInputEnabled(enabled: Boolean) = settingsCoordinator.setRemoteInputEnabled(enabled)

    fun setAutostartEnabled(enabled: Boolean) = settingsCoordinator.setAutostartEnabled(enabled)

    private fun onPairingRequest(
        generation: Long,
        request: DesktopPairingRequest,
    ): PairingRequestDispatchResult {
        synchronized(lifecycleLock) {
            if (!lifecycleActive(generation)) return PairingRequestDispatchResult.AgentUnavailable
            if (_state.value.pendingPairingRequests.any { it.requestId == request.requestId }) {
                return PairingRequestDispatchResult.AgentUnavailable
            }
            _state.update {
                it
                    .copy(pendingPairingRequests = it.pendingPairingRequests + request)
                    .withLog(
                        now = clock(),
                        level = "info",
                        message = "Pairing request received from ${request.deviceName}",
                        eventCode = AgentLogEventCode.PairingRequestReceived,
                        context =
                            mapOf(
                                "requestId" to request.requestId,
                                "deviceName" to request.deviceName,
                                "transport" to if (request.remote) "relay" else "local",
                                "localHost" to request.localHost.orEmpty(),
                            ),
                    )
            }
        }
        // The scanned token has been consumed before this callback. Rotate it
        // immediately so the visible QR never invites a second, doomed scan.
        pairingCoordinator.refresh()
        return PairingRequestDispatchResult.Accepted
    }

    private fun onPairingEvent(
        generation: Long,
        event: PairingServerEvent,
    ) {
        val sanitized = event.copy(lifecycleGeneration = generation)
        _state.update {
            val expiredState =
                if (sanitized.stage == PairingServerEventStage.PAIRING_STATUS_POLLED &&
                    sanitized.result == LocalPairingRequestStatus.Expired.name.lowercase() &&
                    sanitized.requestId != null
                ) {
                    it
                        .pendingPairingRequests
                        .firstOrNull { request -> request.requestId == sanitized.requestId }
                        ?.let { request ->
                            it
                                .copy(
                                    pendingPairingRequests =
                                        it.pendingPairingRequests.filterNot { pending -> pending.requestId == request.requestId },
                                ).withLog(
                                    now = clock(),
                                    level = "warn",
                                    message = "Expired pairing request from ${request.deviceName}",
                                    eventCode = AgentLogEventCode.PairingRequestExpired,
                                    context = mapOf("requestId" to request.requestId),
                                )
                        } ?: it
                } else {
                    it
                }
            expiredState.withLog(
                now = clock(),
                level = if (sanitized.stage == PairingServerEventStage.PAIRING_REQUEST_DISPATCH_REJECTED) "warn" else "info",
                message = sanitized.stage.name,
                eventCode = sanitized.stage.toAgentLogEventCode(),
                context =
                    mapOf(
                        "requestId" to sanitized.requestId.orEmpty(),
                        "stage" to sanitized.stage.name,
                        "result" to sanitized.result,
                        "remoteHost" to sanitized.remoteHost,
                        "lifecycleGeneration" to sanitized.lifecycleGeneration.toString(),
                        "pendingCount" to sanitized.pendingCount.toString(),
                        "latencyMillis" to sanitized.latencyMillis.toString(),
                    ),
            )
        }
    }

    private suspend fun onLocalProtocolChannel(
        generation: Long,
        request: LocalProtocolChannelRequest,
    ) = localProtocolCoordinator.handle(generation, request)

    private suspend fun openDesktopProtocolChannel(
        sessionId: dev.aegis.remote.core.model.SessionId,
        permissions: dev.aegis.remote.core.model.DevicePermissions,
        channel: ProtocolMessageChannel,
        mapKey: String,
        label: String,
        turnCredentialResolver: suspend (dev.aegis.remote.core.model.TurnConfig) -> DesktopResolvedTurnCredentials?,
    ): Job =
        desktopSessionCoordinator.openProtocolChannel(
            sessionId = sessionId,
            permissions = permissions,
            channel = channel,
            mapKey = mapKey,
            label = label,
            turnCredentialResolver = turnCredentialResolver,
        )

    private fun pairingServerIdentity(): DevicePublicIdentity? = currentPairingHostIdentity
}

internal fun DesktopRelaySessionRequest.displayLabel(): String {
    val name = sourceDisplayName?.takeIf { it.isNotBlank() }
    return if (name == null) {
        sourceRelayDeviceId.value
    } else {
        "$name (${sourceRelayDeviceId.value})"
    }
}

internal fun DesktopRelaySessionRequest.trustDisplayName(): String =
    sourceDisplayName
        ?.takeIf { it.isNotBlank() }
        ?.let { "Relay $it" }
        ?: "Relay ${sourceRelayDeviceId.value}"

internal fun pairingLogContext(request: DesktopPairingRequest): Map<String, String> =
    mapOf(
        "requestId" to request.requestId,
        "deviceName" to request.deviceName,
        "transport" to if (request.remote) "relay" else "local",
        "localHost" to request.localHost.orEmpty(),
    ).filterValues(String::isNotBlank)

internal suspend fun DeviceTrustStore.authorization(remoteDeviceId: String): DeviceAuthorization? =
    listAuthorizedDevices().firstOrNull { authorization ->
        authorization.remoteDeviceId.value == remoteDeviceId
    }

internal fun Throwable?.sshKeyRemovalFailureCode(): String = (this as? AegisOpenSshException)?.failure?.code ?: SSH_KEY_REMOVAL_INCOMPLETE_CODE

internal fun DesktopAgentState.withLog(
    now: Long,
    level: String,
    message: String,
    eventCode: AgentLogEventCode = AgentLogEventCode.Generic,
    context: Map<String, String> = emptyMap(),
    error: Throwable? = null,
): DesktopAgentState {
    val entry =
        AgentLogEntry(
            timestampEpochMillis = now,
            level = level,
            message = message,
            eventCode = eventCode,
            context = context.filterValues(String::isNotBlank),
            errorType = error?.javaClass?.simpleName,
        )
    return copy(logs = (logs + entry).takeLast(100))
}

private fun PairingServerEventStage.toAgentLogEventCode(): AgentLogEventCode =
    when (this) {
        PairingServerEventStage.PAIRING_HTTP_REQUEST_RECEIVED -> AgentLogEventCode.PairingHttpRequestReceived
        PairingServerEventStage.PAIRING_REQUEST_VALIDATED -> AgentLogEventCode.PairingRequestValidated
        PairingServerEventStage.PAIRING_REQUEST_DISPATCHED -> AgentLogEventCode.PairingRequestDispatched
        PairingServerEventStage.PAIRING_REQUEST_VISIBLE -> AgentLogEventCode.PairingRequestVisible
        PairingServerEventStage.PAIRING_REQUEST_DISPATCH_REJECTED -> AgentLogEventCode.PairingRequestDispatchRejected
        PairingServerEventStage.PAIRING_STATUS_POLLED -> AgentLogEventCode.PairingStatusPolled
    }

internal fun desktopDisplayName(): String =
    System.getenv("COMPUTERNAME")
        ?: System.getenv("HOSTNAME")
        ?: "Desktop PC"

private fun DesktopAgent.pairingHostFromState(): String =
    state.value.pairingUrl
        ?.let { url -> runCatching { URI(url).host }.getOrNull() }
        ?.ifBlank { null }
        ?: "127.0.0.1"

internal data class RelayValidationFailure(
    val reason: RelayValidationReason,
    val fallbackMessage: String,
)

internal fun validateRelayConfiguration(
    relayUrl: String,
    relayDeviceId: String,
): RelayValidationFailure? {
    if (relayUrl.isBlank()) {
        return RelayValidationFailure(RelayValidationReason.UrlRequired, "La URL del relay es obligatoria")
    }
    val uri =
        runCatching { URI(relayUrl) }.getOrNull()
            ?: return RelayValidationFailure(RelayValidationReason.InvalidUrl, "La URL del relay no es válida")
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        return RelayValidationFailure(
            RelayValidationReason.CompleteHttpUrlRequired,
            "Usa una URL completa que empiece con http:// o https://",
        )
    }
    if (uri.userInfo != null || uri.fragment != null) {
        return RelayValidationFailure(
            RelayValidationReason.CredentialsOrFragmentNotAllowed,
            "La URL no debe contener credenciales ni fragmentos",
        )
    }
    if (relayDeviceId.isNotBlank() && !relayDeviceId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
        return RelayValidationFailure(
            RelayValidationReason.InvalidDeviceId,
            "El ID sólo puede contener letras, números, punto, guion, guion bajo o dos puntos",
        )
    }
    return null
}

internal const val LOCAL_PAIRING_APPROVAL_TTL_MILLIS = 120_000L
internal const val DESKTOP_SHUTDOWN_TIMEOUT_MILLIS = 3_000L
internal const val LOCAL_INPUT_RELEASE_TIMEOUT_MILLIS = 750L
internal const val PAIRING_QR_REFRESH_EARLY_MILLIS = 5_000L
internal const val PAIRING_QR_MINIMUM_REFRESH_DELAY_MILLIS = 1_000L

/** Stable code when a managed SSH key removal did not positively confirm removal. */
private const val SSH_KEY_REMOVAL_INCOMPLETE_CODE = "SSH-7326"
