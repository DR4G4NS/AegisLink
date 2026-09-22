package dev.aegis.remote.android.home

import android.util.Log
import dev.aegis.remote.android.security.AndroidDeviceIdentityStore
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.security.CryptoCapabilityReport
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.security.P256SessionE2ee
import dev.aegis.remote.protocol.ProtocolDataChannelClient
import dev.aegis.remote.protocol.ProtocolMessageChannel
import dev.aegis.remote.relayclient.KtorRelayClient
import dev.aegis.remote.relayclient.openE2eeProtocolMessageChannel
import kotlinx.coroutines.CancellationException

/** Owns relay peer identities and the short-lived E2EE protocol channels for approved sessions. */
internal class AndroidRelayE2eeCoordinator(
    private val deviceIdentityStore: AndroidDeviceIdentityStore,
    private val relayClientProvider: () -> KtorRelayClient?,
) {
    private val peerIdentitiesBySession = mutableMapOf<String, dev.aegis.remote.core.model.DevicePublicIdentity>()
    private val channelsBySession = mutableMapOf<String, ProtocolMessageChannel>()

    fun rememberPeerIdentity(
        sessionId: SessionId,
        identity: dev.aegis.remote.core.model.DevicePublicIdentity,
    ) {
        peerIdentitiesBySession[sessionId.value] = identity
    }

    fun forgetPeerIdentity(sessionId: SessionId) {
        peerIdentitiesBySession.remove(sessionId.value)
    }

    suspend fun establishProtocolMessageChannel(sessionId: SessionId): ProtocolMessageChannel {
        channelsBySession[sessionId.value]?.let { return it }
        Log.i("AegisE2EE", "ephemeral_${P256SessionE2ee.ephemeralProviderDescription()}")
        val localIdentity = loadIdentity()
        Log.i("AegisE2EE", "identity_stage_ok")
        val capabilityReport = loadCapabilityReport()
        Log.i("AegisE2EE", "capability_stage_ok suites=${capabilityReport.supportedSuites.joinToString { it.id }}")
        val channel = openChannel(sessionId, localIdentity, capabilityReport)
        channelsBySession[sessionId.value] = channel
        return channel
    }

    private suspend fun loadIdentity() =
        runCatching { deviceIdentityStore.getOrCreate() }
            .rethrowCancellation()
            .getOrElse { error -> relayStageFailure("IDENTITY_STAGE_FAILED", error) }

    private suspend fun loadCapabilityReport() =
        runCatching { deviceIdentityStore.capabilityReport() }
            .rethrowCancellation()
            .getOrElse { error -> relayStageFailure("CAPABILITY_STAGE_FAILED", error) }

    private suspend fun openChannel(
        sessionId: SessionId,
        localIdentity: LocalDeviceIdentity,
        capabilityReport: CryptoCapabilityReport,
    ): ProtocolMessageChannel {
        val relayClient =
            relayClientProvider()
                ?: relayStageFailure(
                    "CHANNEL_HANDSHAKE_STAGE_FAILED",
                    IllegalStateException("Register this phone with the relay before opening E2EE."),
                )
        val peerIdentity =
            peerIdentitiesBySession[sessionId.value]
                ?: relayStageFailure(
                    "CHANNEL_HANDSHAKE_STAGE_FAILED",
                    IllegalStateException("TARGET_IDENTITY_REQUIRED_FOR_E2EE"),
                )
        return runCatching {
            relayClient.openE2eeProtocolMessageChannel(
                sessionId = sessionId,
                localIdentity = localIdentity,
                peerIdentity = peerIdentity,
                localIsSource = true,
                capabilityReport = capabilityReport,
            )
        }.rethrowCancellation()
            .getOrElse { error -> relayStageFailure("CHANNEL_HANDSHAKE_STAGE_FAILED", error) }
    }

    suspend fun clearSession(sessionId: SessionId) {
        peerIdentitiesBySession.remove(sessionId.value)
        channelsBySession.remove(sessionId.value)?.let { channel ->
            runCatching { channel.close() }.rethrowCancellation()
        }
    }

    suspend fun clearAllSessions() {
        channelsBySession.values.forEach { channel -> runCatching { channel.close() }.rethrowCancellation() }
        channelsBySession.clear()
        peerIdentitiesBySession.clear()
    }

    suspend fun takeProtocolMessageChannel(sessionId: SessionId): ProtocolMessageChannel {
        val channel = establishProtocolMessageChannel(sessionId)
        channelsBySession.remove(sessionId.value)
        return channel
    }

    suspend fun sendProtocolMessage(
        sessionId: SessionId,
        send: suspend (ProtocolDataChannelClient) -> Unit,
    ) {
        val channel = takeProtocolMessageChannel(sessionId)
        try {
            send(ProtocolDataChannelClient(sessionId, channel))
        } finally {
            runCatching { channel.close() }.rethrowCancellation()
        }
    }

    suspend fun shutdown() {
        clearAllSessions()
    }
}

private fun relayStageFailure(
    stage: String,
    error: Throwable,
): Nothing {
    if (error is CancellationException) throw error
    throw IllegalStateException("$stage: ${error.message}", error)
}
