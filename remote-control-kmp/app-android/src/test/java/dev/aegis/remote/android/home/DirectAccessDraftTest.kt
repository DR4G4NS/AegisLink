package dev.aegis.remote.android.home

import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.SshCredentialsRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectAccessDraftTest {
    private val profile =
        DeviceProfile(
            id = DeviceProfileId("pc"),
            displayName = "PC",
            localHost = HostAddress("192.168.1.2", 48291),
            username = "user",
            authMethod = AuthMethod.PrivateKey,
            authorizedDeviceId = "local-phone",
            localProtocolTokenRef = SshCredentialsRef("paired-token"),
            localAgentCertificateFingerprint = "pin",
            permissions = DevicePermissions(visual = true, input = false),
        )

    @Test
    fun `remote address keeps local pairing and permissions and separates ports`() {
        val updated = DirectAccessDraft(" home.example.com ", "50000", "50001").applyTo(profile)
        assertEquals(HostAddress("home.example.com", 50000), updated.vpnHost)
        assertEquals(50001, updated.remoteSshPort)
        assertEquals(profile, updated.copy(vpnHost = null, remoteSshPort = null))
    }

    @Test
    fun `ipv6 addresses are supported`() {
        assertEquals("2001:db8::1", DirectAccessDraft("[2001:db8::1]").applyTo(profile).vpnHost?.host)
    }

    @Test
    fun `urls credentials and invalid ports are rejected`() {
        listOf("", "https://example.com", "user@example.com", "example.com/path", "a b", "example.com:443", "a?b").forEach { host ->
            assertFailsWith<IllegalArgumentException>(host) { DirectAccessDraft(host).applyTo(profile) }
        }
        listOf("0", "65536", "text", "-1").forEach { port ->
            assertFailsWith<IllegalStateException> { DirectAccessDraft("example.com", port).applyTo(profile) }
            assertFailsWith<IllegalStateException> { DirectAccessDraft("example.com", filePort = port).applyTo(profile) }
        }
    }
}
