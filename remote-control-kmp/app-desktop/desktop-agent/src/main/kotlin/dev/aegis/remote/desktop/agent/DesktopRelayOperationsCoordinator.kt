package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.storage.RelayConfigRepository
import dev.aegis.remote.relayclient.RelayIdentityRotationPendingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class DesktopRelayOperationsDependencies(
    val state: StateFlow<DesktopAgentState>,
    val scope: CoroutineScope,
    val clock: () -> Long,
    val reconnectionPolicy: DesktopRelayReconnectPolicy,
    val connectorProvider: () -> DesktopRelayConnector?,
    val setConnector: (DesktopRelayConnector?) -> Unit,
    val relayConfigOwnedBySettings: () -> Boolean,
    val setRelayConfigOwnedBySettings: (Boolean) -> Unit,
    val remoteAccessEnabled: () -> Boolean,
    val setRemoteAccessEnabled: (Boolean) -> Unit,
    val relayConfigRepository: RelayConfigRepository,
    val relayConnectorFactory: (RelayConfig) -> DesktopRelayConnector,
    val relaySessionCoordinator: DesktopRelaySessionCoordinator,
    val updateState: ((DesktopAgentState) -> DesktopAgentState) -> Unit,
)

internal class DesktopRelayOperationsCoordinator(
    private val dependencies: DesktopRelayOperationsDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val clock = dependencies.clock
    private val reconnectionPolicy = dependencies.reconnectionPolicy
    private val connectorProvider = dependencies.connectorProvider
    private val setConnector = dependencies.setConnector
    private val relayConfigOwnedBySettings = dependencies.relayConfigOwnedBySettings
    private val setRelayConfigOwnedBySettings = dependencies.setRelayConfigOwnedBySettings
    private val remoteAccessEnabled = dependencies.remoteAccessEnabled
    private val setRemoteAccessState = dependencies.setRemoteAccessEnabled
    private val relayConfigRepository = dependencies.relayConfigRepository
    private val relayConnectorFactory = dependencies.relayConnectorFactory
    private val relaySessionCoordinator = dependencies.relaySessionCoordinator
    private val updateState = dependencies.updateState
    private var eventCoordinator: DesktopRelayCoordinator? = null

    fun remoteSessionCoordinator(): DesktopRemoteSessionCoordinator? = relaySessionCoordinator.remoteSessionCoordinator()

    fun stop() {
        eventCoordinator?.stop()
        eventCoordinator = null
    }

    private fun startEventMonitoring(connector: DesktopRelayConnector) {
        eventCoordinator?.stop()
        eventCoordinator =
            DesktopRelayCoordinator(
                scope = scope,
                connector = connector,
                clock = clock,
                reconnectionPolicy = reconnectionPolicy,
                remoteAccessEnabled = remoteAccessEnabled,
                displayName = ::desktopDisplayName,
                onEvent = relaySessionCoordinator::onRelayDeviceEvent,
                updateState = updateState,
            ).also { it.start() }
    }

    private fun stopEventMonitoring() {
        eventCoordinator?.stop()
        eventCoordinator = null
    }

    fun approveRelaySession(sessionIdValue: String) = relaySessionCoordinator.approveRelaySession(sessionIdValue)

    fun rejectRelaySession(sessionIdValue: String) = relaySessionCoordinator.rejectRelaySession(sessionIdValue)

    fun setRemoteAccessEnabled(enabled: Boolean) {
        setRemoteAccessState(enabled)
        val connector = connectorProvider()
        if (connector == null) {
            updateState {
                it
                    .copy(remoteAccessEnabled = false, pendingRelaySessions = emptyList())
                    .withLog(
                        clock(),
                        "warn",
                        "Cannot ${if (enabled) "enable" else "disable"} remote access because relay is not configured in Settings",
                        AgentLogEventCode.RelayConfigurationChanged,
                        mapOf("action" to "remote-access-unavailable"),
                    )
            }
            return
        }
        updateState {
            it
                .copy(remoteAccessEnabled = enabled, pendingRelaySessions = if (enabled) it.pendingRelaySessions else emptyList())
                .withLog(
                    clock(),
                    "info",
                    "Remote access ${if (enabled) "enabled" else "disabled"} locally; updating relay registration",
                    AgentLogEventCode.RelayConfigurationChanged,
                    mapOf("action" to "remote-access-changed", "enabled" to enabled.toString()),
                )
        }
        scope.launch {
            runCatching { persistActiveRelayConfigIfOwned() }
                .onFailure { error ->
                    updateState {
                        it.withLog(
                            clock(),
                            "error",
                            "Failed to persist remote-access setting: ${error.message}",
                            AgentLogEventCode.RelayConfigurationChanged,
                            mapOf("action" to "remote-access-persist-failed"),
                            error,
                        )
                    }
                }
            registerIfConfigured()
        }
    }

    fun configureRelay(
        relayUrl: String,
        relayDeviceId: String,
    ) {
        val normalizedUrl = relayUrl.trim().trimEnd('/')
        val normalizedDeviceId = relayDeviceId.trim()
        val validationError = validateRelayConfiguration(normalizedUrl, normalizedDeviceId)
        if (validationError != null) {
            updateState {
                it.copy(
                    relayConfigurationBusy = false,
                    relayConfigurationMessage = validationError.fallbackMessage,
                    relayConfigurationMessageCode = RelayConfigurationMessageCode.ValidationFailed,
                    relayConfigurationMessageContext = mapOf("reason" to validationError.reason.name),
                )
            }
            return
        }
        val config =
            RelayConfig(
                relayUrl = normalizedUrl,
                deviceId = normalizedDeviceId.takeIf { it.isNotBlank() }?.let(::RelayDeviceId),
                enabled = remoteAccessEnabled(),
            )
        updateState {
            it.copy(
                relayConfigurationBusy = true,
                relayConfigurationMessage = "Guardando configuración del relay…",
                relayConfigurationMessageCode = RelayConfigurationMessageCode.Saving,
                relayConfigurationMessageContext = emptyMap(),
            )
        }
        scope.launch {
            runCatching {
                relayConfigRepository.saveRelayConfig(config)
                stopEventMonitoring()
                connectorProvider()?.close()
                setConnector(relayConnectorFactory(config))
                setRelayConfigOwnedBySettings(true)
            }.onSuccess {
                updateState {
                    it
                        .copy(
                            relayConnected = false,
                            relayUrl = normalizedUrl,
                            relayDeviceId = config.deviceId?.value,
                            relayConfigurationBusy = false,
                            relayConfigurationMessage = "Configuración guardada. Conectando con el relay…",
                            relayConfigurationMessageCode = RelayConfigurationMessageCode.SavedConnecting,
                            relayConfigurationMessageContext = emptyMap(),
                            pendingRelaySessions = emptyList(),
                        ).withLog(
                            clock(),
                            "info",
                            "Relay configuration updated from desktop Settings",
                            AgentLogEventCode.RelayConfigurationChanged,
                            mapOf("action" to "updated", "relayUrl" to normalizedUrl),
                        )
                }
                registerIfConfigured()
            }.onFailure { error ->
                updateState {
                    it
                        .copy(
                            relayConfigurationBusy = false,
                            relayConfigurationMessage = "No se pudo guardar el relay: ${error.message ?: "error desconocido"}",
                            relayConfigurationMessageCode = RelayConfigurationMessageCode.SaveFailed,
                            relayConfigurationMessageContext = mapOf("errorType" to error.javaClass.simpleName),
                        ).withLog(
                            clock(),
                            "error",
                            "Failed to update relay configuration: ${error.message}",
                            AgentLogEventCode.RelayConfigurationChanged,
                            mapOf("action" to "update-failed"),
                            error,
                        )
                }
            }
        }
    }

    fun clearRelayConfiguration() {
        updateState {
            it.copy(
                relayConfigurationBusy = true,
                relayConfigurationMessage = "Quitando configuración del relay…",
                relayConfigurationMessageCode = RelayConfigurationMessageCode.Removing,
                relayConfigurationMessageContext = emptyMap(),
            )
        }
        scope.launch {
            runCatching {
                stopEventMonitoring()
                closeRelaySessionResourcesForIdentityChange()
                connectorProvider()?.takeIf { state.value.relayConnected }?.revokeIdentity()?.also {
                    updateState { current ->
                        current.withLog(
                            clock(),
                            "warn",
                            "Relay identity revoked before removing its configuration",
                            AgentLogEventCode.RelayIdentityChanged,
                            mapOf("action" to "revoked"),
                        )
                    }
                }
                relayConfigRepository.saveRelayConfig(RelayConfig(relayUrl = "", enabled = false))
                connectorProvider()?.close()
                setConnector(null)
                setRelayConfigOwnedBySettings(false)
                setRemoteAccessState(false)
            }.onSuccess {
                updateState {
                    it
                        .copy(
                            relayConnected = false,
                            relayUrl = null,
                            relayDeviceId = null,
                            relayConfigurationBusy = false,
                            relayConfigurationMessage = "Relay eliminado. Aegis permanece disponible en la red local.",
                            relayConfigurationMessageCode = RelayConfigurationMessageCode.Removed,
                            relayConfigurationMessageContext = emptyMap(),
                            remoteAccessEnabled = false,
                            pendingRelaySessions = emptyList(),
                        ).withLog(
                            clock(),
                            "info",
                            "Relay configuration removed from desktop Settings",
                            AgentLogEventCode.RelayConfigurationChanged,
                            mapOf("action" to "removed"),
                        )
                }
            }.onFailure { error ->
                updateState {
                    it.copy(
                        relayConfigurationBusy = false,
                        relayConfigurationMessage = "No se pudo quitar el relay: ${error.message ?: "error desconocido"}",
                        relayConfigurationMessageCode = RelayConfigurationMessageCode.RemoveFailed,
                        relayConfigurationMessageContext = mapOf("errorType" to error.javaClass.simpleName),
                    )
                }
            }
        }
    }

    @Suppress("LongMethod")
    fun rotateRelayIdentity() {
        val connector = connectorProvider()
        val resumingPreparedRotation = state.value.relayConfigurationMessageCode == RelayConfigurationMessageCode.IdentityRotationPending
        if (connector == null || (!state.value.relayConnected && !resumingPreparedRotation)) {
            updateState {
                it.copy(
                    relayConfigurationBusy = false,
                    relayConfigurationMessage = "IDN-1012: connect the relay before rotating its identity",
                    relayConfigurationMessageCode = RelayConfigurationMessageCode.IdentityRotationFailed,
                    relayConfigurationMessageContext = emptyMap(),
                )
            }
            return
        }
        if (state.value.relayConfigurationBusy) return
        stopEventMonitoring()
        updateState {
            it
                .copy(
                    relayConfigurationBusy = true,
                    relayConfigurationMessage = "Rotating relay identity…",
                    relayConfigurationMessageCode = RelayConfigurationMessageCode.IdentityRotating,
                    relayConfigurationMessageContext = emptyMap(),
                    pendingRelaySessions = emptyList(),
                ).withLog(
                    clock(),
                    "warn",
                    "Relay identity rotation started; active remote sessions are being closed",
                    AgentLogEventCode.RelayIdentityChanged,
                    mapOf("action" to "rotation-started"),
                )
        }
        scope.launch {
            runCatching {
                closeRelaySessionResourcesForIdentityChange()
                connector.rotateIdentity(
                    reason = KeyRotationReason.UserRequested,
                    displayName = desktopDisplayName(),
                    remoteAccessEnabled = remoteAccessEnabled(),
                )
            }.onSuccess { result ->
                relaySessionCoordinator.setRemoteSessionCoordinator(
                    DesktopRemoteSessionCoordinator(
                        localIdentity = result.identity.publicIdentity,
                        relayUrl = connector.relayUrl,
                        clock = clock,
                    ),
                )
                updateState {
                    it
                        .copy(
                            relayConnected = true,
                            relayDeviceId = result.registration.relayDeviceId.value,
                            relayConfigurationBusy = false,
                            relayConfigurationMessage = "Relay identity rotated and confirmed",
                            relayConfigurationMessageCode = RelayConfigurationMessageCode.IdentityRotated,
                            relayConfigurationMessageContext =
                                mapOf(
                                    "keyGeneration" to
                                        result.identity.publicIdentity.keyGeneration
                                            .toString(),
                                ),
                        ).withLog(
                            clock(),
                            "info",
                            "Relay identity rotation completed at key generation ${result.identity.publicIdentity.keyGeneration}",
                            AgentLogEventCode.RelayIdentityChanged,
                            mapOf(
                                "action" to "rotation-completed",
                                "keyGeneration" to
                                    result.identity.publicIdentity.keyGeneration
                                        .toString(),
                                "operationId" to result.operationId.take(12),
                            ),
                        )
                }
                runCatching { persistActiveRelayConfigIfOwned() }
                    .onFailure { error ->
                        updateState {
                            it.withLog(
                                clock(),
                                "error",
                                "Failed to persist relay configuration after identity rotation: ${error.message}",
                                AgentLogEventCode.RelayConfigurationChanged,
                                mapOf("action" to "device-id-persist-failed"),
                                error,
                            )
                        }
                    }
                startEventMonitoring(connector)
            }.onFailure { error ->
                val pending = error is RelayIdentityRotationPendingException
                updateState {
                    it
                        .copy(
                            relayConnected = false,
                            relayConfigurationBusy = false,
                            relayConfigurationMessage = error.message ?: "IDN-1012: relay identity rotation failed",
                            relayConfigurationMessageCode =
                                if (pending) {
                                    RelayConfigurationMessageCode.IdentityRotationPending
                                } else {
                                    RelayConfigurationMessageCode.IdentityRotationFailed
                                },
                            relayConfigurationMessageContext =
                                (error as? RelayIdentityRotationPendingException)
                                    ?.let { pendingError -> mapOf("operationId" to pendingError.operationId.take(12)) }
                                    .orEmpty(),
                        ).withLog(
                            clock(),
                            "error",
                            error.message ?: "Relay identity rotation failed",
                            AgentLogEventCode.RelayIdentityChanged,
                            mapOf("action" to if (pending) "rotation-pending" else "rotation-failed"),
                            error,
                        )
                }
            }
        }
    }

    private suspend fun closeRelaySessionResourcesForIdentityChange() {
        relaySessionCoordinator.closeRelaySessionResourcesForIdentityChange()
    }

    private suspend fun persistActiveRelayConfigIfOwned() {
        if (!relayConfigOwnedBySettings()) return
        val connector = connectorProvider() ?: return
        relayConfigRepository.saveRelayConfig(
            RelayConfig(
                relayUrl = connector.relayUrl,
                deviceId =
                    state.value.relayDeviceId
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::RelayDeviceId),
                enabled = remoteAccessEnabled(),
            ),
        )
    }

    suspend fun registerIfConfigured() {
        val connector = connectorProvider()
        if (connector == null) {
            updateState {
                it
                    .copy(
                        relayConnected = false,
                        remoteAccessEnabled = false,
                        relayConfigurationMessage = "Configura un relay en Ajustes para habilitar el acceso remoto",
                        relayConfigurationMessageCode = RelayConfigurationMessageCode.NotConfigured,
                        relayConfigurationMessageContext = emptyMap(),
                    ).withLog(
                        clock(),
                        "info",
                        "Relay disabled; configure it in desktop Settings to enable outbound registration",
                        AgentLogEventCode.RelayConnectionChanged,
                        mapOf("action" to "not-configured"),
                    )
            }
            return
        }
        runCatching {
            connector.register(
                displayName = desktopDisplayName(),
                remoteAccessEnabled = remoteAccessEnabled(),
            )
        }.onSuccess { registration ->
            relaySessionCoordinator.setRemoteSessionCoordinator(
                DesktopRemoteSessionCoordinator(
                    localIdentity = connector.localPublicIdentity(),
                    relayUrl = connector.relayUrl,
                    clock = clock,
                ),
            )
            updateState {
                it
                    .copy(
                        relayConnected = true,
                        relayUrl = connector.relayUrl,
                        relayDeviceId = registration.relayDeviceId.value,
                        remoteAccessEnabled = remoteAccessEnabled(),
                        relayConfigurationBusy = false,
                        relayConfigurationMessage = "Relay conectado",
                        relayConfigurationMessageCode = RelayConfigurationMessageCode.Connected,
                        relayConfigurationMessageContext = emptyMap(),
                    ).withLog(
                        clock(),
                        "info",
                        "Registered with relay as ${registration.relayDeviceId.value}",
                        AgentLogEventCode.RelayConnectionChanged,
                        mapOf("action" to "registered", "relayDeviceId" to registration.relayDeviceId.value),
                    )
            }
            if (relayConfigOwnedBySettings()) {
                runCatching {
                    relayConfigRepository.saveRelayConfig(
                        RelayConfig(
                            relayUrl = connector.relayUrl,
                            deviceId = registration.relayDeviceId,
                            enabled = remoteAccessEnabled(),
                        ),
                    )
                }.onFailure { error ->
                    updateState {
                        it.withLog(
                            clock(),
                            "error",
                            "Failed to persist registered relay id: ${error.message}",
                            AgentLogEventCode.RelayConfigurationChanged,
                            mapOf("action" to "device-id-persist-failed"),
                            error,
                        )
                    }
                }
            }
            startEventMonitoring(connector)
        }.onFailure { error ->
            val pendingRotation = error is RelayIdentityRotationPendingException
            updateState {
                it
                    .copy(
                        relayConnected = false,
                        relayUrl = connector.relayUrl,
                        remoteAccessEnabled = remoteAccessEnabled(),
                        relayConfigurationBusy = false,
                        relayConfigurationMessage = "No se pudo conectar con el relay: ${error.message ?: "error desconocido"}",
                        relayConfigurationMessageCode =
                            if (pendingRotation) {
                                RelayConfigurationMessageCode.IdentityRotationPending
                            } else {
                                RelayConfigurationMessageCode.ConnectionFailed
                            },
                        relayConfigurationMessageContext =
                            if (pendingRotation) {
                                mapOf(
                                    "operationId" to
                                        (error as RelayIdentityRotationPendingException)
                                            .operationId
                                            .take(12),
                                )
                            } else {
                                mapOf("errorType" to error.javaClass.simpleName)
                            },
                    ).withLog(
                        clock(),
                        "error",
                        "Relay registration failed: ${error.message}",
                        AgentLogEventCode.RelayConnectionChanged,
                        mapOf("action" to "registration-failed", "relayUrl" to connector.relayUrl),
                        error,
                    )
            }
        }
    }
}
