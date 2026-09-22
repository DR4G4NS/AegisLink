package dev.aegis.remote.relay

import dev.aegis.remote.core.model.SessionId
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryRelayRegistryTest {
    @Test
    fun rejectsRelayDeviceIdTakeoverWithADifferentClaimedKey() {
        val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
        val legitimate =
            registry.register(
                dev.aegis.remote.core.relay.RelayDeviceRegistration(
                    relayDeviceId =
                        dev.aegis.remote.core.model
                            .RelayDeviceId("pc-identity"),
                    displayName = "Legitimate PC",
                    publicKeyFingerprint = "SHA256:legitimate-key",
                    remoteAccessEnabled = true,
                ),
            )

        val takeover =
            runCatching {
                registry.register(
                    dev.aegis.remote.core.relay.RelayDeviceRegistration(
                        relayDeviceId =
                            dev.aegis.remote.core.model
                                .RelayDeviceId("pc-identity"),
                        displayName = "Attacker-controlled name",
                        publicKeyFingerprint = "SHA256:attacker-key",
                        remoteAccessEnabled = true,
                    ),
                )
            }

        assertTrue(takeover.isFailure)
        assertFalse(registry.authenticate(legitimate.relayDeviceId, legitimate.authToken))
    }

    @Test
    fun rejectsRelayDeviceIdTakeoverWithCopiedNameIdAndTextFingerprint() {
        val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
        val copiedRegistration =
            dev.aegis.remote.core.relay.RelayDeviceRegistration(
                relayDeviceId =
                    dev.aegis.remote.core.model
                        .RelayDeviceId("pc-copied-metadata"),
                displayName = "Copied PC",
                publicKeyFingerprint = "SHA256:copied-text-only",
                remoteAccessEnabled = true,
            )
        registry.register(copiedRegistration)

        val takeover = runCatching { registry.register(copiedRegistration) }

        assertTrue(takeover.isFailure)
    }

    @Test
    fun registersDeviceWithExpiringToken() {
        val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L }, tokenTtlMillis = 5_000L)
        val response =
            RelayIdentityTestFixtures.register(
                registry = registry,
                identity = RelayIdentityTestFixtures.identity(),
                now = 1_000L,
            )

        assertTrue(registry.authenticate(response.relayDeviceId, response.authToken))
        assertFalse(registry.authenticate(response.relayDeviceId, "wrong"))
    }

    @Test
    fun createsSessionOnlyForKnownDevices() {
        val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
        val androidIdentity = RelayIdentityTestFixtures.identity()
        val android =
            RelayIdentityTestFixtures.register(
                registry = registry,
                identity = androidIdentity,
                displayName = "Android",
                remoteAccessEnabled = false,
                now = 1_000L,
            )
        val pcIdentity = RelayIdentityTestFixtures.identity()
        val pc =
            RelayIdentityTestFixtures.register(
                registry = registry,
                identity = pcIdentity,
                displayName = "PC",
                remoteAccessEnabled = true,
                now = 1_000L,
            )

        val session = registry.createSession(android.relayDeviceId, pc.relayDeviceId)

        assertNotNull(session)
        assertNotNull(registry.getSession(session.session.sessionId))
        assertEquals("Android", session.sourceIdentity?.displayName)
        assertTrue(session.sourceIdentity?.publicIdentity?.matches(androidIdentity.identity) == true)
        assertEquals("PC", session.targetIdentity?.displayName)
        assertTrue(session.targetIdentity?.publicIdentity?.matches(pcIdentity.identity) == true)
    }

    @Test
    fun signalingSessionRequiresTargetApproval() {
        val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
        val androidIdentity = RelayIdentityTestFixtures.identity()
        val android =
            RelayIdentityTestFixtures.register(
                registry = registry,
                identity = androidIdentity,
                displayName = "Android",
                remoteAccessEnabled = false,
                now = 1_000L,
            )
        val pcIdentity = RelayIdentityTestFixtures.identity()
        val pc =
            RelayIdentityTestFixtures.register(
                registry = registry,
                identity = pcIdentity,
                displayName = "PC",
                remoteAccessEnabled = true,
                now = 1_000L,
            )
        val session = registry.createSession(android.relayDeviceId, pc.relayDeviceId)!!

        assertNull(registry.getApprovedSession(session.session.sessionId))

        val approval = registry.decideSession(session.session.sessionId, pc.relayDeviceId, approved = true)

        assertNotNull(approval)
        assertEquals(android.relayDeviceId, approval.sourceRelayDeviceId)
        assertEquals("Android", approval.sourceIdentity?.displayName)
        assertEquals("PC", approval.targetIdentity?.displayName)
        assertTrue(approval.targetIdentity?.publicIdentity?.matches(pcIdentity.identity) == true)
        assertNotNull(registry.getApprovedSession(session.session.sessionId))
        assertNull(registry.decideSession(SessionId("missing"), pc.relayDeviceId, approved = true))
    }

    @Test
    fun persistsDevicesWithoutWritingPlainToken() {
        val storagePath = createTempDirectory("aegis-relay-test").resolve("relay-registry.json")
        val firstRegistry =
            RelayIdentityTestFixtures.registry(
                clock = { 1_000L },
                tokenTtlMillis = 5_000L,
                store = JsonRelayRegistryStore(storagePath),
            )
        val registration =
            RelayIdentityTestFixtures.register(
                registry = firstRegistry,
                identity = RelayIdentityTestFixtures.identity(),
                displayName = "PC",
                remoteAccessEnabled = true,
                now = 1_000L,
            )

        val storedJson = storagePath.readText()
        assertFalse(storedJson.contains(registration.authToken))

        val secondRegistry =
            RelayIdentityTestFixtures.registry(
                clock = { 2_000L },
                tokenTtlMillis = 5_000L,
                store = JsonRelayRegistryStore(storagePath),
            )

        assertTrue(secondRegistry.authenticate(registration.relayDeviceId, registration.authToken))
    }

    @Test
    fun persistsApprovedSessionsUntilExpiry() {
        val storagePath = createTempDirectory("aegis-relay-session-test").resolve("relay-registry.json")
        val firstRegistry =
            RelayIdentityTestFixtures.registry(
                clock = { 1_000L },
                sessionTtlMillis = 5_000L,
                store = JsonRelayRegistryStore(storagePath),
            )
        val android =
            RelayIdentityTestFixtures.register(
                registry = firstRegistry,
                identity = RelayIdentityTestFixtures.identity(),
                displayName = "Android",
                remoteAccessEnabled = false,
                now = 1_000L,
            )
        val pc =
            RelayIdentityTestFixtures.register(
                registry = firstRegistry,
                identity = RelayIdentityTestFixtures.identity(),
                displayName = "PC",
                remoteAccessEnabled = true,
                now = 1_000L,
            )
        val session = firstRegistry.createSession(android.relayDeviceId, pc.relayDeviceId)!!
        firstRegistry.decideSession(session.session.sessionId, pc.relayDeviceId, approved = true)

        val secondRegistry =
            RelayIdentityTestFixtures.registry(
                clock = { 2_000L },
                sessionTtlMillis = 5_000L,
                store = JsonRelayRegistryStore(storagePath),
            )

        assertNotNull(secondRegistry.getApprovedSession(session.session.sessionId))
    }

    @Test
    fun rejectsSessionWhenTargetRemoteAccessIsDisabled() {
        val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
        val android =
            RelayIdentityTestFixtures.register(
                registry = registry,
                identity = RelayIdentityTestFixtures.identity(),
                remoteAccessEnabled = true,
                now = 1_000L,
            )
        val pc =
            RelayIdentityTestFixtures.register(
                registry = registry,
                identity = RelayIdentityTestFixtures.identity(),
                remoteAccessEnabled = false,
                now = 1_000L,
            )

        assertNull(registry.createSession(android.relayDeviceId, pc.relayDeviceId))
    }
}
