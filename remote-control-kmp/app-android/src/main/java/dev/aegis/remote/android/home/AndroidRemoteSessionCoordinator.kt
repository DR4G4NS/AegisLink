package dev.aegis.remote.android.home

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RelaySession
import dev.aegis.remote.core.session.RemoteSessionAdmission
import dev.aegis.remote.core.session.RemoteSessionPolicy
import dev.aegis.remote.core.session.RemoteSessionRegistry
import dev.aegis.remote.core.session.RemoteSessionRejectedException
import dev.aegis.remote.core.session.RemoteSessionState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

internal class AndroidRemoteSessionCoordinator(
    private val localIdentity: DevicePublicIdentity,
    relayUrl: String,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val relayOrigin = canonicalAndroidRelayOrigin(relayUrl)
    private val registry =
        RemoteSessionRegistry(
            RemoteSessionPolicy(
                localDeviceId = localIdentity.deviceId,
                expectedRelayOrigin = relayOrigin,
                allowedCryptoSuiteIds = setOf(AEGIS_P256_AESGCM_V1.id),
                minimumCryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
            ),
            clock,
        )

    suspend fun created(
        session: RelaySession,
        profileId: DeviceProfileId,
    ) = mutex.withLock {
        val targetIdentity =
            session.targetIdentity
                ?: throw RemoteSessionRejectedException("TARGET_IDENTITY_REQUIRED")
        registry.admit(
            RemoteSessionAdmission(
                sessionId = session.sessionId,
                profileId = profileId,
                sourceDeviceId = localIdentity.deviceId,
                targetDeviceId = targetIdentity.deviceId,
                sourceKeyGeneration = localIdentity.keyGeneration,
                targetKeyGeneration = targetIdentity.keyGeneration,
                cryptoSuiteId = AEGIS_P256_AESGCM_V1.id,
                relayOrigin = relayOrigin,
                createdAtEpochMillis = clock(),
                expiresAtEpochMillis = session.expiresAtEpochMillis,
                generation = 1L,
            ),
        )
        advanceLocked(session.sessionId, RemoteSessionState.AwaitingApproval)
    }

    suspend fun approved(id: SessionId) = advance(id, RemoteSessionState.Approved)

    suspend fun establishingE2ee(id: SessionId) = advance(id, RemoteSessionState.EstablishingE2ee)

    suspend fun openingSignaling(id: SessionId) = advance(id, RemoteSessionState.OpeningSignaling)

    suspend fun negotiatingIce(id: SessionId) = advance(id, RemoteSessionState.NegotiatingIce)

    suspend fun connected(id: SessionId) =
        mutex.withLock {
            val current = registry.get(id) ?: error("SESSION_NOT_ADMITTED")
            if (current.state != RemoteSessionState.Connected) advanceLocked(id, RemoteSessionState.Connected)
        }

    suspend fun reconnecting(id: SessionId) =
        mutex.withLock {
            val current = registry.get(id) ?: error("SESSION_NOT_ADMITTED")
            if (current.state != RemoteSessionState.Reconnecting) advanceLocked(id, RemoteSessionState.Reconnecting)
        }

    suspend fun beginIceRestart(id: SessionId) =
        mutex.withLock {
            val current = registry.get(id) ?: error("SESSION_NOT_ADMITTED")
            if (current.state != RemoteSessionState.Reconnecting) {
                advanceLocked(id, RemoteSessionState.Reconnecting)
            }
            advanceLocked(id, RemoteSessionState.NegotiatingIce)
        }

    suspend fun rejected(id: SessionId) = closed(id)

    suspend fun closed(id: SessionId) =
        mutex.withLock {
            val current = registry.get(id) ?: return@withLock
            when (current.state) {
                RemoteSessionState.Closed,
                RemoteSessionState.Expired,
                is RemoteSessionState.Failed,
                -> {
                    Unit
                }

                RemoteSessionState.Closing -> {
                    advanceLocked(id, RemoteSessionState.Closed)
                }

                else -> {
                    advanceLocked(id, RemoteSessionState.Closing)
                    advanceLocked(id, RemoteSessionState.Closed)
                }
            }
            registry.removeTerminal(id)
        }

    suspend fun failed(
        id: SessionId,
        code: String,
    ) = mutex.withLock {
        val current = registry.get(id) ?: return@withLock
        registry.transition(id, current.admission.generation, current.lastEventSequence + 1u, RemoteSessionState.Failed(code))
    }

    suspend fun state(id: SessionId): RemoteSessionState? = registry.get(id)?.state

    private suspend fun advance(
        id: SessionId,
        state: RemoteSessionState,
    ) = mutex.withLock { advanceLocked(id, state) }

    private suspend fun advanceLocked(
        id: SessionId,
        state: RemoteSessionState,
    ) {
        val current = registry.get(id) ?: error("SESSION_NOT_ADMITTED")
        registry.transition(id, current.admission.generation, current.lastEventSequence + 1u, state)
    }
}

internal fun canonicalAndroidRelayOrigin(url: String): String {
    val uri = URI(url)
    val scheme = uri.scheme?.lowercase() ?: error("RELAY_SCHEME_REQUIRED")
    val host = uri.host?.lowercase() ?: error("RELAY_HOST_REQUIRED")
    val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
    return "$scheme://$host${if (uri.port < 0 || defaultPort) "" else ":${uri.port}"}"
}
