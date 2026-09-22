package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RelayDeviceEvent
import dev.aegis.remote.core.relay.RelayDeviceEventChannel
import dev.aegis.remote.core.relay.RelayDeviceRegistration
import dev.aegis.remote.core.relay.RelayRegistration
import dev.aegis.remote.core.relay.RelayTurnCredentials
import dev.aegis.remote.core.relay.RevokeRelayDeviceV2Response
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.TransactionalDeviceIdentityStore
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.relayclient.KtorRelayClient
import dev.aegis.remote.relayclient.RelayIdentityLifecycleCoordinator
import dev.aegis.remote.relayclient.RelayIdentityRotationResult
import dev.aegis.remote.relayclient.defaultKtorRelayHttpClient
import dev.aegis.remote.relayclient.openE2eeProtocolMessageChannel
import kotlinx.coroutines.flow.Flow

interface DesktopRelayConnector {
    val relayUrl: String
    val remoteAccessEnabled: Boolean

    suspend fun register(
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayRegistration

    suspend fun localPublicIdentity(): DevicePublicIdentity

    suspend fun openDeviceEvents(): Flow<RelayDeviceEvent>

    suspend fun approveSession(
        sessionId: SessionId,
        approved: Boolean,
    )

    suspend fun openProtocolMessageChannel(
        sessionId: SessionId,
        peerIdentity: DevicePublicIdentity,
    ): ProtocolMessageChannel

    suspend fun requestTurnCredentials(): RelayTurnCredentials

    suspend fun rotateIdentity(
        reason: KeyRotationReason,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayIdentityRotationResult = throw UnsupportedOperationException("IDN-1012: relay identity rotation is unavailable")

    suspend fun revokeIdentity(): RevokeRelayDeviceV2Response = throw UnsupportedOperationException("IDN-1013: relay identity revocation is unavailable")

    suspend fun close()
}

class KtorDesktopRelayConnector(
    override val relayUrl: String,
    /** Persisted locator used only to resume an interrupted signed rotation. */
    relayDeviceId: RelayDeviceId?,
    override val remoteAccessEnabled: Boolean,
    private val identityStore: TransactionalDeviceIdentityStore = DesktopDeviceIdentityStore(),
    private val relayClient: KtorRelayClient =
        KtorRelayClient(
            baseUrl = relayUrl,
            httpClient = defaultKtorRelayHttpClient(),
        ),
) : DesktopRelayConnector {
    private var eventChannel: RelayDeviceEventChannel? = null
    private var knownRelayDeviceId: RelayDeviceId? = relayDeviceId
    private val identityLifecycle = RelayIdentityLifecycleCoordinator(identityStore, relayClient)

    override suspend fun register(
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayRegistration {
        relayClient.connect()
        val pending = identityStore.pendingRotation()
        val registration =
            if (pending == null) {
                relayClient.registerDeviceV2(
                    identity = identityStore.getOrCreate(),
                    displayName = displayName,
                    remoteAccessEnabled = remoteAccessEnabled,
                )
            } else {
                if (pending.phase == dev.aegis.remote.core.security.IdentityRotationPhase.Prepared) {
                    val locator =
                        knownRelayDeviceId
                            ?: error("IDN-1015: persisted relay locator is required to resume identity rotation")
                    relayClient.prepareIdentityRotationRecoveryV2(pending.currentIdentity, locator)
                }
                identityLifecycle.rotate(pending.reason, displayName, remoteAccessEnabled).registration
            }
        knownRelayDeviceId = registration.relayDeviceId
        return registration
    }

    override suspend fun localPublicIdentity(): DevicePublicIdentity = identityStore.getOrCreate().publicIdentity

    override suspend fun openDeviceEvents(): Flow<RelayDeviceEvent> {
        val channel = relayClient.openDeviceEvents()
        eventChannel = channel
        return channel.incoming
    }

    override suspend fun approveSession(
        sessionId: SessionId,
        approved: Boolean,
    ) {
        relayClient.approveSession(sessionId, approved)
    }

    override suspend fun openProtocolMessageChannel(
        sessionId: SessionId,
        peerIdentity: DevicePublicIdentity,
    ): ProtocolMessageChannel =
        relayClient.openE2eeProtocolMessageChannel(
            sessionId = sessionId,
            localIdentity = identityStore.getOrCreate(),
            peerIdentity = peerIdentity,
            localIsSource = false,
            capabilityReport = identityStore.capabilityReport(),
        )

    override suspend fun requestTurnCredentials(): RelayTurnCredentials = relayClient.requestTurnCredentials()

    override suspend fun rotateIdentity(
        reason: KeyRotationReason,
        displayName: String,
        remoteAccessEnabled: Boolean,
    ): RelayIdentityRotationResult {
        eventChannel?.close()
        eventChannel = null
        return identityLifecycle.rotate(reason, displayName, remoteAccessEnabled).also {
            knownRelayDeviceId = it.registration.relayDeviceId
        }
    }

    override suspend fun revokeIdentity(): RevokeRelayDeviceV2Response {
        eventChannel?.close()
        eventChannel = null
        return identityLifecycle.revoke()
    }

    override suspend fun close() {
        eventChannel?.close()
        eventChannel = null
        relayClient.close()
    }

    companion object {
        fun fromEnvironment(): DesktopRelayConnector? {
            val url = System.getenv("AEGIS_RELAY_URL")?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val relayId =
                System
                    .getenv("AEGIS_RELAY_DEVICE_ID")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::RelayDeviceId)
            val remoteAccess = System.getenv("AEGIS_REMOTE_ACCESS")?.equals("true", ignoreCase = true) == true
            return KtorDesktopRelayConnector(
                relayUrl = url,
                relayDeviceId = relayId,
                remoteAccessEnabled = remoteAccess,
            )
        }
    }
}
