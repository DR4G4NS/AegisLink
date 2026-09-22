package dev.aegis.remote.android.home

import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.pairing.LanAnnouncePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostPresenceTrackerTest {
    private val id = DeviceProfileId("pc-1")
    private val hostFingerprint = "SHA256:ABCDEF"

    private val profile =
        DeviceProfile(
            id = id,
            displayName = "Office PC",
            localHost = HostAddress("192.168.1.20"),
            sshPort = 22,
            username = "ian",
            authMethod = AuthMethod.PrivateKey,
            localAgentCertificateFingerprint = "SHA256:TLSPIN",
            pairedHostIdentity =
                DevicePublicIdentity(
                    deviceId = DeviceId("host-device"),
                    signingPublicKey = byteArrayOf(1, 2, 3),
                    keyAlgorithm = "Ed25519",
                ).copy(fingerprint = hostFingerprint),
        )

    private fun announce(fingerprint: String = hostFingerprint) =
        LanAnnouncePayload(
            fingerprint = fingerprint,
            tlsPin = "SHA256:TLSPIN",
            port = 48291,
            pairingUrl = "https://192.168.1.20:48291",
        )

    @Test
    fun announceMatchesOnlyThePairedHostFingerprint() {
        assertTrue(HostPresenceTracker.matches(profile, announce()))
        assertTrue(HostPresenceTracker.matches(profile, announce(fingerprint = "abcdef")))
        assertFalse(HostPresenceTracker.matches(profile, announce(fingerprint = "SHA256:OTHER")))
    }

    @Test
    fun fallsBackToTlsPinWhenHostIdentityIsUnknown() {
        val legacy = profile.copy(pairedHostIdentity = null)
        assertTrue(HostPresenceTracker.matches(legacy, announce(fingerprint = "SHA256:OTHER")))
        assertFalse(HostPresenceTracker.matches(legacy.copy(localAgentCertificateFingerprint = null), announce()))
    }

    @Test
    fun onlineHostsExpireAfterSilenceAndRecoverOnNextSighting() {
        val seen = HostPresenceTracker.markSeen(emptyMap(), id, nowEpochMillis = 10_000)
        assertEquals(HostPresenceStatus.Online, seen.getValue(id).status)

        val stillFresh = HostPresenceTracker.expire(seen, nowEpochMillis = 15_000)
        assertSame(seen, stillFresh)

        val stale = HostPresenceTracker.expire(seen, nowEpochMillis = 17_000)
        assertEquals(HostPresenceStatus.Offline, stale.getValue(id).status)
        assertEquals(10_000, stale.getValue(id).lastSeenEpochMillis)

        val back = HostPresenceTracker.markSeen(stale, id, nowEpochMillis = 18_000)
        assertEquals(HostPresenceStatus.Online, back.getValue(id).status)
    }

    @Test
    fun failedProbeDoesNotOverrideFreshLanEvidence() {
        val seen = HostPresenceTracker.markSeen(emptyMap(), id, nowEpochMillis = 10_000)
        assertSame(seen, HostPresenceTracker.markUnreachable(seen, id))
        assertEquals(HostPresenceStatus.Offline, HostPresenceTracker.markUnreachable(emptyMap(), id).getValue(id).status)
    }
}
