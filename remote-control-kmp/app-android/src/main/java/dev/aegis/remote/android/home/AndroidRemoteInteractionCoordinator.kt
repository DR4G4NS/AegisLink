package dev.aegis.remote.android.home

import dev.aegis.remote.android.clipboard.AndroidClipboardBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardProtocolBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardSyncGate
import dev.aegis.remote.android.input.AndroidInputProtocolBridge
import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.input.KeyCode
import dev.aegis.remote.core.input.MouseButtonType
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class AndroidRemoteInteractionDependencies(
    val state: StateFlow<AndroidHomeUiState>,
    val scope: CoroutineScope,
    val inputProtocolBridge: AndroidInputProtocolBridge,
    val clipboardBridge: AndroidClipboardBridge,
    val clipboardProtocolBridge: AndroidClipboardProtocolBridge,
    val clipboardSyncGate: AndroidClipboardSyncGate,
    val visualSessionCoordinatorProvider: () -> RemoteSessionCoordinator,
    val sendRelayProtocolMessage: suspend (SessionId, suspend (ProtocolDataChannelClient) -> Unit) -> Unit,
    val updateState: ((AndroidHomeUiState) -> AndroidHomeUiState) -> Unit,
)

internal class AndroidRemoteInteractionCoordinator(
    private val dependencies: AndroidRemoteInteractionDependencies,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val inputProtocolBridge = dependencies.inputProtocolBridge
    private val clipboardBridge = dependencies.clipboardBridge
    private val clipboardProtocolBridge = dependencies.clipboardProtocolBridge
    private val clipboardSyncGate = dependencies.clipboardSyncGate
    private val visualSessionCoordinator get() = dependencies.visualSessionCoordinatorProvider()
    private val sendRelayProtocolMessage = dependencies.sendRelayProtocolMessage
    private val updateState = dependencies.updateState
    private var clipboardAutoSyncJob: Job? = null
    private var lastAutomaticClipboardText: String? = null
    private var suppressedIncomingClipboardText: String? = null

    fun markIncomingClipboardText(text: String) {
        suppressedIncomingClipboardText = text
    }

    fun prepareRemoteInputPayload(
        label: String,
        eventBuilder: () -> RemoteInputEvent,
    ) = prepareRemoteInputPayloads(label) { listOf(eventBuilder()) }

    fun prepareRemoteInputPayloads(
        label: String,
        eventBuilder: () -> List<RemoteInputEvent>,
    ) {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile?.permissions?.input != true) {
            updateState { it.copy(remoteInput = it.remoteInput.copy(message = "Remote input is not permitted for this profile.")) }
            return
        }
        val relaySessionId = current.relay.approvedSessionIdFor(profile.relayDeviceId)
        val sessionId = relaySessionId?.let(::SessionId) ?: visualSessionCoordinator.activeSessionId ?: SessionId("input-preview")
        val events =
            runCatching { eventBuilder() }
                .onFailure { error ->
                    updateState { it.copy(remoteInput = it.remoteInput.copy(message = error.message ?: "Invalid input event.")) }
                }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: return
        updateState {
            it.copy(
                remoteInput =
                    it.remoteInput.copy(
                        busy = true,
                        dataChannelPayload = null,
                        dataChannelSessionId = sessionId.value,
                        lastEventLabel = label,
                        message = "Preparing remote input protocol payload...",
                    ),
            )
        }
        scope.launch {
            runCatching { events.map { event -> inputProtocolBridge.buildInputPayload(sessionId, event) } }
                .rethrowCancellation()
                .onSuccess { payloads ->
                    val sendResult =
                        if (visualSessionCoordinator.hasActiveSessionFor(sessionId)) {
                            visualSessionCoordinator.sendNativeInput(sessionId, events)
                        } else if (relaySessionId == null) {
                            null
                        } else {
                            runCatching {
                                events.forEach { event ->
                                    sendRelayProtocolMessage(sessionId) { client ->
                                        client.sendInput(event)
                                    }
                                }
                            }.rethrowCancellation()
                        }
                    updateState {
                        it.copy(
                            remoteInput =
                                it.remoteInput.copy(
                                    busy = false,
                                    dataChannelPayload = payloads.joinToString(separator = "\n"),
                                    dataChannelSessionId = sessionId.value,
                                    lastEventLabel = label,
                                    message = inputDeliveryMessage(label, sessionId, relaySessionId, sendResult),
                                ),
                        )
                    }
                }.onFailure { error ->
                    updateState {
                        it.copy(
                            remoteInput = it.remoteInput.copy(busy = false, message = error.message ?: "Could not prepare input payload"),
                        )
                    }
                }
        }
    }

    fun readLocalClipboard() {
        updateState { it.copy(clipboard = it.clipboard.copy(busy = true, message = "Reading Android clipboard...")) }
        scope.launch {
            runCatching { clipboardBridge.read() }
                .rethrowCancellation()
                .onSuccess { payload ->
                    val text = (payload as? ClipboardPayload.Text)?.value.orEmpty()
                    updateState {
                        it.copy(
                            clipboard =
                                it.clipboard.copy(
                                    busy = false,
                                    text = text,
                                    message =
                                        if (text.isBlank()) {
                                            "Android clipboard has no readable text."
                                        } else {
                                            "Loaded Android clipboard text."
                                        },
                                ),
                        )
                    }
                }.onFailure { error ->
                    updateState {
                        it.copy(clipboard = it.clipboard.copy(busy = false, message = error.message ?: "Could not read Android clipboard"))
                    }
                }
        }
    }

    fun writeLocalClipboard() {
        val text = state.value.clipboard.text
        if (text.isBlank()) {
            updateState { it.copy(clipboard = it.clipboard.copy(message = "Enter text before writing clipboard.")) }
            return
        }
        updateState { it.copy(clipboard = it.clipboard.copy(busy = true, message = "Writing Android clipboard...")) }
        scope.launch {
            runCatching { clipboardBridge.write(ClipboardPayload.Text(text)) }
                .rethrowCancellation()
                .onSuccess {
                    updateState {
                        it.copy(
                            clipboard =
                                it.clipboard.copy(
                                    busy = false,
                                    message = "Text copied to Android clipboard. Use ClipboardSync to push it to the active PC session.",
                                ),
                        )
                    }
                }.onFailure { error ->
                    updateState {
                        it.copy(clipboard = it.clipboard.copy(busy = false, message = error.message ?: "Could not write Android clipboard"))
                    }
                }
        }
    }

    fun prepareClipboardDataChannelPayload() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile?.permissions?.clipboard != true) {
            updateState { it.copy(clipboard = it.clipboard.copy(message = "Clipboard remote access is not permitted for this profile.")) }
            return
        }
        val text = current.clipboard.text
        if (text.isBlank()) {
            updateState { it.copy(clipboard = it.clipboard.copy(message = "Enter text before preparing a DataChannel payload.")) }
            return
        }
        if (!allowManualClipboardSync(text)) return
        val relaySessionId = current.relay.approvedSessionIdFor(profile.relayDeviceId)
        val sessionId = relaySessionId?.let(::SessionId) ?: visualSessionCoordinator.activeSessionId ?: SessionId("clipboard-preview")
        updateState {
            it.copy(
                clipboard =
                    it.clipboard.copy(
                        busy = true,
                        dataChannelPayload = null,
                        dataChannelSessionId = sessionId.value,
                        message = "Preparing clipboard protocol payload...",
                    ),
            )
        }
        scope.launch {
            runCatching { clipboardProtocolBridge.buildClipboardSyncPayload(sessionId, text) }
                .rethrowCancellation()
                .onSuccess { payload ->
                    val sendResult =
                        if (visualSessionCoordinator.hasActiveSessionFor(sessionId)) {
                            visualSessionCoordinator.sendNativeProtocolMessage(
                                ProtocolMessage.Input(sessionId, RemoteInputEvent.ClipboardSync(text)),
                            )
                        } else if (relaySessionId == null) {
                            null
                        } else {
                            runCatching {
                                sendRelayProtocolMessage(sessionId) { client ->
                                    client.sendClipboardText(text)
                                }
                            }.rethrowCancellation()
                        }
                    updateState {
                        it.copy(
                            clipboard =
                                it.clipboard.copy(
                                    busy = false,
                                    dataChannelPayload = payload,
                                    dataChannelSessionId = sessionId.value,
                                    message = clipboardDeliveryMessage(sessionId, relaySessionId, sendResult),
                                ),
                        )
                    }
                }.onFailure { error ->
                    updateState {
                        it.copy(
                            clipboard = it.clipboard.copy(busy = false, message = error.message ?: "Could not prepare clipboard payload"),
                        )
                    }
                }
        }
    }

    fun setAutomaticClipboardSync(enabled: Boolean) {
        if (enabled) {
            startAutomaticClipboardSync()
        } else {
            stopAutomaticClipboardSync(resetState = false)
        }
    }

    private fun inputDeliveryMessage(
        label: String,
        sessionId: SessionId,
        relaySessionId: String?,
        sendResult: Result<Unit>?,
    ): String =
        when {
            sendResult?.isSuccess == true && visualSessionCoordinator.activeSessionId == sessionId -> {
                "Sent $label through native WebRTC DataChannel for session ${sessionId.value}."
            }

            sendResult?.isSuccess == true -> {
                "Sent $label through relay protocol channel for session ${sessionId.value}."
            }

            relaySessionId == null -> {
                "Prepared $label preview payload. Start a local or relay WebRTC session before sending it over a real DataChannel."
            }

            else -> {
                "Prepared $label payload for session ${sessionId.value}, but send failed: " +
                    (sendResult?.exceptionOrNull()?.message ?: "protocol channel is unavailable")
            }
        }

    private fun clipboardDeliveryMessage(
        sessionId: SessionId,
        relaySessionId: String?,
        sendResult: Result<Unit>?,
    ): String =
        when {
            sendResult?.isSuccess == true && visualSessionCoordinator.activeSessionId == sessionId -> {
                "Sent ClipboardSync through native WebRTC DataChannel for session ${sessionId.value}."
            }

            sendResult?.isSuccess == true -> {
                "Sent ClipboardSync through relay protocol channel for session ${sessionId.value}."
            }

            relaySessionId == null -> {
                "Prepared ClipboardSync preview payload. Start a local or relay WebRTC session before sending it over a real DataChannel."
            }

            else -> {
                "Prepared ClipboardSync payload for session ${sessionId.value}, but send failed: " +
                    (sendResult?.exceptionOrNull()?.message ?: "protocol channel is unavailable")
            }
        }

    private fun allowManualClipboardSync(text: String): Boolean =
        when (val decision = clipboardSyncGate.evaluateManualAndroidToDesktop(text)) {
            ClipboardSyncDecision.Allowed -> {
                true
            }

            is ClipboardSyncDecision.Blocked -> {
                updateState { it.copy(clipboard = it.clipboard.copy(message = "Clipboard sync blocked: ${decision.reason}")) }
                false
            }

            is ClipboardSyncDecision.RequiresConfirmation -> {
                updateState {
                    it.copy(clipboard = it.clipboard.copy(message = "Clipboard sync requires confirmation: ${decision.reason}"))
                }
                false
            }
        }

    private fun startAutomaticClipboardSync() {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (profile?.permissions?.clipboard != true) {
            updateState {
                it.copy(
                    clipboard =
                        it.clipboard.copy(
                            automaticSyncEnabled = false,
                            message = "Clipboard remote access is not permitted for this profile.",
                        ),
                )
            }
            return
        }
        if (clipboardAutoSyncJob?.isActive == true) {
            updateState {
                it.copy(
                    clipboard = it.clipboard.copy(automaticSyncEnabled = true, message = "Automatic clipboard sync is already enabled."),
                )
            }
            return
        }

        lastAutomaticClipboardText = null
        updateState {
            it.copy(
                clipboard =
                    it.clipboard.copy(
                        automaticSyncEnabled = true,
                        message = "Automatic Android-to-PC clipboard sync enabled for safe text.",
                    ),
            )
        }
        clipboardAutoSyncJob =
            scope.launch {
                clipboardBridge.changes.collect { payload ->
                    val text = (payload as? ClipboardPayload.Text)?.value.orEmpty()
                    if (text.isBlank()) return@collect
                    if (suppressedIncomingClipboardText == text) {
                        suppressedIncomingClipboardText = null
                        return@collect
                    }
                    if (lastAutomaticClipboardText == text) return@collect
                    sendAutomaticClipboardText(text)
                }
            }
    }

    fun stopAutomaticClipboardSync(resetState: Boolean) {
        clipboardAutoSyncJob?.cancel()
        clipboardAutoSyncJob = null
        lastAutomaticClipboardText = null
        suppressedIncomingClipboardText = null
        if (!resetState) {
            updateState {
                it.copy(
                    clipboard =
                        it.clipboard.copy(
                            automaticSyncEnabled = false,
                            busy = false,
                            message = "Automatic clipboard sync disabled.",
                        ),
                )
            }
        }
    }

    private suspend fun sendAutomaticClipboardText(text: String) {
        val current = state.value
        val profile = current.profiles.firstOrNull { it.id == current.selectedProfileId }
        if (!ensureAutomaticClipboardPermission(profile)) return
        if (!allowAutomaticClipboardSync(text)) return
        val relaySessionId = profile?.let { current.relay.approvedSessionIdFor(it.relayDeviceId) }
        val sessionId = relaySessionId?.let(::SessionId) ?: visualSessionCoordinator.activeSessionId
        if (sessionId == null) {
            updateState {
                it.copy(
                    clipboard =
                        it.clipboard.copy(
                            text = text,
                            busy = false,
                            dataChannelPayload = null,
                            dataChannelSessionId = null,
                            message = "Automatic clipboard sync is waiting for an active visual session or approved relay session.",
                        ),
                )
            }
            return
        }
        updateState {
            it.copy(
                clipboard =
                    it.clipboard.copy(
                        text = text,
                        busy = true,
                        dataChannelPayload = null,
                        dataChannelSessionId = sessionId.value,
                        message = "Sending Android clipboard change...",
                    ),
            )
        }
        runCatching { sendAutomaticClipboardPayload(sessionId, relaySessionId, text) }
            .rethrowCancellation()
            .onSuccess { (payload, sendResult) ->
                if (sendResult.isSuccess) lastAutomaticClipboardText = text
                updateState {
                    it.copy(
                        clipboard =
                            it.clipboard.copy(
                                busy = false,
                                dataChannelPayload = payload,
                                dataChannelSessionId = sessionId.value,
                                lastAutomaticSyncText = if (sendResult.isSuccess) text else it.clipboard.lastAutomaticSyncText,
                                message = automaticClipboardMessage(sessionId, sendResult),
                            ),
                    )
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                updateState { it.copy(clipboard = it.clipboard.copy(busy = false, message = error.message ?: "Automatic clipboard sync failed")) }
            }
    }

    private fun ensureAutomaticClipboardPermission(profile: DeviceProfile?): Boolean {
        if (profile?.permissions?.clipboard == true) return true
        updateState {
            it.copy(
                clipboard =
                    it.clipboard.copy(
                        automaticSyncEnabled = false,
                        busy = false,
                        message = "Automatic clipboard sync stopped because this profile does not permit clipboard access.",
                    ),
            )
        }
        clipboardAutoSyncJob?.cancel()
        clipboardAutoSyncJob = null
        return false
    }

    private fun allowAutomaticClipboardSync(text: String): Boolean =
        when (val decision = clipboardSyncGate.evaluateAutomaticAndroidToDesktop(text)) {
            ClipboardSyncDecision.Allowed -> {
                true
            }

            is ClipboardSyncDecision.Blocked -> {
                updateState {
                    it.copy(clipboard = it.clipboard.copy(text = text, busy = false, message = "Automatic clipboard sync blocked: ${decision.reason}"))
                }
                false
            }

            is ClipboardSyncDecision.RequiresConfirmation -> {
                updateState {
                    it.copy(
                        clipboard =
                            it.clipboard.copy(
                                text = text,
                                busy = false,
                                message = "Automatic clipboard sync requires confirmation: ${decision.reason}",
                            ),
                    )
                }
                false
            }
        }

    private suspend fun sendAutomaticClipboardPayload(
        sessionId: SessionId,
        relaySessionId: String?,
        text: String,
    ): Pair<String, Result<Unit>> {
        val payload = clipboardProtocolBridge.buildClipboardSyncPayload(sessionId, text)
        val sendResult =
            if (visualSessionCoordinator.hasActiveSessionFor(sessionId)) {
                visualSessionCoordinator.sendNativeProtocolMessage(
                    ProtocolMessage.Input(sessionId, RemoteInputEvent.ClipboardSync(text)),
                )
            } else if (relaySessionId == null) {
                Result.failure(IllegalStateException("No approved relay protocol channel is available."))
            } else {
                runCatching {
                    sendRelayProtocolMessage(sessionId) { client -> client.sendClipboardText(text) }
                }.rethrowCancellation()
            }
        return payload to sendResult
    }

    private fun automaticClipboardMessage(
        sessionId: SessionId,
        sendResult: Result<Unit>,
    ): String =
        when {
            sendResult.isSuccess && visualSessionCoordinator.activeSessionId == sessionId -> {
                "Automatically synced Android clipboard through native WebRTC DataChannel."
            }

            sendResult.isSuccess -> {
                "Automatically synced Android clipboard through relay protocol channel."
            }

            else -> {
                "Automatic clipboard sync failed: ${sendResult.exceptionOrNull()?.message ?: "protocol channel is unavailable"}"
            }
        }

    fun shutdown() {
        stopAutomaticClipboardSync(resetState = true)
    }
}
