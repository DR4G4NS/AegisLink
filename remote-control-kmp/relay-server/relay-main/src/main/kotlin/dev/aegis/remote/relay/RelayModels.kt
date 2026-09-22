package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RelayDeviceIdentity
import dev.aegis.remote.core.relay.RelayDeviceRegistration
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.relay.RelaySession
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRelayDeviceRequest(
    val registration: RelayDeviceRegistration,
)

@Serializable
data class RegisterRelayDeviceResponse(
    val relayDeviceId: RelayDeviceId,
    val authToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class CreateRelaySessionRequest(
    val sourceRelayDeviceId: RelayDeviceId,
    val targetRelayDeviceId: RelayDeviceId,
)

@Serializable
data class RelaySessionResponse(
    val session: RelaySession,
    val sourceRelayDeviceId: RelayDeviceId,
    val targetRelayDeviceId: RelayDeviceId,
    val sourceIdentity: RelayDeviceIdentity? = null,
    val targetIdentity: RelayDeviceIdentity? = null,
)

@Serializable
data class RelaySessionApprovalRequest(
    val approved: Boolean,
)

@Serializable
data class RelayErrorResponse(
    val error: String,
    val code: String? = null,
)

@Serializable
data class RelayWebSocketEnvelope(
    val protocolVersion: Int = 1,
    val sessionId: SessionId,
    val senderRelayDeviceId: RelayDeviceId,
    val payloadKind: RelayPayloadKind = RelayPayloadKind.LEGACY_PLAINTEXT,
    val payloadJson: String,
)
