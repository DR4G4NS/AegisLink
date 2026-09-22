package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.session.RemoteSessionAdmission
import dev.aegis.remote.core.session.RemoteSessionPolicy
import dev.aegis.remote.core.session.RemoteSessionRegistry
import dev.aegis.remote.core.session.RemoteSessionRejectedException
import dev.aegis.remote.core.session.RemoteSessionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/** Owns the process-wide, replay-safe lifecycle for desktop relay sessions. */
internal class DesktopRemoteSessionCoordinator(
    localIdentity: DevicePublicIdentity,
    relayUrl: String,
    private val clock: () -> Long,
    maxActiveSessions: Int = 32,
) {
    private val mutex = Mutex()
    private val relayOrigin = canonicalRelayOrigin(relayUrl)
    private val registry =
        RemoteSessionRegistry(
            policy =
                RemoteSessionPolicy(
                    localDeviceId = localIdentity.deviceId,
                    expectedRelayOrigin = relayOrigin,
                    allowedCryptoSuiteIds = setOf(AEGIS_P256_AESGCM_V1.id),
                    minimumCryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
                    maxActiveSessions = maxActiveSessions,
                ),
            clock = clock,
        )
    private val localIdentity = localIdentity

    suspend fun admit(
        sessionId: SessionId,
        sourceIdentity: DevicePublicIdentity,
        expiresAtEpochMillis: Long,
    ) = mutex.withLock {
        if (expiresAtEpochMillis <= clock()) {
            throw RemoteSessionRejectedException("SESSION_EXPIRED")
        }
        registry.admit(
            RemoteSessionAdmission(
                sessionId = sessionId,
                profileId = DeviceProfileId(sourceIdentity.deviceId.value),
                sourceDeviceId = sourceIdentity.deviceId,
                targetDeviceId = localIdentity.deviceId,
                sourceKeyGeneration = sourceIdentity.keyGeneration,
                targetKeyGeneration = localIdentity.keyGeneration,
                cryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
                relayOrigin = relayOrigin,
                createdAtEpochMillis = clock(),
                expiresAtEpochMillis = expiresAtEpochMillis,
                generation = 1L,
            ),
        )
        advanceLocked(sessionId, RemoteSessionState.AwaitingApproval)
    }

    suspend fun approved(sessionId: SessionId) = advance(sessionId, RemoteSessionState.Approved)

    suspend fun establishingE2ee(sessionId: SessionId) = advance(sessionId, RemoteSessionState.EstablishingE2ee)

    suspend fun openingSignaling(sessionId: SessionId) = advance(sessionId, RemoteSessionState.OpeningSignaling)

    suspend fun negotiatingIce(sessionId: SessionId) = advance(sessionId, RemoteSessionState.NegotiatingIce)

    suspend fun connected(sessionId: SessionId) = advance(sessionId, RemoteSessionState.Connected)

    suspend fun reconnecting(sessionId: SessionId) = advance(sessionId, RemoteSessionState.Reconnecting)

    suspend fun rejected(sessionId: SessionId) =
        mutex.withLock {
            advanceLocked(sessionId, RemoteSessionState.Closing)
            advanceLocked(sessionId, RemoteSessionState.Closed)
            registry.removeTerminal(sessionId)
        }

    suspend fun failed(
        sessionId: SessionId,
        code: String,
    ) = mutex.withLock {
        val current = registry.get(sessionId) ?: return@withLock
        registry.transition(
            sessionId,
            current.admission.generation,
            current.lastEventSequence + 1u,
            RemoteSessionState.Failed(code),
        )
    }

    suspend fun closed(sessionId: SessionId) =
        mutex.withLock {
            val current = registry.get(sessionId) ?: return@withLock
            if (current.state !is RemoteSessionState.Closing) {
                advanceLocked(sessionId, RemoteSessionState.Closing)
            }
            advanceLocked(sessionId, RemoteSessionState.Closed)
            registry.removeTerminal(sessionId)
        }

    suspend fun state(sessionId: SessionId): RemoteSessionState? = registry.get(sessionId)?.state

    private suspend fun advance(
        sessionId: SessionId,
        state: RemoteSessionState,
    ) = mutex.withLock {
        advanceLocked(sessionId, state)
    }

    private suspend fun advanceLocked(
        sessionId: SessionId,
        state: RemoteSessionState,
    ) {
        val current = registry.get(sessionId) ?: error("SESSION_NOT_ADMITTED")
        registry.transition(
            sessionId,
            current.admission.generation,
            current.lastEventSequence + 1u,
            state,
        )
    }
}

internal fun canonicalRelayOrigin(relayUrl: String): String {
    val uri = URI(relayUrl)
    val scheme = uri.scheme?.lowercase() ?: error("RELAY_SCHEME_REQUIRED")
    val host = uri.host?.lowercase() ?: error("RELAY_HOST_REQUIRED")
    val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
    val port = if (uri.port < 0 || defaultPort) "" else ":${uri.port}"
    return "$scheme://$host$port"
}
