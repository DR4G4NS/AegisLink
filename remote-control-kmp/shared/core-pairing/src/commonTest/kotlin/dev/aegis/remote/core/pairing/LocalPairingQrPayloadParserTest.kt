package dev.aegis.remote.core.pairing

import dev.aegis.remote.core.model.DevicePermissions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalPairingQrPayloadParserTest {
    private val parser = LocalPairingQrPayloadParser()

    @Test
    fun parsesCurrentQrPayloadContract() {
        val payload =
            parser.parse(
                """
                {
                  "version": 1,
                  "pairingUrl": "http://127.0.0.1:48291",
                  "pairingCode": "123456",
                  "agentFingerprint": "SHA256:test",
                  "pairingSecret": "${"0".repeat(32)}"
                }
                """.trimIndent(),
            )

        assertEquals("http://127.0.0.1:48291", payload.pairingUrl)
        assertEquals("123456", payload.pairingCode)
        assertEquals("SHA256:test", payload.agentFingerprint)
        assertEquals("0".repeat(32), payload.pairingSecret)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(
                """
                {
                  "version": 3,
                  "pairingUrl": "http://127.0.0.1:48291",
                  "pairingCode": "123456",
                  "agentFingerprint": "SHA256:test",
                  "pairingSecret": "${"0".repeat(32)}"
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun parsesV2EndpointsWhileKeepingPrimaryUrlCompatibleWithV1Readers() {
        val payload =
            parser.parse(
                """
                {
                  "version": 2,
                  "pairingUrl": "http://192.168.1.92:48291",
                  "pairingUrls": [
                    "http://192.168.1.92:48291",
                    "http://10.147.19.204:48291",
                    "http://172.31.196.207:48291"
                  ],
                  "pairingCode": "123456",
                  "agentFingerprint": "SHA256:test",
                  "pairingSecret": "${"0".repeat(32)}"
                }
                """.trimIndent(),
            )

        assertEquals("http://192.168.1.92:48291", payload.pairingUrl)
        assertEquals(
            listOf(
                "http://192.168.1.92:48291",
                "http://10.147.19.204:48291",
                "http://172.31.196.207:48291",
            ),
            payload.candidatePairingUrls(),
        )
    }

    @Test
    fun approvedProfileRoundTripsDesktopPermissions() {
        val expected =
            LocalPairedProfile(
                displayName = "Office PC",
                localHost = "192.168.1.20",
                sshPort = 22,
                agentFingerprint = "SHA256:desktop",
                authorizedDeviceId = "desktop-trust-1",
                permissions =
                    DevicePermissions(
                        terminal = true,
                        visual = true,
                        input = true,
                        sftp = true,
                        clipboard = true,
                    ),
            )

        val encoded = Json.encodeToString(LocalPairedProfile.serializer(), expected)
        val decoded = Json.decodeFromString(LocalPairedProfile.serializer(), encoded)

        assertEquals(expected, decoded)
        assertTrue(decoded.permissions.clipboard)
    }

    @Test
    fun olderApprovedProfileWithoutPermissionsRemainsDecodable() {
        val decoded =
            Json.decodeFromString(
                LocalPairedProfile.serializer(),
                """{"displayName":"PC","localHost":"127.0.0.1","sshPort":22,"agentFingerprint":"SHA256:test","authorizedDeviceId":"device-1"}""",
            )

        assertEquals(DevicePermissions(), decoded.permissions)
    }
}
