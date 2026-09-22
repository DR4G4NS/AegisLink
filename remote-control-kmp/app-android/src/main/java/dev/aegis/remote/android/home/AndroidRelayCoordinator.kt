package dev.aegis.remote.android.home

import android.util.Log
import dev.aegis.remote.android.routing.AndroidTurnConfigProvider
import dev.aegis.remote.android.security.AndroidDeviceIdentityStore
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.session.ExponentialBackoffReconnectionManager
import dev.aegis.remote.core.storage.DeviceProfileRepository
import dev.aegis.remote.core.storage.RelayConfigRepository
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.relayclient.KtorRelayClient
import dev.aegis.remote.relayclient.RelayIdentityLifecycleCoordinator
import dev.aegis.remote.relayclient.RelayIdentityRotationPendingException
import dev.aegis.remote.relayclient.openE2eeProtocolMessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class AndroidRelayCoordinatorDependencies(
    val state: StateFlow<AndroidHomeUiState>,
    val scope: CoroutineScope,
    val repository: DeviceProfileRepository,
    val relayConfigRepository: RelayConfigRepository,
    val deviceIdentityStore: AndroidDeviceIdentityStore,
    val relayReconnectionManager: ExponentialBackoffReconnectionManager,
    val turnConfigProvider: AndroidTurnConfigProvider,
    val relayClientSlot: AndroidRelayClientSlot,
    val relayClientFactory: (RelayConfig) -> KtorRelayClient,
    val closeVisualSession: () -> Unit,
    val stopClipboardSync: () -> Unit,
    val remoteSessionCoordinatorProvider: () -> AndroidRemoteSessionCoordinator?,
    val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
)

/** Owns relay registration, identity lifecycle, event subscriptions and relay E2EE channels. */
internal class AndroidRelayCoordinator(
    private val dependencies: AndroidRelayCoordinatorDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val repository = dependencies.repository
    private val relayConfigRepository = dependencies.relayConfigRepository
    private val deviceIdentityStore = dependencies.deviceIdentityStore
    private val relayReconnectionManager = dependencies.relayReconnectionManager
    private val turnConfigProvider = dependencies.turnConfigProvider
    private val relayClientSlot = dependencies.relayClientSlot
    private val relayClientFactory = dependencies.relayClientFactory
    private val closeVisualSession = dependencies.closeVisualSession
    private val stopClipboardSync = dependencies.stopClipboardSync
    private val remoteSessionCoordinatorProvider = dependencies.remoteSessionCoordinatorProvider
    private val updateState = dependencies.updateState
    private val relayE2eeCoordinator =
        AndroidRelayE2eeCoordinator(
            deviceIdentityStore = deviceIdentityStore,
            relayClientProvider = { relayClientSlot.client },
        )
    private var relayEventsJob: Job? = null
    private var remoteSessionCoordinator: AndroidRemoteSessionCoordinator? = null

    val client: KtorRelayClient?
        get() = relayClientSlot.client

    fun sessionCoordinator(): AndroidRemoteSessionCoordinator? = remoteSessionCoordinator

    fun register() {
        val relay = state.value.relay
        val validationError = relay.validationError()
        if (validationError != null) {
            updateState { it.copy(relay = it.relay.copy(message = validationError)) }
            return
        }
        updateState { it.copy(relay = it.relay.copy(busy = true, message = "Registering this phone with relay...")) }
        scope.launch {
            runCatching {
                relayE2eeCoordinator.clearAllSessions()
                client?.close()
                relayClientSlot.client = null
                val client =
                    relayClientFactory(
                        RelayConfig(
                            relayUrl = relay.relayUrl.trim(),
                            deviceId = null,
                            authTokenRef = null,
                            enabled = relay.enabled,
                        ),
                    )
                val pendingRotation = deviceIdentityStore.pendingRotation()
                val (localIdentity, registration) =
                    runCatching {
                        client.connect()
                        if (pendingRotation == null) {
                            val identity = deviceIdentityStore.getOrCreate()
                            identity to
                                client.registerDeviceV2(
                                    identity = identity,
                                    displayName = relay.phoneName.trim(),
                                    remoteAccessEnabled = relay.enabled,
                                )
                        } else {
                            if (pendingRotation.phase == dev.aegis.remote.core.security.IdentityRotationPhase.Prepared) {
                                val locator =
                                    relay.phoneRelayDeviceId
                                        .trim()
                                        .takeIf(String::isNotBlank)
                                        ?.let(::RelayDeviceId)
                                        ?: relayConfigRepository.getRelayConfig()?.deviceId
                                        ?: error("IDN-1015: persisted relay locator is required to resume identity rotation")
                                client.prepareIdentityRotationRecoveryV2(pendingRotation.currentIdentity, locator)
                            }
                            RelayIdentityLifecycleCoordinator(deviceIdentityStore, client)
                                .rotate(
                                    reason = pendingRotation.reason,
                                    displayName = relay.phoneName.trim(),
                                    remoteAccessEnabled = relay.enabled,
                                ).let { result -> result.identity to result.registration }
                        }
                    }.getOrElse { error ->
                        client.close()
                        if (error is CancellationException) throw error
                        throw error
                    }
                relayClientSlot.client = client
                remoteSessionCoordinator =
                    AndroidRemoteSessionCoordinator(
                        localIdentity = localIdentity.publicIdentity,
                        relayUrl = relay.relayUrl.trim(),
                        clock = { System.currentTimeMillis() },
                    )
                relayConfigRepository.saveRelayConfig(
                    RelayConfig(
                        relayUrl = relay.relayUrl.trim(),
                        deviceId = registration.relayDeviceId,
                        authTokenRef = null,
                        enabled = relay.enabled,
                    ),
                )
                registration
            }.rethrowCancellation()
                .onSuccess { registration ->
                    updateState {
                        it.copy(
                            relay =
                                it.relay.copy(
                                    busy = false,
                                    registered = true,
                                    identityRotationPending = false,
                                    phoneRelayDeviceId = registration.relayDeviceId.value,
                                    tokenExpiresAtEpochMillis = registration.authToken.expiresAtEpochMillis,
                                    message = "Registered as ${registration.relayDeviceId.value}. Token is kept in memory only.",
                                ),
                        )
                    }
                    client?.let(::startEventMonitoring)
                }.onFailure { error ->
                    relayClientSlot.client = null
                    val pending = error is RelayIdentityRotationPendingException
                    updateState {
                        it.copy(
                            relay =
                                it.relay.copy(
                                    busy = false,
                                    registered = false,
                                    identityRotationPending = pending,
                                    message =
                                        if (pending) {
                                            "IDN-1011: identity rotation remains safely prepared; retry registration"
                                        } else {
                                            error.message ?: "Relay registration failed"
                                        },
                                ),
                        )
                    }
                }
        }
    }

    fun rotate() {
        val client = client
        val relay = state.value.relay
        if (client == null && relay.identityRotationPending) {
            register()
            return
        }
        if (client == null || (!relay.registered && !relay.identityRotationPending)) {
            updateState {
                it.copy(relay = it.relay.copy(message = "IDN-1012: register this phone before rotating its relay identity"))
            }
            return
        }
        if (relay.busy) return
        if (remoteSessionCoordinatorProvider() != null) {
            closeVisualSession()
        }
        relayEventsJob?.cancel()
        relayEventsJob = null
        stopClipboardSync()
        updateState {
            it.copy(
                relay =
                    it.relay.copy(
                        busy = true,
                        registered = false,
                        message = "Rotating relay identity and closing active remote sessions...",
                        approvedSessionIdsByRelayDevice = emptyMap(),
                        lastSessionApproved = false,
                    ),
            )
        }
        scope.launch {
            runCatching {
                relayE2eeCoordinator.clearAllSessions()
                val result =
                    RelayIdentityLifecycleCoordinator(deviceIdentityStore, client).rotate(
                        reason = dev.aegis.remote.core.security.KeyRotationReason.UserRequested,
                        displayName = relay.phoneName.trim(),
                        remoteAccessEnabled = relay.enabled,
                    )
                remoteSessionCoordinator =
                    AndroidRemoteSessionCoordinator(
                        localIdentity = result.identity.publicIdentity,
                        relayUrl = relay.relayUrl.trim(),
                        clock = { System.currentTimeMillis() },
                    )
                relayConfigRepository.saveRelayConfig(
                    RelayConfig(
                        relayUrl = relay.relayUrl.trim(),
                        deviceId = result.registration.relayDeviceId,
                        authTokenRef = null,
                        enabled = relay.enabled,
                    ),
                )
                result
            }.rethrowCancellation()
                .onSuccess { result ->
                    updateState {
                        it.copy(
                            relay =
                                it.relay.copy(
                                    busy = false,
                                    registered = true,
                                    identityRotationPending = false,
                                    phoneRelayDeviceId = result.registration.relayDeviceId.value,
                                    tokenExpiresAtEpochMillis = result.registration.authToken.expiresAtEpochMillis,
                                    message =
                                        "Relay identity rotated and confirmed at generation " +
                                            result.identity.publicIdentity.keyGeneration,
                                ),
                        )
                    }
                    startEventMonitoring(client)
                }.onFailure { error ->
                    val message =
                        if (error is RelayIdentityRotationPendingException) {
                            "IDN-1011: rotation ${error.operationId.take(12)} remains safely prepared; retry this action"
                        } else {
                            error.message ?: "IDN-1012: relay identity rotation failed"
                        }
                    updateState {
                        it.copy(
                            relay =
                                it.relay.copy(
                                    busy = false,
                                    registered = false,
                                    identityRotationPending = error is RelayIdentityRotationPendingException,
                                    message = message,
                                ),
                        )
                    }
                }
        }
    }

    fun createSession() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        val targetRelayId =
            current.relay.pcRelayDeviceId
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let(::RelayDeviceId)
                ?: profile?.relayDeviceId
        if (targetRelayId == null) {
            updateState {
                it.copy(relay = it.relay.copy(message = "Select a profile with a relay id or enter the PC relay id."))
            }
            return
        }
        val client = client
        if (client == null || !current.relay.registered) {
            updateState {
                it.copy(relay = it.relay.copy(message = "Register this phone with the relay before creating a session."))
            }
            return
        }
        updateState { it.copy(relay = it.relay.copy(busy = true, message = "Creating relay session to ${targetRelayId.value}...")) }
        scope.launch {
            runCatching {
                val session = client.createSession(targetRelayId)
                val profileId = profile?.id ?: DeviceProfileId("relay-${targetRelayId.value}")
                checkNotNull(remoteSessionCoordinator) { "SESSION_COORDINATOR_UNAVAILABLE" }
                    .created(session, profileId)
                session
            }.rethrowCancellation()
                .onSuccess { session ->
                    updateState {
                        it.copy(
                            relay =
                                it.relay.copy(
                                    busy = false,
                                    lastSessionId = session.sessionId.value,
                                    lastSessionTargetRelayDeviceId = targetRelayId.value,
                                    lastSessionApproved = false,
                                    approvedSessionIdsByRelayDevice = it.relay.approvedSessionIdsByRelayDevice - targetRelayId.value,
                                    message = "Relay session ${session.sessionId.value} created. Wait for PC approval before WebRTC signaling can use it.",
                                ),
                        )
                    }
                }.onFailure { error ->
                    updateState {
                        it.copy(relay = it.relay.copy(busy = false, message = error.message ?: "Could not create relay session"))
                    }
                }
        }
    }

    fun requestTurnCredentials() {
        val client = client
        if (client == null || !state.value.relay.registered) {
            updateState {
                it.copy(relay = it.relay.copy(message = "Register this phone with the relay before requesting TURN credentials."))
            }
            return
        }
        updateState { it.copy(relay = it.relay.copy(busy = true, message = "Requesting temporary TURN credentials...")) }
        scope.launch {
            runCatching { turnConfigProvider.request(client) }
                .rethrowCancellation()
                .onSuccess { config ->
                    val turnConfig = config.turnConfig
                    updateState {
                        it.copy(
                            relay =
                                it.relay.copy(
                                    busy = false,
                                    turnUrls = turnConfig?.urls.orEmpty(),
                                    turnCredentialRef = turnConfig?.credentialRef,
                                    turnCredentialsExpiresAtEpochMillis = turnConfig?.expiresAtEpochMillis,
                                    message = "TURN credentials stored securely. Native WebRTC adapters will consume this ICE config next.",
                                ),
                        )
                    }
                }.onFailure { error ->
                    updateState {
                        it.copy(relay = it.relay.copy(busy = false, message = error.message ?: "Could not request TURN credentials"))
                    }
                }
        }
    }

    private fun startEventMonitoring(client: KtorRelayClient) {
        relayEventsJob?.cancel()
        relayEventsJob =
            scope.launch {
                var attempt = 0
                while (true) {
                    val streamResult =
                        runCatching {
                            client.openDeviceEvents().incoming.collect { event ->
                                attempt = 0
                                onRelayDeviceEvent(event)
                            }
                        }
                    val error = streamResult.exceptionOrNull()
                    if (error is CancellationException) {
                        throw error
                    }
                    // A clean server-side close must also reconnect; otherwise the phone
                    // silently stops receiving approvals until it is re-registered by hand.
                    val disconnectReason = error?.message ?: "stream closed by server"
                    updateState {
                        it.copy(
                            relay =
                                it.relay.copy(
                                    registered = false,
                                    message = "Relay event stream lost: $disconnectReason. Reconnecting...",
                                ),
                        )
                    }
                    if (!relayReconnectionManager.shouldRetry(attempt, System.currentTimeMillis())) {
                        updateState {
                            it.copy(relay = it.relay.copy(message = "Relay reconnection attempts exhausted. Register again manually."))
                        }
                        return@launch
                    }
                    delay(relayReconnectionManager.nextDelayMillis(attempt))
                    attempt += 1
                    val relay = state.value.relay
                    runCatching {
                        client.connect()
                        client.registerDeviceV2(
                            identity = deviceIdentityStore.getOrCreate(),
                            displayName = relay.phoneName,
                            remoteAccessEnabled = relay.enabled,
                        )
                    }.rethrowCancellation()
                        .onSuccess { registration ->
                            updateState {
                                it.copy(
                                    relay =
                                        it.relay.copy(
                                            registered = true,
                                            phoneRelayDeviceId = registration.relayDeviceId.value,
                                            tokenExpiresAtEpochMillis = registration.authToken.expiresAtEpochMillis,
                                            message = "Relay event stream reconnected.",
                                        ),
                                )
                            }
                        }.onFailure { reconnectError ->
                            if (reconnectError is CancellationException) throw reconnectError
                            updateState {
                                it.copy(relay = it.relay.copy(message = "Relay reconnection failed: ${reconnectError.message}"))
                            }
                        }
                }
            }
    }

    private fun onRelayDeviceEvent(event: RelayDeviceEvent) {
        when (event) {
            is RelayDeviceEvent.SessionRequested -> {
                updateState {
                    it.copy(
                        relay =
                            it.relay.copy(
                                message = "Relay session requested by ${event.sourceRelayDeviceId.value}: ${event.sessionId.value}",
                            ),
                    )
                }
            }

            is RelayDeviceEvent.SessionApproved -> {
                scope.launch {
                    event.targetIdentity?.publicIdentity?.let { identity ->
                        relayE2eeCoordinator.rememberPeerIdentity(event.sessionId, identity)
                    }
                    runCatching {
                        val coordinator = checkNotNull(remoteSessionCoordinator) { "SESSION_COORDINATOR_UNAVAILABLE" }
                        awaitRelaySessionAdmission(coordinator, event.sessionId)
                        coordinator.approved(event.sessionId)
                        coordinator.establishingE2ee(event.sessionId)
                        relayE2eeCoordinator.establishProtocolMessageChannel(event.sessionId)
                        coordinator.openingSignaling(event.sessionId)
                    }.rethrowCancellation()
                        .onSuccess {
                            val profile = ensureRemoteRelayProfile(event.targetRelayDeviceId, event.targetIdentity?.displayName)
                            updateState {
                                it.copy(
                                    selectedProfileId = profile.id,
                                    relay =
                                        it.relay.copy(
                                            pcRelayDeviceId = event.targetRelayDeviceId.value,
                                            lastSessionId = event.sessionId.value,
                                            lastSessionTargetRelayDeviceId = event.targetRelayDeviceId.value,
                                            lastSessionApproved = true,
                                            approvedSessionIdsByRelayDevice =
                                                it.relay.approvedSessionIdsByRelayDevice +
                                                    (event.targetRelayDeviceId.value to event.sessionId.value),
                                            message = "PC approved relay session ${event.sessionId.value}; E2EE P-256 established with ${profile.displayName}.",
                                        ),
                                )
                            }
                        }.onFailure { error ->
                            runCatching { remoteSessionCoordinator?.failed(event.sessionId, "E2EE_ESTABLISHMENT_FAILED") }
                                .rethrowCancellation()
                            Log.e(
                                "AegisE2EE",
                                "relay_e2ee_failed code=${error.message ?: error::class.simpleName}",
                                error,
                            )
                            relayE2eeCoordinator.forgetPeerIdentity(event.sessionId)
                            updateState {
                                it.copy(
                                    relay =
                                        it.relay.copy(
                                            lastSessionApproved = false,
                                            message = "PC approval received, but E2EE failed closed: ${error.message}",
                                        ),
                                )
                            }
                        }
                }
            }

            is RelayDeviceEvent.SessionRejected -> {
                scope.launch { runCatching { remoteSessionCoordinator?.rejected(event.sessionId) }.rethrowCancellation() }
                relayE2eeCoordinator.forgetPeerIdentity(event.sessionId)
                scope.launch { relayE2eeCoordinator.clearSession(event.sessionId) }
                updateState {
                    it.copy(
                        relay =
                            it.relay.copy(
                                lastSessionId = null,
                                lastSessionTargetRelayDeviceId = event.targetRelayDeviceId.value,
                                lastSessionApproved = false,
                                approvedSessionIdsByRelayDevice =
                                    it.relay.approvedSessionIdsByRelayDevice.filterValues { sessionId ->
                                        sessionId != event.sessionId.value
                                    },
                                message = "PC rejected relay session ${event.sessionId.value}.",
                            ),
                    )
                }
            }
        }
    }

    private suspend fun awaitRelaySessionAdmission(
        coordinator: AndroidRemoteSessionCoordinator,
        sessionId: SessionId,
    ) {
        val admitted =
            withTimeoutOrNull(RELAY_SESSION_ADMISSION_WAIT_MILLIS) {
                while (coordinator.state(sessionId) == null) delay(RELAY_SESSION_ADMISSION_POLL_MILLIS)
                true
            }
        check(admitted == true) {
            "${dev.aegis.remote.core.model.AegisFailureCodes.SESSION_REQUEST_DEPENDENCY_FAILED}: approval arrived before local relay-session admission"
        }
    }

    suspend fun clearRelaySessionArtifacts(sessionId: SessionId) {
        relayE2eeCoordinator.clearSession(sessionId)
    }

    suspend fun takeRelayE2eeProtocolMessageChannel(sessionId: SessionId): ProtocolMessageChannel = relayE2eeCoordinator.takeProtocolMessageChannel(sessionId)

    suspend fun sendProtocolMessage(
        sessionId: SessionId,
        send: suspend (ProtocolDataChannelClient) -> Unit,
    ) {
        relayE2eeCoordinator.sendProtocolMessage(sessionId, send)
    }

    suspend fun shutdown() {
        relayEventsJob?.cancel()
        relayEventsJob = null
        relayE2eeCoordinator.shutdown()
        remoteSessionCoordinator = null
        client?.close()
        relayClientSlot.client = null
    }

    private suspend fun ensureRemoteRelayProfile(
        relayDeviceId: RelayDeviceId,
        displayName: String?,
    ): DeviceProfile {
        val existing =
            state.value.profiles.firstOrNull { it.relayDeviceId == relayDeviceId }
                ?: repository.getProfile(DeviceProfileId("relay-${relayDeviceId.value}"))
        if (existing != null) return existing

        val profile =
            DeviceProfile(
                id = DeviceProfileId("relay-${relayDeviceId.value}"),
                displayName = displayName?.takeIf { it.isNotBlank() } ?: "Relay ${relayDeviceId.value}",
                localHost = HostAddress("relay-only"),
                relayDeviceId = relayDeviceId,
                sshPort = 22,
                username = "",
                authMethod = AuthMethod.Password,
                permissions =
                    DevicePermissions(
                        terminal = false,
                        visual = true,
                        input = true,
                        sftp = false,
                        clipboard = true,
                        wakeOnLan = false,
                        remoteAccess = true,
                    ),
                remoteAccessEnabled = true,
            )
        repository.saveProfile(profile)
        return profile
    }
}

private const val RELAY_SESSION_ADMISSION_WAIT_MILLIS = 3_000L
private const val RELAY_SESSION_ADMISSION_POLL_MILLIS = 10L
