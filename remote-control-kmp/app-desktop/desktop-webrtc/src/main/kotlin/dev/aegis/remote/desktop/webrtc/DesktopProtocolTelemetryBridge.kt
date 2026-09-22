package dev.aegis.remote.desktop.webrtc

import dev.aegis.remote.core.clipboard.ClipboardBridge
import dev.aegis.remote.core.clipboard.ClipboardSyncDecision
import dev.aegis.remote.core.clipboard.ClipboardSyncDirection
import dev.aegis.remote.core.clipboard.ClipboardSyncPolicy
import dev.aegis.remote.core.clipboard.ClipboardSyncPolicyEngine
import dev.aegis.remote.core.clipboard.ClipboardSyncRequest
import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.ClipboardPayload
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class DesktopProtocolTelemetryBridge(
    private val sessionId: SessionId,
    private val monitorProvider: MonitorProvider,
    private val videoSession: RemoteVideoSession,
    private val channel: ProtocolMessageChannel,
    private val scope: CoroutineScope,
    private val clipboardBridge: ClipboardBridge? = null,
    private val channelProvider: () -> ProtocolMessageChannel = { channel },
    private val clipboardPolicyEngine: ClipboardSyncPolicyEngine = ClipboardSyncPolicyEngine(),
    private val failureReporter: (AegisFailure) -> Unit = {},
) {
    private var statsJob: Job? = null
    private var clipboardJob: Job? = null

    suspend fun publishMonitorSnapshot() {
        channelProvider().send(
            ProtocolMessage.Monitors(
                sessionId = sessionId,
                monitors = monitorProvider.listMonitors(),
            ),
        )
    }

    fun startStatsForwarding(): Job {
        statsJob?.cancel()
        return scope
            .launch {
                videoSession.stats.collect { stats ->
                    channelProvider().send(ProtocolMessage.Stats(sessionId, stats))
                }
            }.also { statsJob = it }
    }

    fun stopStatsForwarding() {
        statsJob?.cancel()
        statsJob = null
    }

    /**
     * The caller creates this bridge only for an authorization that includes
     * clipboard access. Keeping that check at the session boundary prevents a
     * desktop clipboard value from being emitted on an unapproved channel.
     */
    @Suppress("TooGenericExceptionCaught")
    fun startClipboardForwarding(): Job? {
        val bridge = clipboardBridge ?: return null
        clipboardJob?.cancel()
        return scope
            .launch {
                bridge.changes.collect { payload ->
                    when (payload) {
                        is ClipboardPayload.Text -> {
                            if (!bridge.shouldForwardChange(payload)) return@collect
                            val decision =
                                clipboardPolicyEngine.evaluate(
                                    ClipboardSyncRequest(
                                        policy = ClipboardSyncPolicy.Automatic,
                                        direction = ClipboardSyncDirection.DesktopToAndroid,
                                        payload = payload,
                                        userConfirmed = false,
                                    ),
                                )
                            if (decision == ClipboardSyncDecision.Allowed) {
                                try {
                                    withTimeout(CLIPBOARD_SEND_TIMEOUT_MILLIS) {
                                        channelProvider().send(
                                            ProtocolMessage.Input(
                                                sessionId = sessionId,
                                                event = RemoteInputEvent.ClipboardSync(payload.value),
                                            ),
                                        )
                                    }
                                } catch (error: TimeoutCancellationException) {
                                    failureReporter(clipboardSendFailure(error, timedOut = true))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Throwable) {
                                    failureReporter(clipboardSendFailure(error, timedOut = false))
                                }
                            }
                        }
                    }
                }
            }.also { clipboardJob = it }
    }

    fun stopClipboardForwarding() {
        clipboardJob?.cancel()
        clipboardJob = null
    }

    private fun clipboardSendFailure(
        error: Throwable,
        timedOut: Boolean,
    ): AegisFailure =
        AegisFailure(
            code = if (timedOut) "CLP-1004" else "CLP-1006",
            component = "desktop-clipboard-telemetry",
            operation = "send-desktop-clipboard-text",
            stage = "aegis-clipboard-data-channel",
            category = if (timedOut) FailureCategory.TIMEOUT else FailureCategory.NETWORK,
            summary =
                if (timedOut) {
                    "The bounded clipboard DataChannel send timed out."
                } else {
                    "The bounded clipboard DataChannel send failed."
                },
            technicalCause = error.message?.take(300)?.takeIf(String::isNotBlank) ?: "The channel raised ${error::class.simpleName}",
            expected = "A reliable ordered aegis-clipboard channel accepts the bounded text message",
            actual = "The clipboard contents were not logged and the send did not complete",
            retryable = true,
            correlationId = sessionId.value,
            evidenceRef = null,
            nextAction = "Verify the dedicated clipboard DataChannel is open and retry only while the session remains authorized.",
            underlyingType = error::class.qualifiedName,
        )
}

private const val CLIPBOARD_SEND_TIMEOUT_MILLIS = 2_000L
