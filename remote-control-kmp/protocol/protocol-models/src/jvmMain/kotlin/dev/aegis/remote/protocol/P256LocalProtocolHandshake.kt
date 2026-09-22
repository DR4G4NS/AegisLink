package dev.aegis.remote.protocol

import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.relay.RelayOpaqueChannel
import dev.aegis.remote.core.relay.RelayOpaqueFrame
import dev.aegis.remote.core.security.LocalDeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Applies the existing signed P-256 handshake and AEAD envelope channel to a
 * LAN text transport. TLS remains transport protection; no protocol JSON is
 * emitted until this adapter returns successfully.
 */
suspend fun openLanE2eeProtocolMessageChannel(
    transport: ProtocolTextTransport,
    sessionId: String,
    localIdentity: LocalDeviceIdentity,
    expectedPeerIdentity: DevicePublicIdentity,
    localIsSource: Boolean,
    nowEpochMillis: Long = System.currentTimeMillis(),
): E2eeProtocolMessageChannel {
    val opaque = LocalProtocolOpaqueChannel(transport)
    val handshake = P256RelayHandshake()
    val capabilitiesHash = MessageDigest.getInstance("SHA-256").digest(AEGIS_LAN_CAPABILITIES.encodeToByteArray())
    val result =
        try {
            if (localIsSource) {
                handshake.establishAsSource(
                    relay = opaque,
                    sessionId = sessionId,
                    localIdentity = localIdentity,
                    expectedTargetIdentity = expectedPeerIdentity,
                    relayOrigin = AEGIS_LAN_ORIGIN,
                    sourceCapabilitiesHash = capabilitiesHash,
                    createdAtEpochMillis = nowEpochMillis - LAN_HANDSHAKE_CLOCK_SKEW_MILLIS,
                    expiresAtEpochMillis = nowEpochMillis + LAN_HANDSHAKE_TTL_MILLIS,
                    generation = 1,
                )
            } else {
                handshake.establishAsTarget(
                    relay = opaque,
                    expectedSessionId = sessionId,
                    expectedSourceIdentity = expectedPeerIdentity,
                    localIdentity = localIdentity,
                    expectedRelayOrigin = AEGIS_LAN_ORIGIN,
                    targetCapabilitiesHash = capabilitiesHash,
                    nowEpochMillis = nowEpochMillis,
                )
            }
        } catch (error: Throwable) {
            runCatching { opaque.close() }
            throw IllegalStateException("LAN_E2EE_HANDSHAKE_FAILED: ${error.message ?: error::class.simpleName}", error)
        }
    return E2eeProtocolMessageChannel(
        relay = opaque,
        session = result.session,
        role = if (localIsSource) E2eeChannelRole.SOURCE else E2eeChannelRole.TARGET,
        nowEpochMillis = { nowEpochMillis },
    )
}

private class LocalProtocolOpaqueChannel(
    private val transport: ProtocolTextTransport,
    private val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        },
) : RelayOpaqueChannel {
    private val frames = Channel<RelayOpaqueFrame>(LOCAL_E2EE_FRAME_BUFFER_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val incoming: Flow<RelayOpaqueFrame> = frames.receiveAsFlow()

    init {
        scope.launch {
            runCatching {
                transport.incomingText.collect { payload ->
                    require(payload.isProtocolTextPayloadWithinLimit()) { "LAN_E2EE_FRAME_TOO_LARGE" }
                    frames.send(json.decodeFromString(RelayOpaqueFrame.serializer(), payload))
                }
            }.onFailure { frames.close(it) }
            frames.close()
        }
    }

    override suspend fun send(frame: RelayOpaqueFrame) {
        val payload = json.encodeToString(RelayOpaqueFrame.serializer(), frame)
        require(payload.isProtocolTextPayloadWithinLimit()) { "LAN_E2EE_FRAME_TOO_LARGE" }
        transport.sendText(payload)
    }

    override suspend fun close() {
        scope.cancel()
        frames.close()
        transport.close()
    }
}

private const val LOCAL_E2EE_FRAME_BUFFER_CAPACITY = 64

private const val AEGIS_LAN_ORIGIN = "aegis://lan/local-protocol/v1"
private const val AEGIS_LAN_CAPABILITIES = "AEGIS_P256_AESGCM_V1"
private const val LAN_HANDSHAKE_CLOCK_SKEW_MILLIS = 120_000L
private const val LAN_HANDSHAKE_TTL_MILLIS = 300_000L
