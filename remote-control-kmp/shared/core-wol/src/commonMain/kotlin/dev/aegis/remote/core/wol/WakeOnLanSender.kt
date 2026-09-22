package dev.aegis.remote.core.wol

import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.WakeOnLanConfig

interface WakeOnLanSender {
    suspend fun send(config: WakeOnLanConfig): WakeOnLanResult
}

data class WakeOnLanResult(
    val packetSent: Boolean,
    val broadcastAddress: String,
    val port: Int,
    val message: String,
)

class WakeOnLanException(
    val failure: AegisFailure,
    cause: Throwable? = null,
) : IllegalStateException("${failure.code}: ${failure.summary}", cause)
