package dev.aegis.remote.relayclient

import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.security.CryptoCapabilityReport
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.protocol.E2eeChannelRole
import dev.aegis.remote.protocol.E2eeProtocolMessageChannel
import dev.aegis.remote.protocol.P256RelayHandshake
import java.security.MessageDigest

suspend fun KtorRelayClient.openE2eeProtocolMessageChannel(
    sessionId: SessionId,
    localIdentity: LocalDeviceIdentity,
    peerIdentity: DevicePublicIdentity,
    localIsSource: Boolean,
    capabilityReport: CryptoCapabilityReport,
    nowEpochMillis: Long = System.currentTimeMillis(),
): E2eeProtocolMessageChannel {
    val opaque =
        runCatching { openOpaqueChannel(sessionId) }
            .getOrElse { throw IllegalStateException("OPAQUE_CHANNEL_STAGE_FAILED: ${it.message}", it) }
    val capabilitiesHash =
        runCatching { capabilityReportHash(capabilityReport) }
            .getOrElse {
                runCatching { opaque.close() }
                throw IllegalStateException("CAPABILITIES_HASH_STAGE_FAILED: ${it.message}", it)
            }
    val handshake = P256RelayHandshake()
    val result =
        try {
            if (localIsSource) {
                handshake.establishAsSource(
                    relay = opaque,
                    sessionId = sessionId.value,
                    localIdentity = localIdentity,
                    expectedTargetIdentity = peerIdentity,
                    relayOrigin = registeredRelayOrigin(),
                    sourceCapabilitiesHash = capabilitiesHash,
                    createdAtEpochMillis = nowEpochMillis - 120_000L,
                    expiresAtEpochMillis = nowEpochMillis + 300_000L,
                    generation = 1,
                )
            } else {
                handshake.establishAsTarget(
                    relay = opaque,
                    expectedSessionId = sessionId.value,
                    expectedSourceIdentity = peerIdentity,
                    localIdentity = localIdentity,
                    expectedRelayOrigin = registeredRelayOrigin(),
                    targetCapabilitiesHash = capabilitiesHash,
                    nowEpochMillis = nowEpochMillis,
                )
            }
        } catch (error: Throwable) {
            System.err.println(
                "aegis_e2ee_handshake_failed role=${if (localIsSource) "source" else "target"} " +
                    "session=${sessionId.value.take(16)} code=${error.message ?: error::class.simpleName}",
            )
            opaque.close()
            throw error
        }
    return E2eeProtocolMessageChannel(
        relay = opaque,
        session = result.session,
        role = if (localIsSource) E2eeChannelRole.SOURCE else E2eeChannelRole.TARGET,
    )
}

private fun capabilityReportHash(report: CryptoCapabilityReport): ByteArray {
    val canonical =
        report.supportedSuites
            .map { it.id }
            .distinct()
            .sorted()
            .joinToString(separator = "\n")
            .encodeToByteArray()
    return MessageDigest.getInstance("SHA-256").digest(canonical)
}
