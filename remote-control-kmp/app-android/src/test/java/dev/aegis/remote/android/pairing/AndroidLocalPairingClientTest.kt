package dev.aegis.remote.android.pairing

import dev.aegis.remote.core.pairing.LocalPairingClient
import dev.aegis.remote.core.pairing.LocalPairingPayload
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.LocalPairingResponse
import dev.aegis.remote.core.pairing.LocalPairingStatusResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLocalPairingClientTest {
    @Test
    fun acceptsOnlyLocalBaseUrls() {
        assertEquals("https://192.168.1.20:48291", normalizeLocalPairingBaseUrl("192.168.1.20:48291").toString())
        assertEquals("https://127.0.0.1:48291", normalizeLocalPairingBaseUrl("https://127.0.0.1:48291").toString())
        assertEquals("https://[fd00::20]:48291", normalizeLocalPairingBaseUrl("https://[fd00::20]:48291").toString())
    }

    @Test
    fun defaultsToHttpsPortAndRejectsInvalidPorts() {
        assertEquals("https://127.0.0.1:443", normalizeLocalPairingBaseUrl("127.0.0.1").toString())
        for (port in listOf(0, 65_536)) {
            assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("https://127.0.0.1:$port") }
        }
        assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("https:///missing-host") }
    }

    @Test
    fun rejectsCleartextPublicHostsAndUnexpectedUrlComponents() {
        assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("http://127.0.0.1:48291") }
        assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("http://8.8.8.8:48291") }
        assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("http://example.com:48291") }
        assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("http://127.0.0.1:48291/unexpected") }
        assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("http://user@127.0.0.1:48291") }
    }

    @Test
    fun allowsPublicAddressesOnlyForAlreadyPairedHosts() {
        assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl("https://203.0.113.20:48291") }
        assertEquals(
            "https://203.0.113.20:48291",
            normalizeLocalPairingBaseUrl("https://203.0.113.20:48291", allowPublicAddress = true).toString(),
        )
        listOf("http://203.0.113.20:48291", "https://user@203.0.113.20:48291", "https://203.0.113.20:48291/path").forEach {
            assertFailsWith<IllegalArgumentException> { normalizeLocalPairingBaseUrl(it, allowPublicAddress = true) }
        }
    }

    @Test
    fun certificatePinComparisonUsesExactSha256Digest() {
        val certificate = "test-certificate-der".toByteArray()
        val pin =
            Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(certificate),
            )
        assertTrue(matchesCertificateFingerprint(certificate, pin))
        assertTrue(!matchesCertificateFingerprint("other-certificate".toByteArray(), pin))
        val urlSafePin = Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(pin))
        assertTrue(matchesCertificateFingerprint(certificate, urlSafePin))
    }

    @Test
    fun selectsReachableVerifiedEndpointAfterUnreachableTunnelAddress() =
        runTest {
            val fake =
                FakePairingClient(
                    mapOf(
                        "https://172.31.196.207:48291" to Result.failure(IllegalStateException("unreachable tunnel")),
                        "https://192.168.1.92:48291" to Result.success(pairingPayload()),
                    ),
                )

            val endpoint =
                resolveVerifiedLocalPairingEndpoint(
                    pairingUrls = listOf("https://172.31.196.207:48291", "https://192.168.1.92:48291"),
                    expectedAgentFingerprint = "SHA256:desktop",
                    expectedPairingCode = "123456",
                    client = fake,
                )

            assertEquals("https://192.168.1.92:48291", endpoint.pairingUrl)
            assertEquals(
                listOf("https://172.31.196.207:48291", "https://192.168.1.92:48291"),
                fake.fetchAttempts,
            )
        }

    @Test
    fun neverAcceptsEndpointUntilFingerprintAndCodeMatchQr() =
        runTest {
            val fake =
                FakePairingClient(
                    mapOf(
                        "https://10.147.19.204:48291" to
                            Result.success(
                                pairingPayload(agentFingerprint = "SHA256:attacker"),
                            ),
                        "https://192.168.1.92:48291" to Result.success(pairingPayload()),
                    ),
                )

            val endpoint =
                resolveVerifiedLocalPairingEndpoint(
                    pairingUrls = listOf("https://10.147.19.204:48291", "https://192.168.1.92:48291"),
                    expectedAgentFingerprint = "SHA256:desktop",
                    expectedPairingCode = "123456",
                    client = fake,
                )

            assertEquals("https://192.168.1.92:48291", endpoint.pairingUrl)
        }

    @Test
    fun pollingKeepsRequestAliveUntilDelayedApproval() =
        runTest {
            val client =
                ScriptedStatusPairingClient(
                    ArrayDeque(
                        listOf(
                            Result.success(status(LocalPairingRequestStatus.Pending, "Still waiting")),
                            Result.success(status(LocalPairingRequestStatus.Pending, "Still waiting")),
                            Result.success(status(LocalPairingRequestStatus.Approved, "Approved")),
                        ),
                    ),
                )
            val events = mutableListOf<LocalPairingPollingEvent>()

            val outcome =
                awaitLocalPairingDecision(
                    client = client,
                    pairingUrl = "http://192.168.1.92:48291",
                    requestId = "req-existing",
                    policy =
                        LocalPairingPollingPolicy(
                            deadlineMillis = 10_000,
                            pollIntervalMillis = 100,
                            maximumRetryDelayMillis = 800,
                        ),
                    monotonicMillis = { testScheduler.currentTime },
                    onEvent = events::add,
                )

            val terminal = assertIs<LocalPairingPollingOutcome.Terminal>(outcome)
            assertEquals(LocalPairingRequestStatus.Approved, terminal.response.status)
            assertEquals(3, client.statusAttempts)
            assertEquals(0, client.requestAttempts, "status recovery must never submit another approval request")
            assertEquals(2, events.filterIsInstance<LocalPairingPollingEvent.Pending>().size)
        }

    @Test
    fun transientStatusTimeoutBacksOffThenRecoversSameRequest() =
        runTest {
            val client =
                ScriptedStatusPairingClient(
                    ArrayDeque(
                        listOf(
                            Result.failure(SocketTimeoutException("temporary read timeout")),
                            Result.success(status(LocalPairingRequestStatus.Approved, "Approved after retry")),
                        ),
                    ),
                )
            val events = mutableListOf<LocalPairingPollingEvent>()

            val outcome =
                awaitLocalPairingDecision(
                    client = client,
                    pairingUrl = "http://192.168.1.92:48291",
                    requestId = "req-same",
                    policy =
                        LocalPairingPollingPolicy(
                            deadlineMillis = 10_000,
                            pollIntervalMillis = 100,
                            maximumRetryDelayMillis = 800,
                        ),
                    monotonicMillis = { testScheduler.currentTime },
                    onEvent = events::add,
                )

            assertEquals(
                LocalPairingRequestStatus.Approved,
                assertIs<LocalPairingPollingOutcome.Terminal>(outcome).response.status,
            )
            val retry = assertIs<LocalPairingPollingEvent.RetryingConnection>(events.single())
            assertEquals(1, retry.consecutiveFailureCount)
            assertEquals(200, retry.nextDelayMillis)
            assertEquals(2, client.statusAttempts)
            assertEquals(0, client.requestAttempts)
        }

    @Test
    fun pollingPropagatesCancellationInsteadOfReportingFailure() =
        runTest {
            val client =
                ScriptedStatusPairingClient(
                    ArrayDeque(listOf(Result.failure(CancellationException("screen closed")))),
                )

            assertFailsWith<CancellationException> {
                awaitLocalPairingDecision(
                    client = client,
                    pairingUrl = "http://192.168.1.92:48291",
                    requestId = "req-cancelled",
                    policy = LocalPairingPollingPolicy(1_000, 100, 800),
                    monotonicMillis = { testScheduler.currentTime },
                )
            }
            assertEquals(0, client.requestAttempts)
        }

    private fun pairingPayload(agentFingerprint: String = "SHA256:desktop") =
        LocalPairingPayload(
            host = "192.168.1.92",
            port = 48291,
            pairingCode = "123456",
            agentFingerprint = agentFingerprint,
            timestampEpochMillis = 1,
            expiresAtEpochMillis = 60_001,
            nonce = "nonce",
        )

    private fun status(
        status: LocalPairingRequestStatus,
        message: String,
    ) = LocalPairingStatusResponse(
        status = status,
        message = message,
    )
}

private class FakePairingClient(
    private val payloads: Map<String, Result<LocalPairingPayload>>,
) : LocalPairingClient {
    val fetchAttempts = mutableListOf<String>()

    override suspend fun fetchPayload(pairingUrl: String): LocalPairingPayload {
        fetchAttempts += pairingUrl
        return checkNotNull(payloads[pairingUrl]) { "Unexpected pairing endpoint $pairingUrl" }.getOrThrow()
    }

    override suspend fun requestPairing(
        pairingUrl: String,
        request: LocalPairingRequestBody,
    ): LocalPairingResponse = error("Not used by endpoint discovery")

    override suspend fun getPairingStatus(
        pairingUrl: String,
        requestId: String,
    ): LocalPairingStatusResponse = error("Not used by endpoint discovery")
}

private class ScriptedStatusPairingClient(
    private val statuses: ArrayDeque<Result<LocalPairingStatusResponse>>,
) : LocalPairingClient {
    var requestAttempts: Int = 0
        private set
    var statusAttempts: Int = 0
        private set

    override suspend fun fetchPayload(pairingUrl: String): LocalPairingPayload = error("Not used by status polling")

    override suspend fun requestPairing(
        pairingUrl: String,
        request: LocalPairingRequestBody,
    ): LocalPairingResponse {
        requestAttempts += 1
        error("Status polling must not submit a pairing request")
    }

    override suspend fun getPairingStatus(
        pairingUrl: String,
        requestId: String,
    ): LocalPairingStatusResponse {
        statusAttempts += 1
        return statuses.removeFirstOrNull()?.getOrThrow() ?: error("No scripted status response left")
    }
}
