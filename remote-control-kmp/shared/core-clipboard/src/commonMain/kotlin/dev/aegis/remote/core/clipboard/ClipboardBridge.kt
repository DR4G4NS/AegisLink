package dev.aegis.remote.core.clipboard

import dev.aegis.remote.core.model.ClipboardPayload
import kotlinx.coroutines.flow.Flow

enum class ClipboardSyncPolicy {
    Disabled,
    Manual,
    AskEveryTime,
    Automatic,
}

enum class ClipboardSyncDirection {
    AndroidToDesktop,
    DesktopToAndroid,
}

data class ClipboardSyncRequest(
    val policy: ClipboardSyncPolicy = ClipboardSyncPolicy.Manual,
    val direction: ClipboardSyncDirection,
    val payload: ClipboardPayload,
    val userConfirmed: Boolean = false,
)

sealed interface ClipboardSyncDecision {
    data object Allowed : ClipboardSyncDecision

    data class RequiresConfirmation(
        val reason: String,
        val code: String = CLIPBOARD_CONFIRMATION_REQUIRED_CODE,
    ) : ClipboardSyncDecision

    data class Blocked(
        val reason: String,
        val code: String = CLIPBOARD_POLICY_BLOCKED_CODE,
    ) : ClipboardSyncDecision
}

class ClipboardSyncPolicyEngine(
    private val maxAutomaticTextBytes: Int = 8_192,
    private val maxTextBytes: Int = MAX_CLIPBOARD_TEXT_BYTES,
) {
    init {
        require(maxAutomaticTextBytes > 0)
        require(maxTextBytes >= maxAutomaticTextBytes)
    }

    fun evaluate(request: ClipboardSyncRequest): ClipboardSyncDecision =
        when (request.policy) {
            ClipboardSyncPolicy.Disabled -> ClipboardSyncDecision.Blocked("Clipboard sync is disabled")
            ClipboardSyncPolicy.Manual -> evaluateManual(request)
            ClipboardSyncPolicy.AskEveryTime -> evaluateAskEveryTime(request)
            ClipboardSyncPolicy.Automatic -> evaluateAutomatic(request)
        }

    private fun evaluateManual(request: ClipboardSyncRequest): ClipboardSyncDecision =
        if (request.userConfirmed) {
            evaluatePayloadSafety(request.payload, allowLongText = true) ?: ClipboardSyncDecision.Allowed
        } else {
            ClipboardSyncDecision.RequiresConfirmation("Manual clipboard sync requires an explicit user action")
        }

    private fun evaluateAskEveryTime(request: ClipboardSyncRequest): ClipboardSyncDecision =
        if (request.userConfirmed) {
            evaluatePayloadSafety(request.payload, allowLongText = true) ?: ClipboardSyncDecision.Allowed
        } else {
            ClipboardSyncDecision.RequiresConfirmation("Clipboard sync requires confirmation for every transfer")
        }

    private fun evaluateAutomatic(request: ClipboardSyncRequest): ClipboardSyncDecision = evaluatePayloadSafety(request.payload, allowLongText = false) ?: ClipboardSyncDecision.Allowed

    private fun evaluatePayloadSafety(
        payload: ClipboardPayload,
        allowLongText: Boolean,
    ): ClipboardSyncDecision.Blocked? =
        when (payload) {
            is ClipboardPayload.Text -> {
                val text = payload.value
                val byteCount = text.encodeToByteArray().size
                when {
                    text.isBlank() -> {
                        ClipboardSyncDecision.Blocked("Empty clipboard text is not synced")
                    }

                    byteCount > maxTextBytes -> {
                        ClipboardSyncDecision.Blocked(
                            reason = "Clipboard text exceeds the absolute sync byte limit",
                            code = CLIPBOARD_PAYLOAD_TOO_LARGE_CODE,
                        )
                    }

                    !allowLongText && byteCount > maxAutomaticTextBytes -> {
                        ClipboardSyncDecision.Blocked(
                            reason = "Clipboard text exceeds the automatic sync byte limit",
                            code = CLIPBOARD_PAYLOAD_TOO_LARGE_CODE,
                        )
                    }

                    text.looksSensitive() -> {
                        ClipboardSyncDecision.Blocked(
                            "Clipboard text looks sensitive and must not be synced automatically",
                        )
                    }

                    else -> {
                        null
                    }
                }
            }
        }

    private fun String.looksSensitive(): Boolean {
        val normalized = lowercase()
        if ("-----begin " in normalized && " private key-----" in normalized) return true
        if (jwtPattern.matches(trim())) return true

        return sensitiveMarkers.any { marker -> marker in normalized }
    }

    private companion object {
        val jwtPattern = Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")
        val sensitiveMarkers =
            listOf(
                "password=",
                "passwd=",
                "pwd=",
                "token=",
                "access_token",
                "refresh_token",
                "api_key",
                "apikey",
                "secret=",
                "bearer ",
            )
    }
}

interface ClipboardBridge {
    val changes: Flow<ClipboardPayload>

    suspend fun read(): ClipboardPayload?

    suspend fun write(payload: ClipboardPayload)

    /** Marks an inbound remote value before writing so native change events cannot echo it. */
    suspend fun writeFromRemote(payload: ClipboardPayload) = write(payload)

    /** Called immediately before forwarding a native change to the remote peer. */
    fun shouldForwardChange(payload: ClipboardPayload): Boolean = true
}

/**
 * Short-lived, in-memory loop and duplicate suppression. Only fixed-size
 * fingerprints are retained; clipboard text is never logged or persisted.
 */
class ClipboardLoopGuard(
    private val clock: () -> Long,
    private val remoteEchoSuppressionMillis: Long = 2_000L,
    private val duplicateSuppressionMillis: Long = 500L,
) {
    private val remoteWrites = mutableListOf<FingerprintExpiry>()
    private var lastForwarded: FingerprintExpiry? = null

    init {
        require(remoteEchoSuppressionMillis > 0)
        require(duplicateSuppressionMillis > 0)
    }

    fun markRemoteWrite(payload: ClipboardPayload) {
        val fingerprint = payload.fingerprint()
        val now = clock()
        remoteWrites.removeAll { now > it.expiresAtEpochMillis || it.fingerprint == fingerprint }
        remoteWrites += FingerprintExpiry(fingerprint, expiresAt(now, remoteEchoSuppressionMillis))
        while (remoteWrites.size > MAX_REMOTE_FINGERPRINTS) remoteWrites.removeAt(0)
    }

    fun cancelRemoteWrite(payload: ClipboardPayload) {
        val fingerprint = payload.fingerprint()
        remoteWrites.removeAll { it.fingerprint == fingerprint }
    }

    fun shouldForwardLocalChange(payload: ClipboardPayload): Boolean {
        val now = clock()
        val fingerprint = payload.fingerprint()
        if (isRemoteEcho(payload, now)) return false
        lastForwarded = lastForwarded?.takeIf { now <= it.expiresAtEpochMillis }
        if (lastForwarded?.fingerprint == fingerprint) return false
        lastForwarded = FingerprintExpiry(fingerprint, expiresAt(now, duplicateSuppressionMillis))
        return true
    }

    fun isRemoteEcho(payload: ClipboardPayload): Boolean = isRemoteEcho(payload, clock())

    fun clear() {
        remoteWrites.clear()
        lastForwarded = null
    }

    private fun expiresAt(
        now: Long,
        durationMillis: Long,
    ): Long = if (now > Long.MAX_VALUE - durationMillis) Long.MAX_VALUE else now + durationMillis

    private fun isRemoteEcho(
        payload: ClipboardPayload,
        now: Long,
    ): Boolean {
        val fingerprint = payload.fingerprint()
        remoteWrites.removeAll { now > it.expiresAtEpochMillis }
        return remoteWrites.any { it.fingerprint == fingerprint }
    }

    private data class FingerprintExpiry(
        val fingerprint: ClipboardFingerprint,
        val expiresAtEpochMillis: Long,
    )
}

private data class ClipboardFingerprint(
    val byteCount: Int,
    val hash: Long,
)

private fun ClipboardPayload.fingerprint(): ClipboardFingerprint =
    when (this) {
        is ClipboardPayload.Text -> {
            val bytes = value.encodeToByteArray()
            var hash = FNV_OFFSET_BASIS
            bytes.forEach { byte ->
                hash = hash xor (byte.toLong() and 0xffL)
                hash *= FNV_PRIME
            }
            ClipboardFingerprint(bytes.size, hash)
        }
    }

const val MAX_CLIPBOARD_TEXT_BYTES: Int = 32 * 1_024
const val CLIPBOARD_CONFIRMATION_REQUIRED_CODE = "CLP-1007"
const val CLIPBOARD_PAYLOAD_TOO_LARGE_CODE = "CLP-1002"
const val CLIPBOARD_POLICY_BLOCKED_CODE = "CLP-1003"

private const val FNV_OFFSET_BASIS = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L
private const val MAX_REMOTE_FINGERPRINTS = 8
