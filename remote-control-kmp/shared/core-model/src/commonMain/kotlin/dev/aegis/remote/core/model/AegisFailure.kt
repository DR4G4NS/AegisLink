package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

/**
 * A structured, non-secret description of a failure that can cross module and
 * protocol boundaries without losing its causal context.
 */
@Serializable
data class AegisFailure(
    val code: String,
    val component: String,
    val operation: String,
    val stage: String,
    val category: FailureCategory,
    val summary: String,
    val technicalCause: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val retryable: Boolean,
    val correlationId: String,
    val evidenceRef: String? = null,
    val nextAction: String? = null,
    val underlyingType: String? = null,
) {
    init {
        require(FailureCodeNamespaces.isSupported(code)) { "Unsupported Aegis failure code: $code" }
        require(component.isNotBlank())
        require(operation.isNotBlank())
        require(stage.isNotBlank())
        require(summary.isNotBlank())
        require(correlationId.isNotBlank())
        require(technicalCause == null || technicalCause.isNotBlank())
        require(expected == null || expected.isNotBlank())
        require(actual == null || actual.isNotBlank())
        require(evidenceRef == null || evidenceRef.isNotBlank())
        require(nextAction == null || nextAction.isNotBlank())
        require(underlyingType == null || underlyingType.isNotBlank())
    }
}

@Serializable
enum class FailureCategory {
    INVALID_STATE,
    UNSUPPORTED_CAPABILITY,
    AUTHENTICATION,
    AUTHORIZATION,
    CRYPTOGRAPHY,
    NETWORK,
    TIMEOUT,
    PROTOCOL,
    DEPENDENCY,
    WINDOWS_API,
    ANDROID_API,
    CONFIGURATION,
    DATA,
    RESOURCE_EXHAUSTION,
    EXTERNAL_SERVICE,
    INTERNAL_BUG,
    CAUSE_UNCONFIRMED,
}

/** Stable namespaces accepted by [AegisFailure]. */
object FailureCodeNamespaces {
    const val IDENTITY = "IDN"
    const val CRYPTOGRAPHY = "CRY"
    const val END_TO_END = "E2E"
    const val RELAY = "REL"
    const val SESSION = "SES"
    const val WEBRTC = "RTC"
    const val CAPTURE = "CAP"
    const val INPUT = "INP"
    const val CLIPBOARD = "CLP"
    const val SSH = "SSH"
    const val SFTP = "SFT"
    const val QR_PAIRING = "QRP"
    const val WAKE_ON_LAN = "WOL"
    const val WINDOWS = "WIN"
    const val ANDROID = "AND"
    const val DATABASE = "DB"
    const val DEPENDENCY = "DEP"
    const val PACKAGE = "PKG"
    const val CONFIGURATION = "CFG"
    const val TEST = "TST"

    val all: Set<String> =
        setOf(
            IDENTITY,
            CRYPTOGRAPHY,
            END_TO_END,
            RELAY,
            SESSION,
            WEBRTC,
            CAPTURE,
            INPUT,
            CLIPBOARD,
            SSH,
            SFTP,
            QR_PAIRING,
            WAKE_ON_LAN,
            WINDOWS,
            ANDROID,
            DATABASE,
            DEPENDENCY,
            PACKAGE,
            CONFIGURATION,
            TEST,
        )

    fun isSupported(code: String): Boolean {
        val separator = code.indexOf('-')
        if (separator <= 0 || separator != code.lastIndexOf('-')) return false
        if (code.substring(0, separator) !in all) return false
        val number = code.substring(separator + 1)
        return number.length == FAILURE_NUMBER_LENGTH && number.all { it in '0'..'9' }
    }

    private const val FAILURE_NUMBER_LENGTH = 4
}

/**
 * Canonical codes currently emitted by shared session/protocol infrastructure.
 * Names are deliberately domain-qualified so callers do not depend on prose.
 */
object AegisFailureCodes {
    const val IDENTITY_DEVICE_REVOKED = "IDN-1003"
    const val IDENTITY_AUTHENTICATION_FAILED = "IDN-1004"

    const val E2EE_CRYPTO_SUITE_NOT_ALLOWED = "E2E-2003"
    const val E2EE_DOWNGRADE_DETECTED = "E2E-2004"

    const val RELAY_ORIGIN_MISMATCH = "REL-3001"
    const val RELAY_RECONNECT_UNAVAILABLE = "REL-3002"
    const val RELAY_RECONNECT_APPROVAL_TIMEOUT = "REL-3003"

    const val SESSION_NOT_FOUND = "SES-1001"
    const val SESSION_REPLAY = "SES-1002"
    const val SESSION_CAPACITY_EXCEEDED = "SES-1003"
    const val SESSION_STALE_GENERATION = "SES-1004"
    const val SESSION_EVENT_REPLAY = "SES-1005"
    const val SESSION_INVALID_TRANSITION = "SES-1006"
    const val SESSION_NOT_TERMINAL = "SES-1007"
    const val SESSION_WRONG_LOCAL_DEVICE = "SES-1008"
    const val SESSION_EXPIRED = "SES-1009"
    const val SESSION_NOT_YET_VALID = "SES-1010"
    const val SESSION_REMOTE_ACTION_NOT_AUTHORIZED = "SES-1011"
    const val SESSION_INVALID_PROTOCOL_MESSAGE = "SES-1012"
    const val SESSION_REQUEST_DEPENDENCY_FAILED = "SES-1013"
    const val SESSION_PROTOCOL_SKEW = "SES-1014"
    const val SESSION_PROTOCOL_PROCESSING_FAILED = "SES-1099"

    const val WEBRTC_START_FAILED = "RTC-4001"
    const val WEBRTC_CONNECTION_FAILED = "RTC-4002"
    const val WEBRTC_NETWORK_OPERATION_FAILED = "RTC-4003"
    const val WEBRTC_ICE_RESTART_FAILED = "RTC-4004"
    const val WEBRTC_ICE_BUFFER_OVERFLOW = "RTC-4005"
    const val WEBRTC_TURN_REFRESH_FAILED = "RTC-4006"

    const val CAPTURE_BACKEND_UNAVAILABLE = "CAP-5001"
    const val CAPTURE_SOURCE_UNAVAILABLE = "CAP-5002"
    const val CAPTURE_UNKNOWN_SOURCE = "CAP-5003"
    const val CAPTURE_SESSION_CLOSED = "CAP-5004"
    const val CAPTURE_TOPOLOGY_MISMATCH = "CAP-5005"
    const val CAPTURE_DUPLICATE_SOURCE_ID = "CAP-5006"
    const val CAPTURE_FRAME_SOURCE_INCOMPATIBLE = "CAP-5007"

    const val SSH_HOST_KEY_CHANGED = "SSH-7001"
    const val SSH_CONNECTION_FAILED = "SSH-7002"
    const val SSH_CREDENTIAL_MISSING = "SSH-7003"
    const val SSH_HOST_KEY_MISSING = "SSH-7004"
    const val SSH_USERNAME_MISSING = "SSH-7005"
    const val SSH_AUTHENTICATION_FAILED = "SSH-7006"
    const val SSH_SHELL_FAILED = "SSH-7007"
    const val SSH_OPERATION_TIMEOUT = "SSH-7008"
    const val SSH_SESSION_CLOSED = "SSH-7009"
    const val SSH_INPUT_LIMIT_EXCEEDED = "SSH-7010"
    const val SSH_OPERATION_CANCELLED = "SSH-7011"
    const val SSH_PTY_SIZE_INVALID = "SSH-7012"

    const val SFTP_PATH_REJECTED = "SFT-7101"
    const val SFTP_ROOT_ESCAPE_REJECTED = "SFT-7102"
    const val SFTP_SYMLINK_REJECTED = "SFT-7103"
    const val SFTP_OPERATION_FAILED = "SFT-7104"
    const val SFTP_TRANSFER_FAILED = "SFT-7105"
    const val SFTP_TRANSFER_CANCELLED = "SFT-7106"
    const val SFTP_RESUME_UNSUPPORTED = "SFT-7107"
    const val SFTP_CONCURRENT_OPERATION = "SFT-7108"
    const val SFTP_ROOT_DISCOVERY_FAILED = "SFT-7109"
    const val SFTP_SESSION_CLOSED = "SFT-7110"
    const val WAKE_ON_LAN_ADAPTER_UNSUPPORTED = "WOL-7201"
    const val WAKE_ON_LAN_SEND_FAILED = "WOL-7202"
    const val WAKE_ON_LAN_CAPABILITY_UNKNOWN = "WOL-7203"
    const val WAKE_ON_LAN_NOT_CONFIRMED = "WOL-7204"
    const val DEPENDENCY_CAPABILITY_UNAVAILABLE = "DEP-8002"
}
