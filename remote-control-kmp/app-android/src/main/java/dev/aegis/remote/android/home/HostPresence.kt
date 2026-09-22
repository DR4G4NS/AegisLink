package dev.aegis.remote.android.home

import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.pairing.LanAnnouncePayload
import dev.aegis.remote.core.pairing.LanRediscoveryPolicy

/** Whether the paired PC is currently reachable, as opposed to merely paired. */
enum class HostPresenceStatus {
    Unknown,
    Online,
    Offline,
}

data class HostPresence(
    val status: HostPresenceStatus = HostPresenceStatus.Unknown,
    val lastSeenEpochMillis: Long? = null,
)

/**
 * Presence is derived from live evidence only: the desktop agent multicasts a LAN announce
 * every second while it runs, pinned HTTPS probes answer, and an active WebRTC session
 * streams. Anything older than [STALE_AFTER_MILLIS] without fresh evidence is reported offline.
 */
internal object HostPresenceTracker {
    const val STALE_AFTER_MILLIS = 6_000L

    fun matches(
        profile: DeviceProfile,
        announce: LanAnnouncePayload,
    ): Boolean {
        val pairedFingerprint = profile.pairedHostIdentity?.fingerprint?.takeIf { it.isNotBlank() }
        if (pairedFingerprint != null) {
            return LanRediscoveryPolicy.normalizeFingerprint(pairedFingerprint) ==
                LanRediscoveryPolicy.normalizeFingerprint(announce.fingerprint)
        }
        val pin = profile.localAgentCertificateFingerprint?.takeIf { it.isNotBlank() } ?: return false
        return LanRediscoveryPolicy.normalizeFingerprint(pin) == LanRediscoveryPolicy.normalizeFingerprint(announce.tlsPin)
    }

    fun markSeen(
        presence: Map<DeviceProfileId, HostPresence>,
        id: DeviceProfileId,
        nowEpochMillis: Long,
    ): Map<DeviceProfileId, HostPresence> = presence + (id to HostPresence(HostPresenceStatus.Online, nowEpochMillis))

    fun markUnreachable(
        presence: Map<DeviceProfileId, HostPresence>,
        id: DeviceProfileId,
    ): Map<DeviceProfileId, HostPresence> {
        val current = presence[id]
        if (current?.status == HostPresenceStatus.Online && current.lastSeenEpochMillis != null) return presence
        return presence + (id to HostPresence(HostPresenceStatus.Offline, current?.lastSeenEpochMillis))
    }

    fun expire(
        presence: Map<DeviceProfileId, HostPresence>,
        nowEpochMillis: Long,
        staleAfterMillis: Long = STALE_AFTER_MILLIS,
    ): Map<DeviceProfileId, HostPresence> {
        var changed = false
        val expired =
            presence.mapValues { (_, value) ->
                val lastSeen = value.lastSeenEpochMillis
                if (value.status == HostPresenceStatus.Online && lastSeen != null && nowEpochMillis - lastSeen > staleAfterMillis) {
                    changed = true
                    value.copy(status = HostPresenceStatus.Offline)
                } else {
                    value
                }
            }
        return if (changed) expired else presence
    }
}
