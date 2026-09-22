package dev.aegis.remote.android.wol

import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.WakeOnLanConfig
import dev.aegis.remote.core.wol.MagicPacketBuilder
import dev.aegis.remote.core.wol.WakeOnLanException
import dev.aegis.remote.core.wol.WakeOnLanResult
import dev.aegis.remote.core.wol.WakeOnLanSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class AndroidWakeOnLanSender(
    private val packetBuilder: MagicPacketBuilder = MagicPacketBuilder(),
) : WakeOnLanSender {
    override suspend fun send(config: WakeOnLanConfig): WakeOnLanResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val packet = packetBuilder.build(config.macAddress.value)
                val address = InetAddress.getByName(config.broadcastAddress)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(packet, packet.size, address, config.port))
                }
                WakeOnLanResult(
                    packetSent = true,
                    broadcastAddress = config.broadcastAddress,
                    port = config.port,
                    message = "Magic packet sent to ${config.broadcastAddress}:${config.port}. Wake-on-LAN may still depend on BIOS, OS, router, VPN, or LAN broadcast support.",
                )
            }.getOrElse { error -> throwWakeOnLanFailure(config, error) }
        }

    private fun throwWakeOnLanFailure(
        config: WakeOnLanConfig,
        error: Throwable,
    ): Nothing {
        if (error is CancellationException) throw error
        throw WakeOnLanException(
            failure =
                AegisFailure(
                    code = AegisFailureCodes.WAKE_ON_LAN_SEND_FAILED,
                    component = "android-wake-on-lan",
                    operation = "send-magic-packet",
                    stage = "udp-broadcast",
                    category = FailureCategory.NETWORK,
                    summary = "The Wake-on-LAN magic packet could not be sent.",
                    technicalCause =
                        error.message?.takeIf(String::isNotBlank)
                            ?: error::class.simpleName
                            ?: "CAUSE_UNCONFIRMED",
                    expected = "A 102-byte UDP magic packet is sent to ${config.broadcastAddress}:${config.port}",
                    actual = "UDP send failed before packet confirmation",
                    retryable = true,
                    correlationId = UUID.randomUUID().toString(),
                    nextAction = "Confirm Android is on the same LAN and that directed broadcast is allowed, then retry.",
                    underlyingType = error::class.qualifiedName,
                ),
            cause = error,
        )
    }
}
