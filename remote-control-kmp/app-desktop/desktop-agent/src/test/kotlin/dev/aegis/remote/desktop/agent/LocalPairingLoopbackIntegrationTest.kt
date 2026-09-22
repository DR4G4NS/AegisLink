package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.monitor.MonitorProvider
import dev.aegis.remote.core.pairing.LocalPairingPayload
import dev.aegis.remote.core.pairing.LocalPairingQrPayload
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.LocalPairingResponse
import dev.aegis.remote.core.pairing.LocalPairingStatusResponse
import dev.aegis.remote.core.pairing.localPairingDeviceProofPayload
import dev.aegis.remote.core.pairing.localPairingProofPayload
import dev.aegis.remote.core.security.LocalDeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.Inflater
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalPairingLoopbackIntegrationTest {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    @Test
    fun realHttpsPinnedPairingReachesDesktopAgentAndCompletesApproval() =
        runTest {
            val root = Files.createTempDirectory("aegis-pairing-loopback")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var agent: DesktopAgent? = null
            try {
                val privateKeyProtector = LoopbackPrivateKeyProtector()
                val phoneIdentity =
                    DesktopDeviceIdentityStore(
                        root.resolve("phone-identity.json"),
                        privateKeyProtector,
                    ).getOrCreate()
                val (createdAgent, deviceTrustStore) = createAgent(root, scope, privateKeyProtector)
                agent = createdAgent
                agent.start()
                eventually { agent.state.value.pairingServerRunning }

                val initialQrRaw = assertNotNull(agent.state.value.pairingQrPayload)
                val initialQr =
                    decodeQr(initialQrRaw)
                val client = PinnedJvmLocalPairingClient(json).pinServerFingerprint(initialQr.agentFingerprint)
                val payload = client.fetchPayload(initialQr.pairingUrl)
                assertEquals(initialQr.pairingCode, payload.pairingCode)
                assertEquals(
                    initialQr.tokenId,
                    agent.state.value.pairingQrPayload
                        ?.let { decodeQr(it).tokenId },
                )

                val request = buildSignedRequest(phoneIdentity, initialQr, payload)

                val response = client.requestPairing(initialQr.pairingUrl, request)
                assertEquals(LocalPairingRequestStatus.Pending, response.status)
                val requestId = assertNotNull(response.requestId).also { assertTrue(it.isNotBlank()) }
                eventually {
                    agent.state.value.pendingPairingRequests
                        .any { it.requestId == requestId }
                }
                assertTrue(
                    agent.state.value.logs
                        .any { it.eventCode == AgentLogEventCode.PairingRequestReceived },
                )
                assertTrue(
                    agent.state.value.logs
                        .any { it.eventCode == AgentLogEventCode.PairingRequestVisible },
                )

                eventually {
                    val current = agent.state.value.pairingQrPayload
                    current != null && current != initialQrRaw
                }
                assertTrue(
                    agent.state.value.pendingPairingRequests
                        .any { it.requestId == requestId },
                )
                assertEquals(
                    LocalPairingRequestStatus.Pending,
                    client.getPairingStatus(initialQr.pairingUrl, requestId).status,
                )

                agent.approvePairing(requestId)
                eventually {
                    agent.state.value.pendingPairingRequests
                        .none { it.requestId == requestId } &&
                        agent.state.value.authorizedDevices
                            .any { it.displayName == "Loopback Android" }
                }
                assertEquals(
                    LocalPairingRequestStatus.Approved,
                    client.getPairingStatus(initialQr.pairingUrl, requestId).status,
                )
                assertTrue(deviceTrustStore.listAuthorizedDevices().any { it.displayName == "Loopback Android" })
            } finally {
                agent?.stop()
                scope.coroutineContext[Job]?.cancel()
                root.toFile().deleteRecursively()
            }
            assertFalse(agent?.state?.value?.pairingServerRunning == true)
            assertTrue(
                agent
                    ?.state
                    ?.value
                    ?.pendingPairingRequests
                    .orEmpty()
                    .isEmpty(),
            )
        }

    private fun createAgent(
        root: java.nio.file.Path,
        scope: CoroutineScope,
        privateKeyProtector: DesktopPrivateKeyProtector,
    ): Pair<DesktopAgent, FileDeviceTrustStore> {
        val settings = FileDesktopAppSettingsRepository(root.resolve("settings.json"))
        val server =
            KtorLocalPairingServer(
                host = "127.0.0.1",
                advertisedHosts = { listOf("127.0.0.1") },
                port = availablePort(),
                tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                deviceIdentityStore =
                    DesktopDeviceIdentityStore(
                        root.resolve("desktop-identity.json"),
                        privateKeyProtector,
                    ),
            )
        val deviceTrustStore =
            FileDeviceTrustStore(
                file = root.resolve("authorized-devices.json"),
                payloadProtector = PlainTrustStorePayloadProtector,
            )
        return DesktopAgent(
            pairingServer = server,
            trustStore = deviceTrustStore,
            relayConnector = null,
            monitorProvider =
                object : MonitorProvider {
                    override suspend fun listMonitors(): List<MonitorInfo> = emptyList()
                },
            capabilityDetector =
                object : DesktopCapabilityDetector {
                    override fun detect() = DesktopCapabilityReport(CapabilityStatus.Unavailable, CapabilityStatus.Unavailable)
                },
            autostartManager =
                object : DesktopAutostartManager {
                    override fun status() = DesktopAutostartStatus(false, false, "test")

                    override fun setEnabled(enabled: Boolean) = status()
                },
            manualConnectionInfoProvider = DesktopManualConnectionInfoProvider { DesktopManualConnectionInfo() },
            openSshProvisioner = LoopbackOpenSshManager(),
            settingsRepository = settings,
            relayConfigRepository = settings,
            scope = scope,
        ) to deviceTrustStore
    }

    private suspend fun buildSignedRequest(
        phoneIdentity: LocalDeviceIdentity,
        qr: LocalPairingQrPayload,
        payload: LocalPairingPayload,
    ): LocalPairingRequestBody {
        val identity = phoneIdentity.publicIdentity
        val unsignedRequest =
            LocalPairingRequestBody(
                pairingCode = payload.pairingCode,
                challengeNonce = payload.nonce,
                challengeProof = "",
                deviceName = "Loopback Android",
                deviceFingerprint = identity.fingerprint,
                qrTokenId = qr.tokenId,
                deviceIdentity = identity,
                sshPublicKey = validOpenSshEd25519PublicKey(),
            )
        val requestWithProof =
            unsignedRequest.copy(
                challengeProof =
                    LocalProtocolAuthenticator().deriveToken(
                        qr.pairingSecret,
                        localPairingProofPayload(
                            nonce = payload.nonce,
                            deviceName = unsignedRequest.deviceName,
                            deviceFingerprint = unsignedRequest.deviceFingerprint,
                            qrTokenId = unsignedRequest.qrTokenId,
                            sshPublicKey = unsignedRequest.sshPublicKey,
                            deviceIdentity = unsignedRequest.deviceIdentity,
                        ),
                    ),
            )
        return requestWithProof.copy(
            deviceIdentitySignature =
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    phoneIdentity.sign(localPairingDeviceProofPayload(requestWithProof)),
                ),
        )
    }

    private fun decodeQr(value: String): LocalPairingQrPayload = json.decodeFromString(inflateQr(value.removePrefix("AEGIS3:")))

    private fun inflateQr(encoded: String): String {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(Base64.getUrlDecoder().decode(encoded))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            while (!inflater.finished()) output.write(buffer, 0, inflater.inflate(buffer))
            output.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    private suspend fun eventually(
        timeoutMillis: Long = 15_000L,
        condition: () -> Boolean,
    ) {
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMillis) {
                while (!condition()) delay(25L)
            }
        }
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
}

private class PinnedJvmLocalPairingClient(
    private val json: Json,
    private var expectedFingerprint: String? = null,
) {
    fun pinServerFingerprint(fingerprint: String): PinnedJvmLocalPairingClient = apply { expectedFingerprint = fingerprint.removePrefix("SHA256:") }

    fun fetchPayload(pairingUrl: String): LocalPairingPayload = execute(pairingUrl + "/pairing/payload", "GET")

    fun requestPairing(
        pairingUrl: String,
        request: LocalPairingRequestBody,
    ): LocalPairingResponse =
        execute(
            pairingUrl + "/pairing/requests",
            "POST",
            json.encodeToString(request).toByteArray(Charsets.UTF_8),
        )

    fun getPairingStatus(
        pairingUrl: String,
        requestId: String,
    ): LocalPairingStatusResponse = execute(pairingUrl + "/pairing/requests/$requestId", "GET")

    private inline fun <reified T> execute(
        url: String,
        method: String,
        body: ByteArray? = null,
    ): T {
        val connection =
            pinnedConnection(url).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 15_000
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(body.size)
                    outputStream.use { it.write(body) }
                }
            }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "HTTP $status: $text" }
            json.decodeFromString(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun pinnedConnection(url: String): HttpsURLConnection {
        val expected = requireNotNull(expectedFingerprint)
        val trustManager =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<X509Certificate>,
                    authType: String,
                ) {
                    check(chain.firstOrNull()?.let(::certificateFingerprint) == expected) { "TLS pin mismatch" }
                }
            }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier =
                javax.net.ssl.HostnameVerifier { _, session ->
                    (session.peerCertificates.firstOrNull() as? X509Certificate)?.let(::certificateFingerprint) == expected
                }
        }
    }

    private fun certificateFingerprint(certificate: X509Certificate): String =
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
        )
}

private class LoopbackPrivateKeyProtector : DesktopPrivateKeyProtector {
    override val scheme: String = "test-loopback-v1"

    override fun protect(pkcs8: ByteArray): ByteArray = pkcs8.clone()

    override fun unprotect(protectedValue: ByteArray): ByteArray = protectedValue.clone()
}

private class LoopbackOpenSshManager : AegisOpenSshManager {
    override suspend fun inspect(): AegisOpenSshState =
        AegisOpenSshState(
            serviceName = "AegisOpenSSH-test",
            serviceStatus = "Running",
            port = 48_222,
            rootDirectory = "/tmp/aegis-openssh-test",
            configPath = "/tmp/aegis-openssh-test/sshd_config",
            authorizedKeysPath = "/tmp/aegis-openssh-test/authorized_keys",
            hostKeyPath = "/tmp/aegis-openssh-test/ssh_host_ed25519_key",
            hostKeyFingerprint = "SHA256:loopback-host",
            hostKeyAlgorithm = "ssh-ed25519",
            firewallRuleName = "Aegis OpenSSH test",
            authorizedUser = "aegis-test",
        )

    override suspend fun enrollAuthorizedKey(
        publicKey: String,
        deviceId: String,
    ): AegisSshBootstrapResult =
        AegisSshBootstrapResult(
            username = "aegis-test",
            port = 48_222,
            hostKeyFingerprint = HostKeyFingerprint("SHA256", "loopback-host"),
            wakeOnLanConfigs = emptyList(),
            enrollment =
                AegisSshKeyEnrollmentResult(
                    deviceId = deviceId,
                    fingerprint = "SHA256:loopback-client",
                    algorithm = "ssh-ed25519",
                    added = true,
                    authorizedKeysPath = "/tmp/aegis-openssh-test/authorized_keys",
                ),
        )

    override suspend fun removeAuthorizedKey(deviceId: String): AegisSshKeyRemovalResult =
        AegisSshKeyRemovalResult(
            deviceId,
            removed = true,
            authorizedKeysPath = "/tmp/aegis-openssh-test/authorized_keys",
        )
}

private fun validOpenSshEd25519PublicKey(): String {
    val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val rawPublicKey = keyPair.public.encoded.copyOfRange(keyPair.public.encoded.size - 32, keyPair.public.encoded.size)
    val blob = ByteArrayOutputStream()
    blob.writeSshField("ssh-ed25519".toByteArray(Charsets.US_ASCII))
    blob.writeSshField(rawPublicKey)
    return "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob.toByteArray())} loopback-test"
}

private fun ByteArrayOutputStream.writeSshField(value: ByteArray) {
    write((value.size ushr 24) and 0xff)
    write((value.size ushr 16) and 0xff)
    write((value.size ushr 8) and 0xff)
    write(value.size and 0xff)
    write(value)
}
