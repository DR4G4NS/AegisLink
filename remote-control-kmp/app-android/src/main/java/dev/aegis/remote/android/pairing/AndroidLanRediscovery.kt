package dev.aegis.remote.android.pairing

import android.content.Context
import android.net.wifi.WifiManager
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.stableAdvertisedEndpoints
import dev.aegis.remote.core.pairing.LanAnnouncePayload
import dev.aegis.remote.core.pairing.LanRediscoveryDecision
import dev.aegis.remote.core.pairing.LanRediscoveryPolicy
import dev.aegis.remote.core.pairing.LanRegisterRequest
import dev.aegis.remote.core.pairing.LanRegisterResponse
import dev.aegis.remote.core.pairing.marksHostUnlinked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

sealed interface LanHostSyncOutcome {
    data class HostUpdated(
        val profile: DeviceProfile,
    ) : LanHostSyncOutcome

    data object HostUnlinked : LanHostSyncOutcome

    /** The pinned host answered and still authorizes this phone; nothing to persist. */
    data object HostReachable : LanHostSyncOutcome

    data object Ignored : LanHostSyncOutcome
}

class AndroidLanRediscovery(
    private val pairingClient: AndroidLocalPairingClient,
    private val context: Context? = null,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    suspend fun decideAndMaybeUpdate(
        profile: DeviceProfile,
        announce: LanAnnouncePayload,
        localFingerprint: String?,
    ): DeviceProfile? =
        when (val outcome = syncAnnouncedHost(profile, announce, localFingerprint)) {
            is LanHostSyncOutcome.HostUpdated -> outcome.profile

            LanHostSyncOutcome.HostUnlinked,
            LanHostSyncOutcome.HostReachable,
            LanHostSyncOutcome.Ignored,
            -> null
        }

    suspend fun syncAnnouncedHost(
        profile: DeviceProfile,
        announce: LanAnnouncePayload,
        localFingerprint: String?,
    ): LanHostSyncOutcome {
        val expectedPin = profile.localAgentCertificateFingerprint
        val preliminary =
            LanRediscoveryPolicy.decide(
                profile = profile,
                announce = announce,
                localFingerprint = localFingerprint,
                expectedTlsPin = expectedPin,
                tlsPinVerified = false,
            )
        if (preliminary.blocksRediscovery()) {
            return LanHostSyncOutcome.Ignored
        }
        val response = registerWithPinnedHost(profile, announce.pairingUrl, expectedPin) ?: return LanHostSyncOutcome.Ignored
        if (response.authorizationStatus.marksHostUnlinked()) {
            return LanHostSyncOutcome.HostUnlinked
        }
        return when (
            val decision =
                LanRediscoveryPolicy.decide(
                    profile = profile,
                    announce = announce,
                    localFingerprint = localFingerprint,
                    expectedTlsPin = expectedPin,
                    tlsPinVerified = true,
                )
        ) {
            is LanRediscoveryDecision.UpdateLocalHost -> {
                val updated = profile.copy(localHost = decision.host, permissions = response.permissions ?: profile.permissions)
                LanHostSyncOutcome.HostUpdated(updated.copy(advertisedEndpoints = updated.stableAdvertisedEndpoints()))
            }

            LanRediscoveryDecision.HostUnlinked -> {
                LanHostSyncOutcome.HostUnlinked
            }

            else -> {
                if (response.permissions != null && response.permissions != profile.permissions) {
                    LanHostSyncOutcome.HostUpdated(profile.copy(permissions = requireNotNull(response.permissions)))
                } else {
                    LanHostSyncOutcome.HostReachable
                }
            }
        }
    }

    suspend fun probeAuthorization(
        profile: DeviceProfile,
        remoteOnly: Boolean = false,
    ): LanHostSyncOutcome {
        val pin = profile.localAgentCertificateFingerprint ?: return LanHostSyncOutcome.Ignored
        val deviceId =
            profile.authorizedDeviceId?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                ?: return LanHostSyncOutcome.Ignored
        val urls =
            (profile.advertisedEndpoints.map { it.pairingUrl } + profile.stableAdvertisedEndpoints().map { it.pairingUrl })
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .filter {
                    !remoteOnly || java.net
                        .URI(it)
                        .host
                        ?.removePrefix("[")
                        ?.removeSuffix("]") == profile.vpnHost?.host
                }
        // Probe all known paths together: an unreachable LAN must not delay a working remote path.
        val responses =
            coroutineScope {
                urls
                    .map { pairingUrl ->
                        async { registerWithPinnedHost(profile.copy(authorizedDeviceId = deviceId), pairingUrl, pin) }
                    }.awaitAll()
                    .filterNotNull()
            }
        if (responses.any { it.authorizationStatus.marksHostUnlinked() }) return LanHostSyncOutcome.HostUnlinked
        val response = responses.firstOrNull() ?: return LanHostSyncOutcome.Ignored
        return if (response.permissions != null && response.permissions != profile.permissions) {
            LanHostSyncOutcome.HostUpdated(profile.copy(permissions = requireNotNull(response.permissions)))
        } else {
            LanHostSyncOutcome.HostReachable
        }
    }

    private suspend fun registerWithPinnedHost(
        profile: DeviceProfile,
        pairingUrl: String,
        expectedPin: String?,
    ): LanRegisterResponse? {
        expectedPin?.let { pairingClient.pinServerFingerprint(it) } ?: return null
        val deviceId = profile.authorizedDeviceId?.takeIf { it.isNotBlank() } ?: return null
        val fingerprint = profile.pairedHostIdentity?.fingerprint?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            pairingClient.registerLan(
                pairingUrl,
                LanRegisterRequest(
                    fingerprint = fingerprint,
                    authorizedDeviceId = deviceId,
                ),
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    suspend fun receiveAnnounce(timeoutMillis: Int = 1_200): LanAnnouncePayload? =
        withContext(Dispatchers.IO) {
            val group = InetAddress.getByName(LanRediscoveryPolicy.MULTICAST_GROUP)
            val multicastLock =
                runCatching {
                    val wifi = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wifi?.createMulticastLock("aegis-lan-rediscovery")?.apply {
                        setReferenceCounted(true)
                        acquire()
                    }
                }.getOrNull()
            try {
                MulticastSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.soTimeout = timeoutMillis
                    socket.bind(InetSocketAddress(LanRediscoveryPolicy.PORT))
                    NetworkInterface
                        .getNetworkInterfaces()
                        .toList()
                        .filter { network -> network.isUp && !network.isLoopback && network.supportsMulticast() }
                        .forEach { network ->
                            runCatching {
                                socket.joinGroup(InetSocketAddress(group, LanRediscoveryPolicy.PORT), network)
                            }
                        }
                    val buffer = ByteArray(2_048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    json.decodeFromString(LanAnnouncePayload.serializer(), payload)
                }
            } catch (_: SocketTimeoutException) {
                null
            } finally {
                runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            }
        }
}

/**
 * A rediscovery announce is dropped outright when it is our own echo, an
 * uninteresting host, or a pin mismatch that is final (anything other than
 * "the pin was not verified yet", which the pinned re-register resolves).
 */
private fun LanRediscoveryDecision.blocksRediscovery(): Boolean =
    when (this) {
        is LanRediscoveryDecision.PinMismatch -> !reason.contains("was not verified")

        is LanRediscoveryDecision.DropSelf,
        is LanRediscoveryDecision.Ignore,
        -> true

        else -> false
    }
