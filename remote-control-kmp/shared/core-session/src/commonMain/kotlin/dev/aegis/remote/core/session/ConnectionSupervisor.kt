package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.webrtc.VideoSessionState

enum class ConnectionFailureKind {
    Transient,
    FatalAuth,
    ProtocolSkew,
    Offline,
}

sealed interface ConnectionSupervisorDecision {
    data object Hold : ConnectionSupervisorDecision

    data class ProbeSession(
        val reason: String,
    ) : ConnectionSupervisorDecision

    data class RestartIce(
        val reason: String,
    ) : ConnectionSupervisorDecision

    data class RebuildSession(
        val reason: String,
        val delayMillis: Long,
        val preferSameRoute: Boolean,
    ) : ConnectionSupervisorDecision

    data class WaitOffline(
        val reason: String,
    ) : ConnectionSupervisorDecision

    data class FailClosed(
        val code: String,
        val reason: String,
    ) : ConnectionSupervisorDecision
}

data class ConnectionSupervisorInput(
    val videoState: VideoSessionState,
    val iceRestartInFlight: Boolean,
    val pingAlive: Boolean,
    val networkOnline: Boolean,
    val iceRestartGraceElapsed: Boolean,
    val backgroundDurationMillis: Long? = null,
    val failureKind: ConnectionFailureKind = ConnectionFailureKind.Transient,
    val attempt: Int = 0,
    val lastStableEpochMillis: Long? = null,
    val lastFailureEpochMillis: Long = 0L,
    val nowEpochMillis: Long,
    val currentRouteType: ConnectionRouteType,
    val lastSelectedPairType: ConnectionRouteType? = null,
    val selectedPairProvesCurrentTypeImpossible: Boolean = false,
)

/**
 * Single owner for ICE restart, session probe, and full rebuild. Advertised
 * routes are hints; this supervisor never treats a probe as a working path.
 */
class ConnectionSupervisor(
    private val reconnectionManager: ReconnectionManager =
        ExponentialBackoffReconnectionManager(
            baseDelayMillis = DEFAULT_BASE_DELAY_MILLIS,
            maxDelayMillis = DEFAULT_MAX_DELAY_MILLIS,
            maxAttempts = Int.MAX_VALUE,
            stableResetAfterMillis = DEFAULT_STABLE_RESET_MILLIS,
        ),
    private val backgroundReconnectThresholdMillis: Long = DEFAULT_BACKGROUND_RECONNECT_MILLIS,
) {
    suspend fun decide(input: ConnectionSupervisorInput): ConnectionSupervisorDecision {
        when (input.failureKind) {
            ConnectionFailureKind.FatalAuth -> {
                return ConnectionSupervisorDecision.FailClosed(
                    code = AegisFailureCodes.IDENTITY_AUTHENTICATION_FAILED,
                    reason = "Identity or authorization failed; retries are blocked until a wakeup",
                )
            }

            ConnectionFailureKind.ProtocolSkew -> {
                return ConnectionSupervisorDecision.FailClosed(
                    code = AegisFailureCodes.SESSION_PROTOCOL_SKEW,
                    reason = "Protocol capability handshake failed; retries are stopped",
                )
            }

            ConnectionFailureKind.Offline -> {
                return ConnectionSupervisorDecision.WaitOffline(
                    "Network is offline; retry attempts are not consumed",
                )
            }

            ConnectionFailureKind.Transient -> {
                Unit
            }
        }

        if (!input.networkOnline) {
            return ConnectionSupervisorDecision.WaitOffline(
                "Network is offline; retry attempts are not consumed",
            )
        }

        input.backgroundDurationMillis?.let { background ->
            return decideForegroundWakeup(input, background)
        }

        if (input.iceRestartInFlight) {
            return decideDuringIceRestart(input)
        }

        return when (val state = input.videoState) {
            VideoSessionState.Streaming -> {
                ConnectionSupervisorDecision.Hold
            }

            VideoSessionState.Reconnecting -> {
                if (input.iceRestartGraceElapsed) {
                    ConnectionSupervisorDecision.ProbeSession(
                        "ICE restart grace elapsed; probe before rebuilding",
                    )
                } else {
                    ConnectionSupervisorDecision.Hold
                }
            }

            is VideoSessionState.Failed -> {
                decideFailed(input, state.reason)
            }

            VideoSessionState.Idle,
            VideoSessionState.Negotiating,
            -> {
                ConnectionSupervisorDecision.Hold
            }
        }
    }

    fun nextRebuildDelayMillis(
        attempt: Int,
        lastFailureEpochMillis: Long,
        lastStableEpochMillis: Long?,
    ): Long {
        val effective = reconnectionManager.effectiveAttempt(attempt, lastFailureEpochMillis, lastStableEpochMillis)
        return reconnectionManager.nextDelayMillis(effective)
    }

    private fun decideForegroundWakeup(
        input: ConnectionSupervisorInput,
        backgroundDurationMillis: Long,
    ): ConnectionSupervisorDecision {
        val wakeup = classifyApplicationWakeup(backgroundDurationMillis, input.pingAlive, backgroundReconnectThresholdMillis)
        return when (wakeup) {
            ApplicationWakeup.Ignore -> {
                ConnectionSupervisorDecision.Hold
            }

            ApplicationWakeup.Probe -> {
                ConnectionSupervisorDecision.ProbeSession(
                    "Application became active after ${backgroundDurationMillis}ms; probing the live session",
                )
            }

            ApplicationWakeup.Reconnect -> {
                rebuild(input, "Application was backgrounded for ${backgroundDurationMillis}ms")
            }
        }
    }

    private fun decideDuringIceRestart(input: ConnectionSupervisorInput): ConnectionSupervisorDecision {
        if (input.videoState is VideoSessionState.Failed && input.pingAlive) {
            return ConnectionSupervisorDecision.Hold
        }
        if (input.iceRestartGraceElapsed) {
            return if (input.pingAlive) {
                ConnectionSupervisorDecision.Hold
            } else {
                ConnectionSupervisorDecision.ProbeSession(
                    "ICE restart grace elapsed without a fresh Ping; probe before rebuilding",
                )
            }
        }
        return ConnectionSupervisorDecision.Hold
    }

    private suspend fun decideFailed(
        input: ConnectionSupervisorInput,
        reason: String,
    ): ConnectionSupervisorDecision {
        if (input.pingAlive) {
            return ConnectionSupervisorDecision.Hold
        }
        if (!reconnectionManager.shouldRetry(
                reconnectionManager.effectiveAttempt(
                    input.attempt,
                    input.lastFailureEpochMillis,
                    input.lastStableEpochMillis,
                ),
                input.lastFailureEpochMillis,
            )
        ) {
            return ConnectionSupervisorDecision.FailClosed(
                code = AegisFailureCodes.WEBRTC_CONNECTION_FAILED,
                reason = "Visual reconnection attempts exhausted",
            )
        }
        return rebuild(input, reason)
    }

    private fun rebuild(
        input: ConnectionSupervisorInput,
        reason: String,
    ): ConnectionSupervisorDecision.RebuildSession {
        val preferSameRoute =
            !input.selectedPairProvesCurrentTypeImpossible &&
                (input.lastSelectedPairType == null || input.lastSelectedPairType == input.currentRouteType)
        return ConnectionSupervisorDecision.RebuildSession(
            reason = reason,
            delayMillis =
                nextRebuildDelayMillis(
                    input.attempt,
                    input.lastFailureEpochMillis,
                    input.lastStableEpochMillis,
                ),
            preferSameRoute = preferSameRoute,
        )
    }

    companion object {
        const val DEFAULT_BASE_DELAY_MILLIS = 3_000L
        const val DEFAULT_MAX_DELAY_MILLIS = 16_000L
        const val DEFAULT_STABLE_RESET_MILLIS = 30_000L
        const val DEFAULT_BACKGROUND_RECONNECT_MILLIS = 10_000L
        const val ICE_RESTART_GRACE_MILLIS = 12_000L
    }
}

enum class ApplicationWakeup {
    Ignore,
    Probe,
    Reconnect,
}

fun classifyApplicationWakeup(
    backgroundDurationMillis: Long,
    pingFresh: Boolean,
    reconnectThresholdMillis: Long = ConnectionSupervisor.DEFAULT_BACKGROUND_RECONNECT_MILLIS,
): ApplicationWakeup {
    if (backgroundDurationMillis < 0L) return ApplicationWakeup.Ignore
    if (pingFresh) return ApplicationWakeup.Probe
    return if (backgroundDurationMillis < reconnectThresholdMillis) {
        ApplicationWakeup.Probe
    } else {
        ApplicationWakeup.Reconnect
    }
}
