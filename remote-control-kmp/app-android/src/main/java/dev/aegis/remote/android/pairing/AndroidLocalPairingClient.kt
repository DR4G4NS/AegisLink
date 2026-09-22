package dev.aegis.remote.android.pairing

import dev.aegis.remote.core.pairing.LocalPairingClient
import dev.aegis.remote.core.pairing.LocalPairingPayload
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingResponse
import dev.aegis.remote.core.pairing.LocalPairingStatusResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class AndroidLocalPairingClient(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
    private val submitReadTimeoutMillis: Int = SUBMIT_READ_TIMEOUT_MS,
) : LocalPairingClient {
    @Volatile
    private var expectedServerFingerprint: String? = null

    @Volatile
    private var pinnedSslSocketFactory: javax.net.ssl.SSLSocketFactory? = null

    @Volatile
    private var pinnedHostnameVerifier: javax.net.ssl.HostnameVerifier? = null

    override fun pinServerFingerprint(fingerprint: String): LocalPairingClient =
        apply {
            val normalized = normalizeCertificateFingerprint(fingerprint)
            if (expectedServerFingerprint == normalized && pinnedSslSocketFactory != null) return@apply
            expectedServerFingerprint = normalized
            val trustManager = pinnedCertificateTrustManager(normalized)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            pinnedSslSocketFactory = context.socketFactory
            pinnedHostnameVerifier =
                javax.net.ssl.HostnameVerifier { _, session ->
                    val certificate = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
                    matchesCertificateFingerprint(certificate.encoded, normalized)
                }
        }

    override suspend fun fetchPayload(pairingUrl: String): LocalPairingPayload =
        withContext(Dispatchers.IO) {
            get(endpoint(pairingUrl, "/pairing/payload"))
        }

    override suspend fun requestPairing(
        pairingUrl: String,
        request: LocalPairingRequestBody,
    ): LocalPairingResponse =
        withContext(Dispatchers.IO) {
            post(endpoint(pairingUrl, "/pairing/requests"), json.encodeToString(request))
        }

    override suspend fun getPairingStatus(
        pairingUrl: String,
        requestId: String,
    ): LocalPairingStatusResponse =
        withContext(Dispatchers.IO) {
            require(requestId.matches(REQUEST_ID_PATTERN)) { "Invalid local pairing request id" }
            get(endpoint(pairingUrl, "/pairing/requests/$requestId"))
        }

    suspend fun registerLan(
        pairingUrl: String,
        request: dev.aegis.remote.core.pairing.LanRegisterRequest,
    ): dev.aegis.remote.core.pairing.LanRegisterResponse =
        withContext(Dispatchers.IO) {
            check(expectedServerFingerprint != null) { "LOCAL_TLS_FINGERPRINT_REQUIRED" }
            post(endpoint(pairingUrl, "/lan/register", allowPublicAddress = true), json.encodeToString(request))
        }

    private inline fun <reified T> get(uri: URI): T {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.configure(readTimeoutMillis)
            connection.readJson()
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <reified T> post(
        uri: URI,
        body: String,
    ): T {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            // Creating a request is intentionally not retried: once the body reaches the PC,
            // retrying it could create a second approval card. Keep the original response
            // alive longer instead so a briefly busy desktop can return the one request id.
            connection.configure(submitReadTimeoutMillis)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { output -> output.write(bytes) }
            connection.readJson(decodeClientErrorBody = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.configure(responseTimeoutMillis: Int) {
        connectTimeout = connectTimeoutMillis
        readTimeout = responseTimeoutMillis
        instanceFollowRedirects = false
        useCaches = false
        setRequestProperty("Accept", "application/json")
        if (this is HttpsURLConnection) configurePinnedTls()
    }

    private fun HttpsURLConnection.configurePinnedTls() {
        sslSocketFactory = pinnedSslSocketFactory ?: error("LOCAL_TLS_FINGERPRINT_REQUIRED")
        hostnameVerifier = pinnedHostnameVerifier ?: error("LOCAL_TLS_FINGERPRINT_REQUIRED")
    }

    private inline fun <reified T> HttpURLConnection.readJson(decodeClientErrorBody: Boolean = false): T {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use(::readBoundedText).orEmpty()
        if (code !in 200..299) {
            if (decodeClientErrorBody && code in 400..499 && body.isNotBlank()) {
                runCatching { json.decodeFromString<T>(body) }.getOrNull()?.let { structuredError ->
                    return structuredError
                }
            }
            val serverMessage = jsonMessage(body)
            val message =
                when {
                    code in 300..399 -> "Pairing server redirects are not allowed"
                    !serverMessage.isNullOrBlank() -> serverMessage
                    body.isNotBlank() -> body.take(256)
                    else -> "Pairing server returned an empty error response"
                }
            throw LocalPairingHttpException(code, message)
        }
        require(body.isNotBlank()) { "Pairing server returned an empty response" }
        return runCatching { json.decodeFromString<T>(body) }
            .getOrElse { error -> throw IllegalStateException("Pairing server returned invalid JSON", error) }
    }

    private fun jsonMessage(body: String): String? =
        runCatching {
            json
                .parseToJsonElement(body)
                .jsonObject["message"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()

    private fun endpoint(
        baseUrl: String,
        path: String,
        allowPublicAddress: Boolean = false,
    ): URI {
        val base = normalizeLocalPairingBaseUrl(baseUrl, allowPublicAddress = allowPublicAddress && expectedServerFingerprint != null)
        return URI(base.scheme, null, base.host, base.port, path, null, null)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val SUBMIT_READ_TIMEOUT_MS = 30_000
        private const val MAX_RESPONSE_CHARS = 64 * 1024
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")

        private fun readBoundedText(reader: java.io.BufferedReader): String {
            val buffer = CharArray(2_048)
            val result = StringBuilder()
            while (result.length <= MAX_RESPONSE_CHARS) {
                val count = reader.read(buffer)
                if (count < 0) break
                result.append(buffer, 0, count)
            }
            require(result.length <= MAX_RESPONSE_CHARS) { "Pairing server response is too large" }
            return result.toString()
        }
    }
}

internal fun matchesCertificateFingerprint(
    certificateDer: ByteArray,
    expectedBase64: String,
): Boolean {
    val expected = decodeCertificateFingerprint(expectedBase64) ?: return false
    val actual = MessageDigest.getInstance("SHA-256").digest(certificateDer)
    return MessageDigest.isEqual(expected, actual)
}

internal fun pinnedCertificateTrustManager(fingerprint: String): X509TrustManager {
    val expected = normalizeCertificateFingerprint(fingerprint)
    return object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            val certificate = chain.firstOrNull() ?: throw java.security.cert.CertificateException("LOCAL_TLS_CERTIFICATE_MISSING")
            if (!matchesCertificateFingerprint(certificate.encoded, expected)) {
                throw java.security.cert.CertificateException("LOCAL_TLS_FINGERPRINT_MISMATCH")
            }
        }
    }
}

internal fun normalizeCertificateFingerprint(fingerprint: String): String {
    val normalized = fingerprint.trim().removePrefix("SHA256:")
    require(decodeCertificateFingerprint(normalized)?.size == 32) {
        "Invalid local TLS certificate fingerprint"
    }
    return normalized
}

private fun decodeCertificateFingerprint(value: String): ByteArray? =
    runCatching { Base64.getDecoder().decode(value) }
        .recoverCatching { Base64.getUrlDecoder().decode(value) }
        .getOrNull()

class LocalPairingHttpException(
    val statusCode: Int,
    val serverMessage: String,
) : IllegalStateException("Pairing failed (HTTP $statusCode): $serverMessage")

internal data class LocalPairingPollingPolicy(
    val deadlineMillis: Long = 240_000L,
    val pollIntervalMillis: Long = 1_000L,
    val maximumRetryDelayMillis: Long = 4_000L,
) {
    init {
        require(deadlineMillis > 0) { "Pairing polling deadline must be positive" }
        require(pollIntervalMillis > 0) { "Pairing polling interval must be positive" }
        require(maximumRetryDelayMillis >= pollIntervalMillis) {
            "Maximum pairing retry delay must not be shorter than the polling interval"
        }
    }
}

internal sealed interface LocalPairingPollingEvent {
    data class Pending(
        val response: LocalPairingStatusResponse,
    ) : LocalPairingPollingEvent

    data class RetryingConnection(
        val failure: Throwable,
        val consecutiveFailureCount: Int,
        val nextDelayMillis: Long,
    ) : LocalPairingPollingEvent
}

internal sealed interface LocalPairingPollingOutcome {
    data class Terminal(
        val response: LocalPairingStatusResponse,
    ) : LocalPairingPollingOutcome

    data class DeadlineExceeded(
        val lastNetworkFailure: Throwable?,
    ) : LocalPairingPollingOutcome
}

/**
 * Polls one already-created desktop request until it reaches a terminal decision.
 *
 * Only idempotent status reads are retried. A transient timeout therefore never submits a
 * duplicate approval request. Cancellation is always propagated so leaving the screen cannot
 * be misreported as a pairing failure.
 */
internal suspend fun awaitLocalPairingDecision(
    client: LocalPairingClient,
    pairingUrl: String,
    requestId: String,
    policy: LocalPairingPollingPolicy = LocalPairingPollingPolicy(),
    monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    onEvent: (LocalPairingPollingEvent) -> Unit = {},
): LocalPairingPollingOutcome {
    val startedAt = monotonicMillis()
    var nextDelayMillis = policy.pollIntervalMillis
    var consecutiveFailures = 0
    var lastNetworkFailure: Throwable? = null

    while (true) {
        val elapsed = (monotonicMillis() - startedAt).coerceAtLeast(0L)
        val remaining = policy.deadlineMillis - elapsed
        if (remaining <= 0L) {
            return LocalPairingPollingOutcome.DeadlineExceeded(lastNetworkFailure)
        }
        delay(nextDelayMillis.coerceAtMost(remaining))

        val response =
            try {
                client.getPairingStatus(pairingUrl, requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!failure.isTransientLocalPairingFailure()) throw failure
                lastNetworkFailure = failure
                consecutiveFailures += 1
                nextDelayMillis =
                    (policy.pollIntervalMillis shl consecutiveFailures.coerceAtMost(5))
                        .coerceAtMost(policy.maximumRetryDelayMillis)
                onEvent(
                    LocalPairingPollingEvent.RetryingConnection(
                        failure = failure,
                        consecutiveFailureCount = consecutiveFailures,
                        nextDelayMillis = nextDelayMillis,
                    ),
                )
                continue
            }

        consecutiveFailures = 0
        lastNetworkFailure = null
        nextDelayMillis = policy.pollIntervalMillis
        if (response.status == dev.aegis.remote.core.pairing.LocalPairingRequestStatus.Pending) {
            onEvent(LocalPairingPollingEvent.Pending(response))
            continue
        }
        return LocalPairingPollingOutcome.Terminal(response)
    }
}

internal fun Throwable.isTransientLocalPairingFailure(): Boolean =
    when (this) {
        is IOException -> {
            true
        }

        is LocalPairingHttpException -> {
            statusCode == 408 || statusCode == 425 ||
                statusCode == 429 || statusCode in 500..599
        }

        else -> {
            cause?.takeIf { it !== this }?.isTransientLocalPairingFailure() == true
        }
    }

internal data class VerifiedLocalPairingEndpoint(
    val pairingUrl: String,
    val payload: LocalPairingPayload,
)

internal suspend fun resolveVerifiedLocalPairingEndpoint(
    pairingUrls: List<String>,
    expectedAgentFingerprint: String,
    expectedPairingCode: String,
    client: LocalPairingClient,
): VerifiedLocalPairingEndpoint {
    val pinnedClient = client.pinServerFingerprint(expectedAgentFingerprint)
    val candidates =
        pairingUrls
            .map { normalizeLocalPairingBaseUrl(it).toString() }
            .distinct()
    require(candidates.isNotEmpty()) { "Desktop pairing URL is required" }
    var lastConnectionFailure: Throwable? = null
    var identityFailure: Throwable? = null
    candidates.forEach { candidate ->
        val payload =
            try {
                pinnedClient.fetchPayload(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastConnectionFailure = error
                return@forEach
            }
        if (payload.agentFingerprint != expectedAgentFingerprint) {
            identityFailure =
                IllegalArgumentException(
                    "Desktop agent fingerprint does not match the scanned QR code at $candidate",
                )
            return@forEach
        }
        if (payload.pairingCode != expectedPairingCode) {
            identityFailure =
                IllegalArgumentException(
                    "Desktop pairing code changed at $candidate. Scan the current QR code again",
                )
            return@forEach
        }
        return VerifiedLocalPairingEndpoint(candidate, payload)
    }
    throw identityFailure ?: IllegalStateException(
        "Could not reach the PC using any of the ${candidates.size} local network addresses in the QR code",
        lastConnectionFailure,
    )
}

internal fun normalizeLocalPairingBaseUrl(
    rawUrl: String,
    allowPublicAddress: Boolean = false,
): URI {
    val trimmed = rawUrl.trim().trimEnd('/')
    require(trimmed.isNotBlank()) { "Desktop pairing URL is required" }
    val withScheme = if (SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    val uri =
        runCatching { URI(withScheme) }
            .getOrElse { error -> throw IllegalArgumentException("Invalid desktop pairing URL", error) }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https") { "Local pairing URL must use HTTPS" }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Local pairing URL must not contain credentials, a query, or a fragment"
    }
    require(uri.path.isNullOrBlank() || uri.path == "/") { "Local pairing URL must not contain a path" }
    val host = requireNotNull(uri.host) { "Local pairing URL is missing a host" }
    require(uri.port == -1 || uri.port in 1..65_535) { "Local pairing URL has an invalid port" }
    // HTTPS is required above; an absent port always resolves to its standard port.
    val port = if (uri.port == -1) 443 else uri.port
    val addresses =
        runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { error -> throw IllegalArgumentException("Could not resolve the desktop pairing host", error) }
    require(
        addresses.isNotEmpty() && addresses.none { it.isAnyLocalAddress || it.isMulticastAddress } &&
            (allowPublicAddress || addresses.all(::isAllowedLocalAddress)),
    ) {
        "Local pairing is allowed only on a private LAN or loopback address"
    }
    return URI(scheme, null, host, port, null, null, null)
}

private fun isAllowedLocalAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isMulticastAddress) return false
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
    if (address is Inet6Address) {
        val first = address.address.first().toInt() and 0xff
        return first and 0xfe == 0xfc
    }
    if (address is Inet4Address) {
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }
    return false
}

private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
