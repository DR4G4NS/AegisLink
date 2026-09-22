package dev.aegis.remote.android.security

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.security.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.FileMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * One post-pairing gate over one TCP/SSH transport: negotiation and host-key pinning happen in
 * connect(), the enrolled Android key authenticates once, then shell and SFTP channels are
 * opened sequentially on that authenticated connection.
 */
class AndroidSshHealthChecker(
    private val credentialStore: SecureCredentialStore,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun check(
        profile: DeviceProfile,
        routeHost: String = profile.localHost.host,
    ): AndroidSshHealthCheckResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val expectedFingerprint =
                profile.hostKeyFingerprint
                    ?: throw remoteOperationFailure(
                        code = AegisFailureCodes.SSH_HOST_KEY_MISSING,
                        component = "android-ssh-health",
                        operation = "post-pairing-health-check",
                        stage = "precondition",
                        category = FailureCategory.CONFIGURATION,
                        summary = "The SSH host key pin is missing",
                        retryable = false,
                    )
            val pinned =
                runCatching { createPinnedSshConnection(expectedFingerprint, HEALTH_TIMEOUT_MILLIS, HEALTH_TIMEOUT_MILLIS) }
                    .getOrElse { error ->
                        throw sshOperationFailure("post-pairing-health-check", "pin-validation", error)
                    }
            val ssh = pinned.client
            try {
                try {
                    ssh.connect(routeHost, profile.sshPort)
                } catch (error: Throwable) {
                    throw sshOperationFailure("post-pairing-health-check", "tcp-negotiation-pin", error, pinned.observation)
                }
                try {
                    ssh.authenticateProfile(profile, credentialStore)
                } catch (error: Throwable) {
                    throw sshOperationFailure("post-pairing-health-check", "public-key-authentication", error, pinned.observation)
                }
                enableSshKeepAlive(ssh)

                val shellSession =
                    try {
                        ssh.startSession()
                    } catch (error: Throwable) {
                        throw sshOperationFailure("post-pairing-health-check", "shell-channel-open", error)
                    }
                val shellStatus = verifyShell(shellSession)
                val sftp =
                    try {
                        ssh.newSFTPClient()
                    } catch (error: Throwable) {
                        throw sftpOperationFailure(
                            operation = "post-pairing-health-check",
                            stage = "sftp-subsystem-open",
                            error = error,
                            retryable = true,
                        )
                    }
                val canonicalHome = verifySftpSubsystem(sftp)
                AndroidSshHealthCheckResult(
                    endpoint = "$routeHost:${profile.sshPort}",
                    hostKeyFingerprint = "SHA256:${pinned.observation.actualFingerprint ?: pinned.observation.expectedFingerprint}",
                    shellExitStatus = shellStatus,
                    canonicalSftpHomeEstablished = canonicalHome.isNotBlank(),
                    elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                )
            } finally {
                runCatching { ssh.socket.close() }
                runCatching { ssh.disconnect() }
                runCatching { ssh.close() }
            }
        }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun verifyShell(session: Session): Int {
        try {
            val command = session.exec(HEALTH_COMMAND)
            command.use {
                command.join(HEALTH_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
                if (command.isOpen) {
                    runCatching { command.close() }
                    throw SocketTimeoutException("SSH shell health command did not exit within ${HEALTH_TIMEOUT_MILLIS}ms")
                }
                val stdout = command.inputStream.readBounded(HEALTH_OUTPUT_LIMIT_BYTES)
                val stderr = command.errorStream.readBounded(HEALTH_OUTPUT_LIMIT_BYTES)
                val status = command.exitStatus
                if (status != 0 || !stdout.toString(Charsets.UTF_8).contains(HEALTH_MARKER)) {
                    val diagnostic = stderr.toString(Charsets.UTF_8).trim().take(512)
                    throw IllegalStateException(
                        "SSH shell health command failed: exit=${status ?: "missing"}" +
                            diagnostic.takeIf { it.isNotBlank() }?.let { ", stderr=$it" }.orEmpty(),
                    )
                }
                return status
            }
        } catch (error: Throwable) {
            throw sshOperationFailure("post-pairing-health-check", "shell", error)
        } finally {
            runCatching { session.close() }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun verifySftpSubsystem(sftp: net.schmizz.sshj.sftp.SFTPClient): String {
        try {
            val canonicalHome = sftp.canonicalize(".")
            val normalized = canonicalHome.replace('\\', '/')
            val absolute = normalized.startsWith('/') || WINDOWS_ABSOLUTE_PATH.matches(normalized)
            check(absolute) { "SFTP REALPATH returned a non-absolute account home" }
            check(sftp.lstat(canonicalHome).type == FileMode.Type.DIRECTORY) {
                "SFTP account home is not a directory"
            }
            return canonicalHome
        } catch (error: Throwable) {
            throw sftpOperationFailure(
                operation = "post-pairing-health-check",
                stage = "sftp-canonical-home",
                error = error,
                code = AegisFailureCodes.SFTP_ROOT_DISCOVERY_FAILED,
            )
        } finally {
            runCatching { sftp.close() }
        }
    }

    private fun InputStream.readBounded(limitBytes: Int): ByteArray {
        val result = ByteArrayOutputStream(minOf(limitBytes, 256))
        val buffer = ByteArray(256)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            check(total <= limitBytes) { "SSH health command output exceeded $limitBytes bytes" }
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }

    private companion object {
        const val HEALTH_TIMEOUT_MILLIS = 10_000
        const val HEALTH_OUTPUT_LIMIT_BYTES = 4 * 1_024
        const val HEALTH_MARKER = "AEGIS_SSH_HEALTH_OK"
        const val HEALTH_COMMAND = "echo $HEALTH_MARKER"
        val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/.*")
    }
}

data class AndroidSshHealthCheckResult(
    val endpoint: String,
    val hostKeyFingerprint: String,
    val shellExitStatus: Int,
    /** The canonical home itself is intentionally not returned or logged. */
    val canonicalSftpHomeEstablished: Boolean,
    val elapsedMillis: Long,
)
