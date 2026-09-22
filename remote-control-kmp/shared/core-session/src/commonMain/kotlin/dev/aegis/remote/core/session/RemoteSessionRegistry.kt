package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RemoteSessionState {
    data object Requested : RemoteSessionState

    data object AwaitingApproval : RemoteSessionState

    data object Approved : RemoteSessionState

    data object EstablishingE2ee : RemoteSessionState

    data object OpeningSignaling : RemoteSessionState

    data object NegotiatingIce : RemoteSessionState

    data object Connected : RemoteSessionState

    data object Reconnecting : RemoteSessionState

    data object Closing : RemoteSessionState

    data object Closed : RemoteSessionState

    data object Expired : RemoteSessionState

    data class Failed(
        val failure: AegisFailure,
    ) : RemoteSessionState {
        constructor(code: String) : this(remoteSessionFailure(code, LEGACY_SESSION_CORRELATION_ID))

        /** Stable-code compatibility accessor for callers that previously read `state.code`. */
        val code: String
            get() = failure.code
    }
}

data class RemoteSessionAdmission(
    val sessionId: SessionId,
    val profileId: DeviceProfileId,
    val sourceDeviceId: DeviceId,
    val targetDeviceId: DeviceId,
    val sourceKeyGeneration: Long,
    val targetKeyGeneration: Long,
    val cryptoSuiteId: String,
    val relayOrigin: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val generation: Long,
) {
    init {
        require(sessionId.value.isNotBlank())
        require(profileId.value.isNotBlank())
        require(sourceDeviceId.value.isNotBlank())
        require(targetDeviceId.value.isNotBlank())
        require(sourceDeviceId != targetDeviceId)
        require(sourceKeyGeneration > 0 && targetKeyGeneration > 0)
        require(cryptoSuiteId.isNotBlank())
        require(relayOrigin.isNotBlank())
        require(expiresAtEpochMillis > createdAtEpochMillis)
        require(generation > 0)
    }
}

data class RemoteSessionPolicy(
    val localDeviceId: DeviceId,
    val expectedRelayOrigin: String,
    val allowedCryptoSuiteIds: Set<String>,
    val minimumCryptoSuiteId: String? = null,
    val maxClockSkewMillis: Long = 120_000L,
    val maxActiveSessions: Int = 32,
) {
    init {
        require(localDeviceId.value.isNotBlank())
        require(expectedRelayOrigin.isNotBlank())
        require(allowedCryptoSuiteIds.isNotEmpty())
        require(minimumCryptoSuiteId == null || minimumCryptoSuiteId in allowedCryptoSuiteIds)
        require(maxClockSkewMillis >= 0)
        require(maxActiveSessions > 0)
    }
}

data class RegisteredRemoteSession(
    val admission: RemoteSessionAdmission,
    val state: RemoteSessionState,
    val lastEventSequence: ULong,
    val updatedAtEpochMillis: Long,
)

class RemoteSessionRejectedException(
    val code: String,
    val failure: AegisFailure,
) : IllegalStateException("${failure.code} ${failure.summary}") {
    constructor(code: String) : this(code, remoteSessionFailure(code, "unbound-session"))
}

/**
 * Process-wide authority for remote-session lifecycle. Every externally driven
 * mutation is generation- and sequence-bound so stale signaling cannot revive
 * or advance another session.
 */
class RemoteSessionRegistry(
    private val policy: RemoteSessionPolicy,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, RegisteredRemoteSession>()
    private val tombstones = linkedSetOf<String>()
    private val revokedDevices = linkedSetOf<String>()
    private val _snapshot = MutableStateFlow<Map<SessionId, RegisteredRemoteSession>>(emptyMap())
    val snapshot: StateFlow<Map<SessionId, RegisteredRemoteSession>> = _snapshot.asStateFlow()

    suspend fun admit(admission: RemoteSessionAdmission): RegisteredRemoteSession =
        mutex.withLock {
            val now = clock()
            validateAdmission(admission, now)
            if (admission.sessionId.value in sessions || admission.sessionId.value in tombstones) {
                reject(
                    legacyCode = "SESSION_REPLAY",
                    correlationId = admission.sessionId.value,
                    expected = "A session identifier that has never been admitted",
                    actual = "The identifier is active or retained as a replay tombstone",
                )
            }
            if (activeCount() >= policy.maxActiveSessions) {
                reject(
                    legacyCode = "SESSION_CAPACITY_EXCEEDED",
                    correlationId = admission.sessionId.value,
                    expected = "Fewer than ${policy.maxActiveSessions} active sessions",
                    actual = "${activeCount()} active sessions",
                )
            }
            val registered = RegisteredRemoteSession(admission, RemoteSessionState.Requested, 0u, now)
            sessions[admission.sessionId.value] = registered
            publish()
            registered
        }

    suspend fun transition(
        sessionId: SessionId,
        generation: Long,
        eventSequence: ULong,
        next: RemoteSessionState,
    ): RegisteredRemoteSession =
        mutex.withLock {
            val current =
                sessions[sessionId.value]
                    ?: reject(
                        legacyCode = "SESSION_NOT_FOUND",
                        correlationId = sessionId.value,
                        expected = "An active registry entry for the supplied session",
                        actual = "No active registry entry",
                    )
            val now = clock()
            if (current.admission.generation != generation) {
                reject(
                    legacyCode = "STALE_SESSION_GENERATION",
                    correlationId = sessionId.value,
                    expected = "Generation ${current.admission.generation}",
                    actual = "Generation $generation",
                )
            }
            if (eventSequence <= current.lastEventSequence) {
                reject(
                    legacyCode = "SESSION_EVENT_REPLAY",
                    correlationId = sessionId.value,
                    expected = "Event sequence greater than ${current.lastEventSequence}",
                    actual = "Event sequence $eventSequence",
                )
            }
            val boundNext = next.bindLegacyFailureTo(sessionId)
            if (now >= current.admission.expiresAtEpochMillis && next !is RemoteSessionState.Expired) {
                return@withLock replace(current, RemoteSessionState.Expired, eventSequence, now)
            }
            if (!isAllowed(current.state, boundNext)) {
                reject(
                    legacyCode = "INVALID_SESSION_TRANSITION",
                    correlationId = sessionId.value,
                    expected = "A transition permitted by the remote-session state machine",
                    actual = "${current.state::class.simpleName} -> ${next::class.simpleName}",
                )
            }
            replace(current, boundNext, eventSequence, now)
        }

    suspend fun expireDueSessions(): List<RegisteredRemoteSession> =
        mutex.withLock {
            val now = clock()
            sessions.values
                .filter { !it.state.terminal() && now >= it.admission.expiresAtEpochMillis }
                .map { current -> replace(current, RemoteSessionState.Expired, current.lastEventSequence + 1u, now) }
        }

    suspend fun revokeDevice(
        deviceId: DeviceId,
        code: String = "DEVICE_REVOKED",
    ): List<RegisteredRemoteSession> =
        mutex.withLock {
            require(code.isNotBlank())
            revokedDevices += deviceId.value
            val now = clock()
            sessions.values
                .filter { !it.state.terminal() && (it.admission.sourceDeviceId == deviceId || it.admission.targetDeviceId == deviceId) }
                .map { current ->
                    val failure =
                        remoteSessionFailure(
                            legacyCode = code,
                            correlationId = current.admission.sessionId.value,
                            expected = "Both session devices remain trusted and active",
                            actual = "A participating device was revoked",
                        )
                    replace(current, RemoteSessionState.Failed(failure), current.lastEventSequence + 1u, now)
                }
        }

    suspend fun removeTerminal(sessionId: SessionId): Boolean =
        mutex.withLock {
            val current = sessions[sessionId.value] ?: return@withLock false
            if (!current.state.terminal()) {
                reject(
                    legacyCode = "SESSION_NOT_TERMINAL",
                    correlationId = sessionId.value,
                    expected = "Closed, expired, or failed session state",
                    actual = current.state::class.simpleName ?: "Unknown session state",
                )
            }
            sessions.remove(sessionId.value)
            tombstones += sessionId.value
            while (tombstones.size > policy.maxActiveSessions * 4) tombstones.remove(tombstones.first())
            publish()
            true
        }

    suspend fun get(sessionId: SessionId): RegisteredRemoteSession? = mutex.withLock { sessions[sessionId.value] }

    private fun validateAdmission(
        admission: RemoteSessionAdmission,
        now: Long,
    ) {
        val correlationId = admission.sessionId.value
        if (admission.targetDeviceId != policy.localDeviceId && admission.sourceDeviceId != policy.localDeviceId) {
            reject(
                legacyCode = "WRONG_LOCAL_DEVICE",
                correlationId = correlationId,
                expected = "The local device participates in the admission",
                actual = "Neither endpoint matches the local device",
            )
        }
        if (admission.relayOrigin != policy.expectedRelayOrigin) {
            reject(
                legacyCode = "WRONG_RELAY_ORIGIN",
                correlationId = correlationId,
                expected = policy.expectedRelayOrigin,
                actual = admission.relayOrigin,
            )
        }
        if (admission.cryptoSuiteId !in policy.allowedCryptoSuiteIds) {
            reject(
                legacyCode = "CRYPTO_SUITE_NOT_ALLOWED",
                correlationId = correlationId,
                expected = "One of ${policy.allowedCryptoSuiteIds.sorted().joinToString()}",
                actual = admission.cryptoSuiteId,
            )
        }
        if (policy.minimumCryptoSuiteId != null && admission.cryptoSuiteId != policy.minimumCryptoSuiteId) {
            reject(
                legacyCode = "CRYPTO_SUITE_DOWNGRADE",
                correlationId = correlationId,
                expected = policy.minimumCryptoSuiteId,
                actual = admission.cryptoSuiteId,
            )
        }
        if (admission.sourceDeviceId.value in revokedDevices || admission.targetDeviceId.value in revokedDevices) {
            reject(
                legacyCode = "DEVICE_REVOKED",
                correlationId = correlationId,
                expected = "Both session devices remain trusted and active",
                actual = "A participating device is revoked",
            )
        }
        if (admission.expiresAtEpochMillis <= now) {
            reject(
                legacyCode = "SESSION_EXPIRED",
                correlationId = correlationId,
                expected = "Expiration later than $now",
                actual = "Expiration ${admission.expiresAtEpochMillis}",
            )
        }
        if (admission.createdAtEpochMillis > now + policy.maxClockSkewMillis) {
            reject(
                legacyCode = "SESSION_NOT_YET_VALID",
                correlationId = correlationId,
                expected = "Creation at or before ${now + policy.maxClockSkewMillis}",
                actual = "Creation ${admission.createdAtEpochMillis}",
            )
        }
    }

    private fun replace(
        current: RegisteredRemoteSession,
        next: RemoteSessionState,
        sequence: ULong,
        now: Long,
    ): RegisteredRemoteSession {
        val updated = current.copy(state = next, lastEventSequence = sequence, updatedAtEpochMillis = now)
        sessions[current.admission.sessionId.value] = updated
        publish()
        return updated
    }

    private fun activeCount(): Int = sessions.values.count { !it.state.terminal() }

    private fun publish() {
        _snapshot.value = sessions.values.associateBy { it.admission.sessionId }
    }

    private fun reject(
        legacyCode: String,
        correlationId: String,
        expected: String? = null,
        actual: String? = null,
    ): Nothing =
        throw RemoteSessionRejectedException(
            code = legacyCode,
            failure =
                remoteSessionFailure(
                    legacyCode = legacyCode,
                    correlationId = correlationId,
                    expected = expected,
                    actual = actual,
                ),
        )
}

private data class RemoteSessionFailureTemplate(
    val code: String,
    val operation: String,
    val stage: String,
    val category: FailureCategory,
    val summary: String,
    val technicalCause: String,
    val retryable: Boolean,
    val nextAction: String,
)

private const val LEGACY_SESSION_CORRELATION_ID = "legacy-session-state"

private fun RemoteSessionState.bindLegacyFailureTo(sessionId: SessionId): RemoteSessionState =
    if (this is RemoteSessionState.Failed && failure.correlationId == LEGACY_SESSION_CORRELATION_ID) {
        copy(failure = failure.copy(correlationId = sessionId.value))
    } else {
        this
    }

private fun remoteSessionFailure(
    legacyCode: String,
    correlationId: String,
    expected: String? = null,
    actual: String? = null,
): AegisFailure {
    require(legacyCode.isNotBlank())
    val template =
        when (legacyCode) {
            "SESSION_NOT_FOUND" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_NOT_FOUND,
                    "transition-session",
                    "lookup",
                    FailureCategory.INVALID_STATE,
                    "Remote session is not registered.",
                    "No active registry entry exists for the supplied session identifier.",
                    false,
                    "Discard the event and establish a new authorized session.",
                )
            }

            "SESSION_REPLAY" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_REPLAY,
                    "admit-session",
                    "replay-check",
                    FailureCategory.PROTOCOL,
                    "Remote session identifier was replayed.",
                    "The session identifier is already active or retained in the replay tombstones.",
                    false,
                    "Generate a fresh session identifier and repeat authorization.",
                )
            }

            "SESSION_CAPACITY_EXCEEDED" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_CAPACITY_EXCEEDED,
                    "admit-session",
                    "capacity-check",
                    FailureCategory.RESOURCE_EXHAUSTION,
                    "Remote session capacity was reached.",
                    "The registry is already at its configured active-session limit.",
                    true,
                    "Close an inactive session or retry after capacity becomes available.",
                )
            }

            "STALE_SESSION_GENERATION" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_STALE_GENERATION,
                    "transition-session",
                    "generation-check",
                    FailureCategory.PROTOCOL,
                    "A stale session generation was rejected.",
                    "The event generation does not match the admitted session generation.",
                    false,
                    "Discard stale signaling and synchronize with the current session.",
                )
            }

            "SESSION_EVENT_REPLAY" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_EVENT_REPLAY,
                    "transition-session",
                    "sequence-check",
                    FailureCategory.PROTOCOL,
                    "A replayed or reordered session event was rejected.",
                    "The event sequence did not advance beyond the last accepted sequence.",
                    false,
                    "Discard the event and continue only with a strictly newer sequence.",
                )
            }

            "INVALID_SESSION_TRANSITION" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_INVALID_TRANSITION,
                    "transition-session",
                    "state-machine",
                    FailureCategory.INVALID_STATE,
                    "Remote session transition is invalid.",
                    "The requested state transition is not allowed by the lifecycle state machine.",
                    false,
                    "Close the inconsistent session and begin a fresh authorization flow.",
                )
            }

            "SESSION_NOT_TERMINAL" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_NOT_TERMINAL,
                    "remove-session",
                    "terminal-state-check",
                    FailureCategory.INVALID_STATE,
                    "A non-terminal remote session cannot be removed.",
                    "The session is still active in the lifecycle state machine.",
                    true,
                    "Close or fail the session before removing its registry entry.",
                )
            }

            "WRONG_LOCAL_DEVICE" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_WRONG_LOCAL_DEVICE,
                    "admit-session",
                    "participant-binding",
                    FailureCategory.AUTHORIZATION,
                    "Session admission does not include the local device.",
                    "Neither endpoint in the admission matches the configured local identity.",
                    false,
                    "Reject the admission and verify the intended target identity.",
                )
            }

            "WRONG_RELAY_ORIGIN" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.RELAY_ORIGIN_MISMATCH,
                    "admit-session",
                    "relay-origin-binding",
                    FailureCategory.AUTHENTICATION,
                    "Session admission came from an unexpected relay origin.",
                    "The admission relay origin differs from the pinned policy origin.",
                    false,
                    "Reject the admission and reconnect only to the configured relay.",
                )
            }

            "CRYPTO_SUITE_NOT_ALLOWED" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.E2EE_CRYPTO_SUITE_NOT_ALLOWED,
                    "admit-session",
                    "crypto-policy",
                    FailureCategory.CRYPTOGRAPHY,
                    "Session cryptographic suite is not allowed.",
                    "The proposed suite is outside the locally allowed suite set.",
                    false,
                    "Reject the session and negotiate an explicitly supported suite.",
                )
            }

            "CRYPTO_SUITE_DOWNGRADE" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.E2EE_DOWNGRADE_DETECTED,
                    "admit-session",
                    "downgrade-check",
                    FailureCategory.CRYPTOGRAPHY,
                    "A cryptographic suite downgrade was rejected.",
                    "The proposed suite differs from the minimum suite pinned by policy.",
                    false,
                    "Abort the session and investigate peer capability or signaling tampering.",
                )
            }

            "DEVICE_REVOKED" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.IDENTITY_DEVICE_REVOKED,
                    "authorize-session",
                    "device-revocation-check",
                    FailureCategory.AUTHORIZATION,
                    "A revoked device cannot participate in a remote session.",
                    "A source or target identity is present in the local revocation set.",
                    false,
                    "Keep the session closed; re-pair the device only after explicit approval.",
                )
            }

            "SESSION_EXPIRED" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_EXPIRED,
                    "admit-session",
                    "expiry-check",
                    FailureCategory.AUTHORIZATION,
                    "Remote session admission has expired.",
                    "The admission expiration is not later than the local clock.",
                    false,
                    "Request a fresh, explicitly authorized session admission.",
                )
            }

            "SESSION_NOT_YET_VALID" -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_NOT_YET_VALID,
                    "admit-session",
                    "clock-skew-check",
                    FailureCategory.PROTOCOL,
                    "Remote session admission is not yet valid.",
                    "The admission creation time exceeds the configured clock-skew allowance.",
                    true,
                    "Synchronize device clocks and request a fresh admission.",
                )
            }

            else -> {
                RemoteSessionFailureTemplate(
                    AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED,
                    "manage-session",
                    "legacy-failure-adapter",
                    FailureCategory.CAUSE_UNCONFIRMED,
                    "Remote session failed with an unmapped legacy reason.",
                    "No canonical causal mapping exists for legacy reason $legacyCode.",
                    false,
                    "Inspect the correlation trace and add a stable causal mapping before retrying.",
                )
            }
        }

    return AegisFailure(
        code = template.code,
        component = "remote-session-registry",
        operation = template.operation,
        stage = template.stage,
        category = template.category,
        summary = template.summary,
        technicalCause = template.technicalCause,
        expected = expected,
        actual = actual,
        retryable = template.retryable,
        correlationId = correlationId,
        evidenceRef = null,
        nextAction = template.nextAction,
        underlyingType = null,
    )
}

private fun RemoteSessionState.terminal(): Boolean = this is RemoteSessionState.Closed || this is RemoteSessionState.Expired || this is RemoteSessionState.Failed

private fun isAllowed(
    from: RemoteSessionState,
    to: RemoteSessionState,
): Boolean =
    when (from) {
        RemoteSessionState.Requested -> {
            to is RemoteSessionState.AwaitingApproval || to is RemoteSessionState.Closing || to.terminal()
        }

        RemoteSessionState.AwaitingApproval -> {
            to is RemoteSessionState.Approved || to is RemoteSessionState.Closing || to.terminal()
        }

        RemoteSessionState.Approved -> {
            to is RemoteSessionState.EstablishingE2ee || to is RemoteSessionState.Closing || to.terminal()
        }

        RemoteSessionState.EstablishingE2ee -> {
            to is RemoteSessionState.OpeningSignaling || to is RemoteSessionState.Closing || to.terminal()
        }

        RemoteSessionState.OpeningSignaling -> {
            to is RemoteSessionState.NegotiatingIce || to is RemoteSessionState.Closing || to.terminal()
        }

        RemoteSessionState.NegotiatingIce -> {
            to is RemoteSessionState.Connected || to is RemoteSessionState.Reconnecting || to is RemoteSessionState.Closing || to.terminal()
        }

        RemoteSessionState.Connected -> {
            to is RemoteSessionState.Reconnecting || to is RemoteSessionState.Closing || to.terminal()
        }

        RemoteSessionState.Reconnecting -> {
            to is RemoteSessionState.EstablishingE2ee ||
                to is RemoteSessionState.NegotiatingIce ||
                to is RemoteSessionState.Closing ||
                to.terminal()
        }

        RemoteSessionState.Closing -> {
            to is RemoteSessionState.Closed || to is RemoteSessionState.Failed
        }

        RemoteSessionState.Closed, RemoteSessionState.Expired, is RemoteSessionState.Failed -> {
            false
        }
    }
