package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AEGIS_SESSION_PROTOCOL_REV
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.SessionProtocolCapabilities
import dev.aegis.remote.core.model.SessionProtocolFeatures
import dev.aegis.remote.core.model.localSessionProtocolCapabilities

sealed interface ProtocolCapabilityHandshakeResult {
    data class Compatible(
        val negotiated: SessionProtocolCapabilities,
    ) : ProtocolCapabilityHandshakeResult

    data class Skew(
        val code: String = AegisFailureCodes.SESSION_PROTOCOL_SKEW,
        val reason: String,
    ) : ProtocolCapabilityHandshakeResult
}

class ProtocolCapabilityHandshake(
    private val local: SessionProtocolCapabilities = localSessionProtocolCapabilities(),
) {
    fun evaluate(remote: SessionProtocolCapabilities): ProtocolCapabilityHandshakeResult {
        if (remote.protocolRev != local.protocolRev) {
            return ProtocolCapabilityHandshakeResult.Skew(
                reason =
                    "Protocol revision mismatch: local=${local.protocolRev} remote=${remote.protocolRev}",
            )
        }
        val missing = SessionProtocolFeatures.REQUIRED - remote.featureSet()
        if (missing.isNotEmpty()) {
            return ProtocolCapabilityHandshakeResult.Skew(
                reason = "Remote peer is missing required session features: ${missing.sorted().joinToString()}",
            )
        }
        val negotiated =
            SessionProtocolCapabilities(
                protocolRev = AEGIS_SESSION_PROTOCOL_REV,
                features = (local.featureSet() intersect remote.featureSet()).toList().sorted(),
            )
        return ProtocolCapabilityHandshakeResult.Compatible(negotiated)
    }
}
