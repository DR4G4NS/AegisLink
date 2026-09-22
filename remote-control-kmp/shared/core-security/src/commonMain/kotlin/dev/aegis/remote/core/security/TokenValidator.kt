package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.PairingToken

class PairingTokenValidator {
    fun isUsable(
        token: PairingToken,
        nowEpochMillis: Long,
    ): Boolean =
        token.publicPairingId.isNotBlank() &&
            token.nonce.length >= 16 &&
            token.issuedAtEpochMillis <= nowEpochMillis &&
            token.expiresAtEpochMillis > nowEpochMillis
}
