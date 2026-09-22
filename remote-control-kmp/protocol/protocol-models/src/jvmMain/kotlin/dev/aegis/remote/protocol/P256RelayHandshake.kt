package dev.aegis.remote.protocol

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.relay.RelayOpaqueChannel
import dev.aegis.remote.core.relay.RelayOpaqueFrame
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.security.AEGIS_E2EE_PROTOCOL_VERSION
import dev.aegis.remote.core.security.E2eeSession
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.security.P256SessionE2ee
import dev.aegis.remote.core.security.SessionHandshakeTranscript
import dev.aegis.remote.core.security.SignedSessionHandshake
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

private const val HANDSHAKE_TIMEOUT_MILLIS = 15_000L

data class RelayHandshakeResult(
    val session: E2eeSession,
    val signedHandshake: SignedSessionHandshake,
)

class P256RelayHandshake(
    private val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            classDiscriminator = "type"
        },
    private val timeoutMillis: Long = HANDSHAKE_TIMEOUT_MILLIS,
) {
    suspend fun establishAsSource(
        relay: RelayOpaqueChannel,
        sessionId: String,
        localIdentity: LocalDeviceIdentity,
        expectedTargetIdentity: DevicePublicIdentity,
        relayOrigin: String,
        sourceCapabilitiesHash: ByteArray,
        createdAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        generation: Long,
    ): RelayHandshakeResult =
        withTimeout(timeoutMillis) {
            require(sourceCapabilitiesHash.size == 32)
            val ephemeral =
                runCatching { P256SessionE2ee.generateEphemeralKeyPair() }
                    .getOrElse { throw IllegalStateException("SOURCE_EPHEMERAL_STAGE_FAILED: ${it.message}", it) }
            val init =
                RelayHandshakeMessage.Init(
                    sessionId = sessionId,
                    sourceIdentity = localIdentity.publicIdentity,
                    targetDeviceId = expectedTargetIdentity.deviceId.value,
                    sourceEphemeralKeySpki = ephemeral.publicKeySpki,
                    offeredSuiteIds = listOf(AEGIS_P256_AESGCM_V1.id),
                    sourceCapabilitiesHash = sourceCapabilitiesHash,
                    createdAtEpochMillis = createdAtEpochMillis,
                    expiresAtEpochMillis = expiresAtEpochMillis,
                    relayOrigin = relayOrigin,
                    generation = generation,
                )
            runCatching { relay.send(init.frame()) }
                .getOrElse { throw IllegalStateException("SOURCE_INIT_SEND_STAGE_FAILED: ${it.message}", it) }
            val response = relay.nextHandshakeMessage<RelayHandshakeMessage.Response>()
            require(response.sessionId == sessionId) { "WRONG_SESSION" }
            require(response.targetIdentity.matches(expectedTargetIdentity)) { "TARGET_IDENTITY_MISMATCH" }
            require(response.selectedSuiteId in init.offeredSuiteIds) { "CRYPTO_SUITE_DOWNGRADE" }
            require(response.selectedSuiteId == AEGIS_P256_AESGCM_V1.id) { "CRYPTO_SUITE_DOWNGRADE" }
            val transcript = init.transcript(response)
            P256SessionE2ee.verifySignature(response.targetIdentity, transcript, response.targetSignature)
            val sourceSignature = P256SessionE2ee.sign(localIdentity, transcript)
            relay.send(RelayHandshakeMessage.Finish(sessionId, sourceSignature).frame())
            val signed = SignedSessionHandshake(transcript, sourceSignature, response.targetSignature)
            P256SessionE2ee.verify(signed, createdAtEpochMillis, relayOrigin)
            RelayHandshakeResult(P256SessionE2ee.establish(signed, ephemeral, true), signed)
        }

    suspend fun establishAsTarget(
        relay: RelayOpaqueChannel,
        expectedSessionId: String,
        expectedSourceIdentity: DevicePublicIdentity,
        localIdentity: LocalDeviceIdentity,
        expectedRelayOrigin: String,
        targetCapabilitiesHash: ByteArray,
        nowEpochMillis: Long,
    ): RelayHandshakeResult =
        withTimeout(timeoutMillis) {
            require(targetCapabilitiesHash.size == 32)
            val init = relay.nextHandshakeMessage<RelayHandshakeMessage.Init>()
            require(init.sessionId == expectedSessionId) { "WRONG_SESSION" }
            require(init.sourceIdentity.matches(expectedSourceIdentity)) { "SOURCE_IDENTITY_MISMATCH" }
            require(init.targetDeviceId == localIdentity.publicIdentity.deviceId.value) { "WRONG_TARGET" }
            require(init.relayOrigin == expectedRelayOrigin) { "WRONG_RELAY_ORIGIN" }
            require(nowEpochMillis in init.createdAtEpochMillis..init.expiresAtEpochMillis) { "HANDSHAKE_EXPIRED_OR_NOT_YET_VALID" }
            require(AEGIS_P256_AESGCM_V1.id in init.offeredSuiteIds) { "NO_MUTUALLY_SUPPORTED_CRYPTO_SUITE" }
            val ephemeral =
                runCatching { P256SessionE2ee.generateEphemeralKeyPair() }
                    .getOrElse { throw IllegalStateException("TARGET_EPHEMERAL_STAGE_FAILED: ${it.message}", it) }
            val unsignedResponse =
                RelayHandshakeMessage.Response(
                    sessionId = init.sessionId,
                    targetIdentity = localIdentity.publicIdentity,
                    targetEphemeralKeySpki = ephemeral.publicKeySpki,
                    selectedSuiteId = AEGIS_P256_AESGCM_V1.id,
                    targetCapabilitiesHash = targetCapabilitiesHash,
                    targetSignature = byteArrayOf(),
                )
            val transcript = init.transcript(unsignedResponse)
            val targetSignature = P256SessionE2ee.sign(localIdentity, transcript)
            val response = unsignedResponse.copy(targetSignature = targetSignature)
            relay.send(response.frame())
            val finish = relay.nextHandshakeMessage<RelayHandshakeMessage.Finish>()
            require(finish.sessionId == init.sessionId) { "WRONG_SESSION" }
            P256SessionE2ee.verifySignature(init.sourceIdentity, transcript, finish.sourceSignature)
            val signed = SignedSessionHandshake(transcript, finish.sourceSignature, targetSignature)
            P256SessionE2ee.verify(signed, nowEpochMillis, expectedRelayOrigin)
            RelayHandshakeResult(P256SessionE2ee.establish(signed, ephemeral, false), signed)
        }

    private fun RelayHandshakeMessage.frame(): RelayOpaqueFrame =
        RelayOpaqueFrame(
            RelayPayloadKind.E2EE_HANDSHAKE,
            json.encodeToString(RelayHandshakeMessage.serializer(), this),
        )

    private suspend inline fun <reified T : RelayHandshakeMessage> RelayOpaqueChannel.nextHandshakeMessage(): T {
        val frame = incoming.first { it.kind == RelayPayloadKind.E2EE_HANDSHAKE }
        val decoded = json.decodeFromString(RelayHandshakeMessage.serializer(), frame.payloadJson)
        return decoded as? T ?: error("UNEXPECTED_HANDSHAKE_MESSAGE")
    }
}

@Serializable
sealed interface RelayHandshakeMessage {
    @Serializable
    data class Init(
        val sessionId: String,
        val sourceIdentity: DevicePublicIdentity,
        val targetDeviceId: String,
        val sourceEphemeralKeySpki: ByteArray,
        val offeredSuiteIds: List<String>,
        val sourceCapabilitiesHash: ByteArray,
        val createdAtEpochMillis: Long,
        val expiresAtEpochMillis: Long,
        val relayOrigin: String,
        val generation: Long,
    ) : RelayHandshakeMessage

    @Serializable
    data class Response(
        val sessionId: String,
        val targetIdentity: DevicePublicIdentity,
        val targetEphemeralKeySpki: ByteArray,
        val selectedSuiteId: String,
        val targetCapabilitiesHash: ByteArray,
        val targetSignature: ByteArray,
    ) : RelayHandshakeMessage

    @Serializable
    data class Finish(
        val sessionId: String,
        val sourceSignature: ByteArray,
    ) : RelayHandshakeMessage
}

private fun RelayHandshakeMessage.Init.transcript(response: RelayHandshakeMessage.Response): SessionHandshakeTranscript =
    SessionHandshakeTranscript(
        protocolVersion = AEGIS_E2EE_PROTOCOL_VERSION,
        sessionId = sessionId,
        sourceIdentity = sourceIdentity,
        targetIdentity = response.targetIdentity,
        sourceEphemeralKeySpki = sourceEphemeralKeySpki,
        targetEphemeralKeySpki = response.targetEphemeralKeySpki,
        createdAtEpochMillis = createdAtEpochMillis,
        expiresAtEpochMillis = expiresAtEpochMillis,
        relayOrigin = relayOrigin,
        capabilitiesHash =
            combinedCapabilitiesHash(
                sourceCapabilitiesHash,
                response.targetCapabilitiesHash,
                response.selectedSuiteId,
            ),
        generation = generation,
        cryptoSuiteId = response.selectedSuiteId,
    )

private fun combinedCapabilitiesHash(
    source: ByteArray,
    target: ByteArray,
    suiteId: String,
): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { output ->
        listOf(source, target, suiteId.encodeToByteArray()).forEach { value ->
            output.writeInt(value.size)
            output.write(value)
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
}
