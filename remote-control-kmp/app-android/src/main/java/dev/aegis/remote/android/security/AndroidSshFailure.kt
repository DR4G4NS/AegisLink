@file:Suppress("MatchingDeclarationName")

package dev.aegis.remote.android.security

import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.FailureCategory
import kotlinx.coroutines.CancellationException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

class AndroidRemoteOperationException(
    val failure: AegisFailure,
    cause: Throwable? = null,
) : Exception(failure.toDisplayMessage(), cause)

@Suppress("CyclomaticComplexMethod")
internal fun sshOperationFailure(
    operation: String,
    stage: String,
    error: Throwable,
    pinObservation: PinnedHostKeyObservation? = null,
): Throwable {
    if (error is CancellationException || error is AndroidRemoteOperationException) return error

    val mismatch = pinObservation?.mismatch
    val code: String
    val category: FailureCategory
    val retryable: Boolean
    val summary: String
    val expected: String?
    val actual: String?
    when {
        mismatch != null -> {
            code = AegisFailureCodes.SSH_HOST_KEY_CHANGED
            category = FailureCategory.CRYPTOGRAPHY
            retryable = false
            summary = "The SSH host key does not match the key pinned during pairing"
            expected = "SHA256:${pinObservation.expectedFingerprint}"
            actual = "SHA256:$mismatch"
        }

        error.hasCause<UserAuthException>() ||
            error.sshDisconnectReason() == DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE -> {
            code = AegisFailureCodes.SSH_AUTHENTICATION_FAILED
            category = FailureCategory.AUTHENTICATION
            retryable = false
            summary = "SSH public-key authentication failed"
            expected = "The private key generated during pairing is authorized for this Windows account"
            actual = null
        }

        error.hasCause<SocketTimeoutException>() -> {
            code = AegisFailureCodes.SSH_OPERATION_TIMEOUT
            category = FailureCategory.TIMEOUT
            retryable = true
            summary = "The SSH operation timed out"
            expected = null
            actual = null
        }

        error.hasCause<ConnectException>() ||
            error.hasCause<NoRouteToHostException>() ||
            error.hasCause<UnknownHostException>() ||
            error.hasCause<SocketException>() ||
            error.hasCause<EOFException>() -> {
            code = AegisFailureCodes.SSH_CONNECTION_FAILED
            category = FailureCategory.NETWORK
            retryable = true
            summary = "The SSH endpoint could not be reached"
            expected = null
            actual = null
        }

        stage == "shell" || stage == "pty" -> {
            code = AegisFailureCodes.SSH_SHELL_FAILED
            category = FailureCategory.PROTOCOL
            retryable = false
            summary = "The SSH shell could not be opened"
            expected = null
            actual = null
        }

        else -> {
            code = AegisFailureCodes.SSH_CONNECTION_FAILED
            category = FailureCategory.PROTOCOL
            retryable = error.sshDisconnectReason() == DisconnectReason.CONNECTION_LOST
            summary = "The SSH operation failed"
            expected = null
            actual = null
        }
    }
    return remoteOperationFailure(
        code = code,
        component = "android-sshj",
        operation = operation,
        stage = stage,
        category = category,
        summary = summary,
        error = error,
        expected = expected,
        actual = actual,
        retryable = retryable,
        nextAction =
            when (code) {
                AegisFailureCodes.SSH_HOST_KEY_CHANGED -> "Do not bypass the warning. Verify or re-pair the Windows host."
                AegisFailureCodes.SSH_AUTHENTICATION_FAILED -> "Repair Aegis OpenSSH enrollment or revoke and pair this device again."
                else -> "Verify the selected route, Aegis OpenSSH service and firewall state."
            },
    )
}

internal fun sftpOperationFailure(
    operation: String,
    stage: String,
    error: Throwable,
    code: String = AegisFailureCodes.SFTP_OPERATION_FAILED,
    category: FailureCategory = FailureCategory.PROTOCOL,
    retryable: Boolean = false,
    expected: String? = null,
    actual: String? = null,
    nextAction: String = "Verify the authorized home path, Windows ACLs and the Aegis OpenSSH SFTP subsystem.",
): Throwable {
    if (error is CancellationException || error is AndroidRemoteOperationException) return error
    return remoteOperationFailure(
        code = code,
        component = "android-sshj-sftp",
        operation = operation,
        stage = stage,
        category = category,
        summary =
            when (code) {
                AegisFailureCodes.SFTP_ROOT_ESCAPE_REJECTED -> "The remote path escaped the authorized SFTP root"
                AegisFailureCodes.SFTP_SYMLINK_REJECTED -> "A symlink cannot be traversed or used as a transfer target"
                AegisFailureCodes.SFTP_TRANSFER_CANCELLED -> "The SFTP transfer was physically cancelled"
                AegisFailureCodes.SFTP_TRANSFER_FAILED -> "The SFTP transfer failed"
                AegisFailureCodes.SFTP_ROOT_DISCOVERY_FAILED -> "The canonical SFTP account home could not be established"
                else -> "The SFTP operation failed"
            },
        error = error,
        expected = expected,
        actual = actual,
        retryable = retryable,
        nextAction = nextAction,
    )
}

internal fun remoteOperationFailure(
    code: String,
    component: String,
    operation: String,
    stage: String,
    category: FailureCategory,
    summary: String,
    error: Throwable? = null,
    expected: String? = null,
    actual: String? = null,
    retryable: Boolean,
    nextAction: String? = null,
): AndroidRemoteOperationException =
    AndroidRemoteOperationException(
        failure =
            AegisFailure(
                code = code,
                component = component,
                operation = operation,
                stage = stage,
                category = category,
                summary = summary,
                technicalCause = error?.causalMessage(),
                expected = expected,
                actual = actual,
                retryable = retryable,
                correlationId = "android-${UUID.randomUUID()}",
                nextAction = nextAction,
                underlyingType = error?.javaClass?.name,
            ),
        cause = error,
    )

private fun AegisFailure.toDisplayMessage(): String =
    buildString {
        append(code)
        append(": ")
        append(summary)
        technicalCause?.let {
            append(". Cause: ")
            append(it)
        }
        append(" [correlation=")
        append(correlationId)
        append(']')
    }

private fun Throwable.causalMessage(): String {
    val chain = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    return chain
        .joinToString(" <- ") { cause ->
            val detail =
                cause.message
                    ?.replace(Regex("[\\r\\n\\t]+"), " ")
                    ?.trim()
                    .orEmpty()
            if (detail.isBlank()) cause.javaClass.simpleName else "${cause.javaClass.simpleName}: $detail"
        }.take(MAX_CAUSE_LENGTH)
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is T }

private fun Throwable.sshDisconnectReason(): DisconnectReason? =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<SSHException>()
        .firstOrNull()
        ?.disconnectReason

private const val MAX_CAUSE_DEPTH = 4
private const val MAX_CAUSE_LENGTH = 1_024
