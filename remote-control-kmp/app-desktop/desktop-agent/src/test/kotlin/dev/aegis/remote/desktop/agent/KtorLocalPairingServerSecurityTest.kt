package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.pairing.LanAuthorizationStatus
import dev.aegis.remote.core.pairing.LanRegisterRequest
import dev.aegis.remote.core.pairing.LanRegisterResponse
import dev.aegis.remote.core.pairing.LocalPairedProfile
import dev.aegis.remote.core.pairing.LocalPairingPayload
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.LocalPairingResponse
import dev.aegis.remote.core.pairing.LocalPairingStatusResponse
import dev.aegis.remote.core.pairing.localPairingDeviceProofPayload
import dev.aegis.remote.core.pairing.localPairingProofPayload
import dev.aegis.remote.core.security.LocalDeviceIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KtorLocalPairingServerSecurityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun challengeIsSingleUse() =
        runTest {
            val port = availablePort()
            val identityDirectory = Files.createTempDirectory("aegis-pairing-identities")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(identityDirectory.resolve("phone.json"), protector).getOrCreate()
            var receivedRequest: DesktopPairingRequest? = null
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("192.168.1.92", "127.0.0.1") },
                    port = port,
                    clock = { 1_000L },
                    nonceGenerator = { "single-use-nonce" },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(identityDirectory.resolve("host.json"), protector),
                )
            try {
                val session =
                    server.start(onPairingRequest = {
                        receivedRequest = it
                        PairingRequestDispatchResult.Accepted
                    })
                val payload = getPayload(port, session.agentFingerprint)
                assertTrue(payload.host in setOf("127.0.0.1", "192.168.1.92"))
                val request = signedPairingRequest(payload, session, phoneIdentity)

                assertEquals(200, postRequest(port, session.agentFingerprint, request))
                assertEquals(payload.host, receivedRequest?.localHost)
                assertEquals(403, postRequest(port, session.agentFingerprint, request))
            } finally {
                server.stop()
                identityDirectory.toFile().deleteRecursively()
            }
        }

    @Test
    fun rejectsSecp256k1IdentityProofBeforeConsumingChallenge() =
        runTest {
            val port = availablePort()
            val identityDirectory = Files.createTempDirectory("aegis-pairing-secp256k1")
            val protector = TestLocalTlsProtector()
            var receivedRequest: DesktopPairingRequest? = null
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = port,
                    clock = { 1_000L },
                    nonceGenerator = { "secp256k1-nonce" },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(identityDirectory.resolve("host.json"), protector),
                )
            try {
                val session =
                    server.start(onPairingRequest = {
                        receivedRequest = it
                        PairingRequestDispatchResult.Accepted
                    })
                val payload = getPayload(port, session.agentFingerprint)
                val rejected = signedPairingRequest(payload, session, secp256k1Identity())

                val (status, response) = postResponse(port, session.agentFingerprint, rejected)
                assertEquals(403, status)
                assertTrue(response.contains("QRP-7102"))
                assertEquals(null, receivedRequest)
            } finally {
                server.stop()
                identityDirectory.toFile().deleteRecursively()
            }
        }

    @Test
    fun expiredChallengeIsRejected() =
        runTest {
            var now = 1_000L
            val port = availablePort()
            val identityDirectory = Files.createTempDirectory("aegis-expired-pairing-identities")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(identityDirectory.resolve("phone.json"), protector).getOrCreate()
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = port,
                    clock = { now },
                    nonceGenerator = { "expiring-nonce" },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(identityDirectory.resolve("host.json"), protector),
                )
            try {
                val session = server.start(onPairingRequest = { PairingRequestDispatchResult.Accepted })
                val payload = getPayload(port, session.agentFingerprint)
                now = payload.expiresAtEpochMillis + 1
                val request = signedPairingRequest(payload, session, phoneIdentity)

                assertEquals(403, postRequest(port, session.agentFingerprint, request))
            } finally {
                server.stop()
                identityDirectory.toFile().deleteRecursively()
            }
        }

    @Test
    fun refreshRotatesOnlyShortLivedQrValues() =
        runTest {
            var now = 1_000L
            val directory = Files.createTempDirectory("aegis-qr-refresh")
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { now },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), TestLocalTlsProtector()),
                )
            try {
                val first = server.start(onPairingRequest = { PairingRequestDispatchResult.Accepted })
                now += 5_000L
                val second = server.refreshPairingSession()

                assertNotEquals(first.tokenId, second.tokenId)
                assertNotEquals(first.pairingSecret, second.pairingSecret)
                assertEquals(first.agentFingerprint, second.agentFingerprint)
                assertTrue(first.hostIdentity.matches(second.hostIdentity))
                assertEquals(now, second.issuedAtEpochMillis)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun certificatePinSurvivesServerRestart() =
        runTest {
            val directory = Files.createTempDirectory("aegis-local-tls-test")
            val identityFile = directory.resolve("identity.json")
            val hostIdentityFile = directory.resolve("host.json")
            val protector = TestLocalTlsProtector()
            val firstServer =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    tlsIdentityStore = PersistentLocalTlsIdentityStore(identityFile, protector),
                    deviceIdentityStore = DesktopDeviceIdentityStore(hostIdentityFile, protector),
                )
            val firstFingerprint =
                try {
                    firstServer.start(onPairingRequest = { PairingRequestDispatchResult.Accepted }).agentFingerprint
                } finally {
                    firstServer.stop()
                }
            val secondServer =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    tlsIdentityStore = PersistentLocalTlsIdentityStore(identityFile, protector),
                    deviceIdentityStore = DesktopDeviceIdentityStore(hostIdentityFile, protector),
                )
            try {
                val secondFingerprint = secondServer.start(onPairingRequest = { PairingRequestDispatchResult.Accepted }).agentFingerprint
                assertEquals(firstFingerprint, secondFingerprint)
            } finally {
                secondServer.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun corruptPersistedIdentityFailsClosedInsteadOfRotatingThePin() {
        val directory = Files.createTempDirectory("aegis-local-tls-corrupt")
        val identityFile = directory.resolve("identity.json")
        val protector = TestLocalTlsProtector()
        try {
            PersistentLocalTlsIdentityStore(identityFile, protector).getOrCreate()
            Files.writeString(identityFile, "not-an-identity")

            assertFailsWith<LocalTlsIdentityStorageException> {
                PersistentLocalTlsIdentityStore(identityFile, protector).getOrCreate()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun unavailableAgentDoesNotExposePendingAndReleasesTheQrReservation() =
        runTest {
            val directory = Files.createTempDirectory("aegis-dispatch-unavailable")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            val events = CopyOnWriteArrayList<PairingServerEvent>()
            var rejectedRequestId: String? = null
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { 1_000L },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session =
                    server.start(
                        onPairingRequest = {
                            rejectedRequestId = it.requestId
                            PairingRequestDispatchResult.AgentUnavailable
                        },
                        onPairingEvent = { events += it },
                    )
                val payload = getPayload(session.port, session.agentFingerprint)
                val request = signedPairingRequest(payload, session, phoneIdentity)

                val (status, responseBody) = postResponse(session.port, session.agentFingerprint, request)

                assertEquals(503, status)
                assertTrue(responseBody.contains("PAIRING_AGENT_UNAVAILABLE"))
                assertEquals(
                    LocalPairingRequestStatus.Unknown,
                    statusResponse(session.port, session.agentFingerprint, assertNotNull(rejectedRequestId)).status,
                )
                assertTrue(events.any { it.stage == PairingServerEventStage.PAIRING_REQUEST_DISPATCH_REJECTED })
                assertTrue(getPayload(session.port, session.agentFingerprint).nonce != payload.nonce)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun callbackExceptionReturns503AndCleansTheProvisionalRequest() =
        runTest {
            val directory = Files.createTempDirectory("aegis-dispatch-exception")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            var rejectedRequestId: String? = null
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { 1_000L },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session =
                    server.start(
                        onPairingRequest = {
                            rejectedRequestId = it.requestId
                            error("test dispatch failure")
                        },
                    )
                val payload = getPayload(session.port, session.agentFingerprint)
                val (status, body) = postResponse(session.port, session.agentFingerprint, signedPairingRequest(payload, session, phoneIdentity))

                assertEquals(503, status)
                assertTrue(body.contains("PAIRING_DISPATCH_FAILED"))
                assertEquals(
                    LocalPairingRequestStatus.Unknown,
                    statusResponse(session.port, session.agentFingerprint, assertNotNull(rejectedRequestId)).status,
                )
                assertTrue(getPayload(session.port, session.agentFingerprint).nonce != payload.nonce)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun acceptedRequestIdIsTheSameInHttpAndStatusAfterQrRotation() =
        runTest {
            val directory = Files.createTempDirectory("aegis-request-id-propagation")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            var received: DesktopPairingRequest? = null
            var now = 1_000L
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { now },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session =
                    server.start(onPairingRequest = { request ->
                        received = request
                        PairingRequestDispatchResult.Accepted
                    })
                val payload = getPayload(session.port, session.agentFingerprint)
                val (status, body) = postResponse(session.port, session.agentFingerprint, signedPairingRequest(payload, session, phoneIdentity))
                val response = json.decodeFromString<LocalPairingResponse>(body)
                val requestId = assertNotNull(response.requestId)

                assertEquals(200, status)
                assertEquals(LocalPairingRequestStatus.Pending, response.status)
                assertEquals(requestId, received?.requestId)
                now += 5_000L
                server.refreshPairingSession()
                assertEquals(LocalPairingRequestStatus.Pending, statusResponse(session.port, session.agentFingerprint, requestId).status)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun concurrentPostsWithTheSameQrTokenCreateAtMostOneApproval() =
        runTest {
            val directory = Files.createTempDirectory("aegis-concurrent-pairing")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            val received = CopyOnWriteArrayList<DesktopPairingRequest>()
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { 1_000L },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session =
                    server.start(onPairingRequest = { request ->
                        received += request
                        PairingRequestDispatchResult.Accepted
                    })
                val firstPayload = getPayload(session.port, session.agentFingerprint)
                val secondPayload = getPayload(session.port, session.agentFingerprint)
                val firstRequest = signedPairingRequest(firstPayload, session, phoneIdentity)
                val secondRequest = signedPairingRequest(secondPayload, session, phoneIdentity)
                val results =
                    listOf(
                        async(Dispatchers.IO) { postResponse(session.port, session.agentFingerprint, firstRequest).first },
                        async(Dispatchers.IO) { postResponse(session.port, session.agentFingerprint, secondRequest).first },
                    ).awaitAll()

                assertEquals(listOf(200, 409), results.sorted())
                assertEquals(1, received.size)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun stoppingDuringDispatchNeverReturnsPending() =
        runTest {
            val directory = Files.createTempDirectory("aegis-stop-during-dispatch")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session =
                    server.start(
                        onPairingRequest = {
                            entered.complete(Unit)
                            release.await()
                            PairingRequestDispatchResult.Accepted
                        },
                    )
                val payload = getPayload(session.port, session.agentFingerprint)
                val request = signedPairingRequest(payload, session, phoneIdentity)
                val result =
                    async(Dispatchers.IO) { runCatching { postResponse(session.port, session.agentFingerprint, request) } }
                withContext(Dispatchers.Default) { entered.await() }
                server.stop()
                release.complete(Unit)
                val response = result.await().getOrNull()
                assertTrue(response == null || response.first != 200)
            } finally {
                release.complete(Unit)
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun approvalCanExpireAndRejectionIsReportedWithoutLeavingAnApprovalCardInTheServer() =
        runTest {
            val directory = Files.createTempDirectory("aegis-expire-reject-pairing")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            var now = 1_000L
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { now },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session = server.start(onPairingRequest = { PairingRequestDispatchResult.Accepted })
                val firstPayload = getPayload(session.port, session.agentFingerprint)
                val firstResponse =
                    json.decodeFromString<LocalPairingResponse>(
                        postResponse(session.port, session.agentFingerprint, signedPairingRequest(firstPayload, session, phoneIdentity)).second,
                    )
                val firstRequestId = assertNotNull(firstResponse.requestId)
                server.reject(firstRequestId)
                assertEquals(LocalPairingRequestStatus.Rejected, statusResponse(session.port, session.agentFingerprint, firstRequestId).status)

                server.refreshPairingSession()
                val secondSession = server.refreshPairingSession()
                val secondPayload = getPayload(secondSession.port, secondSession.agentFingerprint)
                val secondResponse =
                    json.decodeFromString<LocalPairingResponse>(
                        postResponse(session.port, session.agentFingerprint, signedPairingRequest(secondPayload, secondSession, phoneIdentity)).second,
                    )
                val secondRequestId = assertNotNull(secondResponse.requestId)
                now += 121_000L
                assertEquals(LocalPairingRequestStatus.Expired, statusResponse(session.port, session.agentFingerprint, secondRequestId).status)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun operatorApprovalStaysPendingDuringSlowEnrollmentThenPublishesTheProfile() =
        runTest {
            val directory = Files.createTempDirectory("aegis-hold-approval-pairing")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            var now = 1_000L
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { now },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session = server.start(onPairingRequest = { PairingRequestDispatchResult.Accepted })
                val payload = getPayload(session.port, session.agentFingerprint)
                val response =
                    json.decodeFromString<LocalPairingResponse>(
                        postResponse(session.port, session.agentFingerprint, signedPairingRequest(payload, session, phoneIdentity)).second,
                    )
                val requestId = assertNotNull(response.requestId)
                now += 90_000L
                server.holdPendingApproval(requestId)
                now += 50_000L
                assertEquals(
                    LocalPairingRequestStatus.Pending,
                    statusResponse(session.port, session.agentFingerprint, requestId).status,
                )
                server.approve(requestId, testApprovedProfile(session.agentFingerprint))
                val approved = statusResponse(session.port, session.agentFingerprint, requestId)
                assertEquals(LocalPairingRequestStatus.Approved, approved.status)
                assertEquals("local-phone", approved.profile?.authorizedDeviceId)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun operatorApprovalPublishesTheProfileEvenAfterAPollMarkedTheRequestExpired() =
        runTest {
            val directory = Files.createTempDirectory("aegis-approve-after-expire-pairing")
            val protector = TestLocalTlsProtector()
            val phoneIdentity = DesktopDeviceIdentityStore(directory.resolve("phone.json"), protector).getOrCreate()
            var now = 1_000L
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { now },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(directory.resolve("host.json"), protector),
                )
            try {
                val session = server.start(onPairingRequest = { PairingRequestDispatchResult.Accepted })
                val payload = getPayload(session.port, session.agentFingerprint)
                val response =
                    json.decodeFromString<LocalPairingResponse>(
                        postResponse(session.port, session.agentFingerprint, signedPairingRequest(payload, session, phoneIdentity)).second,
                    )
                val requestId = assertNotNull(response.requestId)
                now += 121_000L
                assertEquals(
                    LocalPairingRequestStatus.Expired,
                    statusResponse(session.port, session.agentFingerprint, requestId).status,
                )
                server.approve(requestId, testApprovedProfile(session.agentFingerprint))
                val approved = statusResponse(session.port, session.agentFingerprint, requestId)
                assertEquals(LocalPairingRequestStatus.Approved, approved.status)
                assertNotNull(approved.profile)
            } finally {
                server.stop()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun lanRegisterReportsDeviceAuthorizationStatus() =
        runTest {
            val identityDirectory = Files.createTempDirectory("aegis-lan-register")
            val protector = TestLocalTlsProtector()
            val server =
                KtorLocalPairingServer(
                    advertisedHosts = { listOf("127.0.0.1") },
                    port = availablePort(),
                    clock = { 1_000L },
                    tlsIdentityStore = EphemeralLocalTlsIdentityStore(),
                    deviceIdentityStore = DesktopDeviceIdentityStore(identityDirectory.resolve("host.json"), protector),
                )
            try {
                server.bindDeviceAuthorizationLookup { deviceId ->
                    when (deviceId) {
                        "local-phone" -> LanAuthorizationStatus.REVOKED
                        "active-phone" -> LanAuthorizationStatus.ACTIVE
                        else -> LanAuthorizationStatus.NOT_FOUND
                    }
                }
                val session = server.start(onPairingRequest = { PairingRequestDispatchResult.Accepted })
                val revoked =
                    json.decodeFromString<LanRegisterResponse>(
                        postLanRegister(
                            session.port,
                            session.agentFingerprint,
                            LanRegisterRequest(fingerprint = "SHA256:host", authorizedDeviceId = "local-phone"),
                        ).second,
                    )
                assertEquals(LanAuthorizationStatus.REVOKED, revoked.authorizationStatus)
                val missing =
                    json.decodeFromString<LanRegisterResponse>(
                        postLanRegister(
                            session.port,
                            session.agentFingerprint,
                            LanRegisterRequest(fingerprint = "SHA256:host", authorizedDeviceId = "gone-phone"),
                        ).second,
                    )
                assertEquals(LanAuthorizationStatus.NOT_FOUND, missing.authorizationStatus)
                val unknown =
                    json.decodeFromString<LanRegisterResponse>(
                        postLanRegister(
                            session.port,
                            session.agentFingerprint,
                            LanRegisterRequest(fingerprint = "SHA256:host", authorizedDeviceId = "unknown"),
                        ).second,
                    )
                assertEquals(LanAuthorizationStatus.UNKNOWN, unknown.authorizationStatus)
            } finally {
                server.stop()
                identityDirectory.toFile().deleteRecursively()
            }
        }

    private fun testApprovedProfile(agentFingerprint: String): LocalPairedProfile =
        LocalPairedProfile(
            displayName = "PC",
            localHost = "127.0.0.1",
            sshPort = 48_222,
            agentFingerprint = agentFingerprint,
            authorizedDeviceId = "local-phone",
            username = "ian",
        )

    private fun getPayload(
        port: Int,
        fingerprint: String,
    ): LocalPairingPayload {
        val connection = pinnedConnection("https://127.0.0.1:$port/pairing/payload", fingerprint)
        return connection.inputStream.bufferedReader().use { json.decodeFromString(it.readText()) }
    }

    private fun postLanRegister(
        port: Int,
        fingerprint: String,
        request: LanRegisterRequest,
    ): Pair<Int, String> {
        val connection = pinnedConnection("https://127.0.0.1:$port/lan/register", fingerprint)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }
        val status = connection.responseCode
        val body =
            (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
        return status to body
    }

    private fun postRequest(
        port: Int,
        fingerprint: String,
        request: LocalPairingRequestBody,
    ): Int = postResponse(port, fingerprint, request).first

    private fun postResponse(
        port: Int,
        fingerprint: String,
        request: LocalPairingRequestBody,
    ): Pair<Int, String> {
        val connection = pinnedConnection("https://127.0.0.1:$port/pairing/requests", fingerprint)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(json.encodeToString(request).toByteArray()) }
        val status = connection.responseCode
        val body =
            (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
        return status to body
    }

    private fun statusResponse(
        port: Int,
        fingerprint: String,
        requestId: String,
    ): LocalPairingStatusResponse {
        val connection = pinnedConnection("https://127.0.0.1:$port/pairing/requests/$requestId", fingerprint)
        return connection.inputStream.bufferedReader().use { json.decodeFromString(it.readText()) }
    }

    private suspend fun signedPairingRequest(
        payload: LocalPairingPayload,
        session: LocalPairingSession,
        identity: LocalDeviceIdentity,
    ): LocalPairingRequestBody {
        val sshPublicKey = "ssh-ed25519 " + Base64.getEncoder().encodeToString(ByteArray(64) { index -> index.toByte() })
        val publicIdentity = identity.publicIdentity
        val proofPayload =
            localPairingProofPayload(
                nonce = payload.nonce,
                deviceName = "Phone",
                deviceFingerprint = publicIdentity.fingerprint,
                qrTokenId = session.tokenId,
                sshPublicKey = sshPublicKey,
                deviceIdentity = publicIdentity,
            )
        val unsigned =
            LocalPairingRequestBody(
                pairingCode = payload.pairingCode,
                challengeNonce = payload.nonce,
                challengeProof = LocalProtocolAuthenticator().deriveToken(session.pairingSecret, proofPayload),
                deviceName = "Phone",
                deviceFingerprint = publicIdentity.fingerprint,
                qrTokenId = session.tokenId,
                deviceIdentity = publicIdentity,
                sshPublicKey = sshPublicKey,
            )
        val signature =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                identity.sign(localPairingDeviceProofPayload(unsigned)),
            )
        return unsigned.copy(deviceIdentitySignature = signature)
    }

    private fun secp256k1Identity(): LocalDeviceIdentity {
        val provider = BouncyCastleProvider()
        val keyPair =
            KeyPairGenerator
                .getInstance("EC", provider)
                .apply { initialize(ECGenParameterSpec("secp256k1")) }
                .generateKeyPair()
        val publicKeySpki = keyPair.public.encoded
        val algorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256
        val deviceId =
            DeviceId(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                        DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, publicKeySpki),
                    ),
                ),
            )
        val publicIdentity =
            DevicePublicIdentity(
                deviceId = deviceId,
                algorithm = algorithm,
                publicKeySpki = publicKeySpki,
                fingerprint =
                    "SHA256:" +
                        Base64.getUrlEncoder().withoutPadding().encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(publicKeySpki),
                        ),
                securityLevel = IdentitySecurityLevel.TRUSTED_ENVIRONMENT,
            )
        return object : LocalDeviceIdentity {
            override val publicIdentity: DevicePublicIdentity = publicIdentity

            override suspend fun sign(payload: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA", provider).run {
                    initSign(keyPair.private)
                    update(payload)
                    sign()
                }
        }
    }

    private fun pinnedConnection(
        url: String,
        fingerprint: String,
    ): HttpsURLConnection {
        val expected = fingerprint.removePrefix("SHA256:")
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
                    val certificate = chain.firstOrNull() ?: error("missing server certificate")
                    check(fingerprint(certificate) == expected) { "unexpected server certificate" }
                }
            }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        return (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = context.socketFactory
            hostnameVerifier =
                javax.net.ssl.HostnameVerifier { _, session ->
                    val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                    certificate != null && fingerprint(certificate) == expected
                }
        }
    }

    private fun fingerprint(certificate: X509Certificate): String =
        Base64
            .getEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
}

private class TestLocalTlsProtector : DesktopPrivateKeyProtector {
    override val scheme: String = "test-memory-v1"

    override fun protect(pkcs8: ByteArray): ByteArray = pkcs8.clone()

    override fun unprotect(protectedValue: ByteArray): ByteArray = protectedValue.clone()
}
