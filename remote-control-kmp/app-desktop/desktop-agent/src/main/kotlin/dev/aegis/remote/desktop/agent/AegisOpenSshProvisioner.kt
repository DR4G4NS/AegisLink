package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.model.WakeOnLanConfig
import dev.aegis.remote.core.security.canonicalOpenSshSha256Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class AegisOpenSshOperation {
    Provision,
    Inspect,
    EnrollKey,
    RemoveKey,
    Uninstall,
}

data class AegisOpenSshFailure(
    val code: String,
    val component: String = "windows-openssh",
    val operation: AegisOpenSshOperation,
    val stage: String,
    val summary: String,
    val technicalCause: String?,
    val expected: String?,
    val actual: String?,
    val retryable: Boolean,
    val nextAction: String,
    val underlyingType: String? = null,
)

class AegisOpenSshException(
    val failure: AegisOpenSshFailure,
    cause: Throwable? = null,
) : IllegalStateException("${failure.code} ${failure.summary}", cause)

@Serializable
data class AegisOpenSshState(
    val serviceName: String,
    val serviceStatus: String,
    val port: Int,
    val rootDirectory: String,
    val configPath: String,
    val authorizedKeysPath: String,
    val hostKeyPath: String,
    val hostKeyFingerprint: String? = null,
    val hostKeyAlgorithm: String? = null,
    val firewallRuleName: String,
    val sshdPath: String? = null,
    val authorizedUser: String,
    val firewallEnabled: Boolean = true,
    val wakeOnLanConfigs: List<WakeOnLanConfig> = emptyList(),
    val appScopedAccess: Boolean = false,
)

@Serializable
data class AegisSshKeyEnrollmentResult(
    val deviceId: String,
    val fingerprint: String,
    val algorithm: String,
    val added: Boolean,
    val authorizedKeysPath: String,
)

@Serializable
data class AegisOpenSshRemovalResult(
    val serviceName: String,
    val removed: Boolean,
    val rootDirectory: String,
    val firewallRuleName: String,
    val capabilityRemoved: Boolean,
)

@Serializable
data class AegisSshKeyRemovalResult(
    val deviceId: String,
    val removed: Boolean,
    val authorizedKeysPath: String,
)

data class AegisSshBootstrapResult(
    val username: String,
    val port: Int,
    val hostKeyFingerprint: HostKeyFingerprint,
    val wakeOnLanConfigs: List<WakeOnLanConfig>,
    val enrollment: AegisSshKeyEnrollmentResult,
)

data class OpenSshCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

fun interface OpenSshCommandRunner {
    suspend fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): OpenSshCommandResult
}

class ProcessOpenSshCommandRunner : OpenSshCommandRunner {
    override suspend fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): OpenSshCommandResult =
        withContext(Dispatchers.IO) {
            require(command.isNotEmpty()) { "Command must not be empty" }
            require(timeoutMillis > 0) { "Timeout must be positive" }
            val process = ProcessBuilder(command).start()
            val readers = Executors.newFixedThreadPool(2)
            try {
                val stdout = readers.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = readers.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                    return@withContext OpenSshCommandResult(
                        exitCode = -1,
                        stdout = stdout.get(5, TimeUnit.SECONDS),
                        stderr = stderr.get(5, TimeUnit.SECONDS),
                        timedOut = true,
                    )
                }
                OpenSshCommandResult(
                    exitCode = process.exitValue(),
                    stdout = stdout.get(5, TimeUnit.SECONDS),
                    stderr = stderr.get(5, TimeUnit.SECONDS),
                )
            } finally {
                readers.shutdownNow()
            }
        }
}

interface AegisOpenSshInspector {
    suspend fun inspect(): AegisOpenSshState
}

interface AegisSshKeyEnrollment {
    suspend fun enrollAuthorizedKey(
        publicKey: String,
        deviceId: String,
    ): AegisSshBootstrapResult

    suspend fun removeAuthorizedKey(deviceId: String): AegisSshKeyRemovalResult
}

/** Runtime port used by the agent; provisioning/install remains a Setup concern. */
interface AegisOpenSshManager :
    AegisOpenSshInspector,
    AegisSshKeyEnrollment {
    suspend fun startAccess(): AegisOpenSshState = inspect()

    suspend fun setFileAccess(
        deviceId: String,
        enabled: Boolean,
    ) {
        error("SSH-7335: This installation does not support changing file access. Update Aegis on the PC.")
    }

    /** Closes app-owned listeners and every active terminal/file connection. */
    suspend fun shutdown() = Unit
}

/**
 * Controls only the isolated `AegisOpenSSH` service and its scripts. It never
 * edits the system sshd service or `%ProgramData%/ssh/sshd_config`.
 */
class WindowsAegisOpenSshProvisioner(
    private val scriptDirectory: Path,
    private val runner: OpenSshCommandRunner = ProcessOpenSshCommandRunner(),
    private val powershellExecutable: String = defaultWindowsPowerShell(),
    private val timeoutMillis: Long = DEFAULT_OPENSSH_COMMAND_TIMEOUT_MILLIS,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AegisOpenSshManager {
    private val gateway = AppScopedSshGateway()

    override suspend fun startAccess(): AegisOpenSshState {
        val state = inspect()
        check(state.appScopedAccess) { "SSH-7334: Repair Aegis with the updated installer to make terminal and file access stop when the app closes." }
        if (state.serviceStatus.equals("Running", ignoreCase = true)) gateway.start(state.port)
        return state
    }

    override suspend fun shutdown() = gateway.close()

    override suspend fun setFileAccess(
        deviceId: String,
        enabled: Boolean,
    ) {
        require(DEVICE_ID_PATTERN.matches(deviceId))
        gateway.disconnectSessions()
        invokeScript(
            scriptName = "set-aegis-ssh-access.ps1",
            operation = AegisOpenSshOperation.EnrollKey,
            stage = "update-device-access",
            arguments = listOf("-DeviceId", deviceId, "-Access", if (enabled) "Allow" else "Deny"),
            deserializer = AegisSshKeyRemovalResult.serializer(),
        )
        gateway.disconnectSessions()
    }

    @Volatile
    private var cachedInspectState: AegisOpenSshState? = null

    suspend fun provision(
        port: Int = DEFAULT_AEGIS_SSH_PORT,
        authorizedUser: String? = null,
        installCapabilityIfMissing: Boolean = true,
    ): AegisOpenSshState {
        if (port !in 1024..65_535) {
            throw validationFailure(
                operation = AegisOpenSshOperation.Provision,
                summary = "The private SSH port is outside the allowed range.",
                actual = port.toString(),
                nextAction = "Choose an unused TCP port between 1024 and 65535.",
            )
        }
        val arguments = mutableListOf("-Port", port.toString())
        authorizedUser?.let { user ->
            if (!WINDOWS_ACCOUNT_PATTERN.matches(user)) {
                throw validationFailure(
                    operation = AegisOpenSshOperation.Provision,
                    summary = "The Windows account cannot be represented safely in AllowUsers.",
                    actual = "invalid account syntax",
                    nextAction = "Use a local or domain account containing only letters, digits, dot, underscore, at-sign, dash or backslash.",
                )
            }
            arguments += listOf("-AuthorizedUser", user)
        }
        if (!installCapabilityIfMissing) arguments += "-SkipCapabilityInstall"
        return invokeScript(
            scriptName = INSTALL_SCRIPT,
            operation = AegisOpenSshOperation.Provision,
            stage = "provision-service",
            arguments = arguments,
            deserializer = AegisOpenSshState.serializer(),
        ).also { cachedInspectState = it }
    }

    override suspend fun inspect(): AegisOpenSshState =
        invokeScript(
            scriptName = INSPECT_SCRIPT,
            operation = AegisOpenSshOperation.Inspect,
            stage = "inspect-managed-instance",
            deserializer = AegisOpenSshState.serializer(),
        ).also { cachedInspectState = it }

    override suspend fun enrollAuthorizedKey(
        publicKey: String,
        deviceId: String,
    ): AegisSshBootstrapResult {
        val enrollment = enrollPublicKey(deviceId = deviceId, publicKey = publicKey)
        // Host key, port, and username do not change when a client key is enrolled.
        // Reuse the last inspect from startup/preflight so Approve is not blocked on a
        // second PowerShell round-trip.
        val state = cachedInspectState?.takeIf { !it.hostKeyFingerprint.isNullOrBlank() } ?: inspect()
        val fingerprintValue =
            state.hostKeyFingerprint?.let(::canonicalOpenSshSha256Fingerprint)
                ?: throw AegisOpenSshException(
                    AegisOpenSshFailure(
                        code = "SSH-7318",
                        operation = AegisOpenSshOperation.EnrollKey,
                        stage = "read-bootstrap-profile",
                        summary = "The managed OpenSSH host-key fingerprint is unavailable.",
                        technicalCause = "Inspection did not return a canonical SHA256 OpenSSH host-key fingerprint",
                        expected = "SHA256 host-key fingerprint after successful provisioning",
                        actual = state.hostKeyFingerprint ?: "missing",
                        retryable = false,
                        nextAction = "Repair the isolated Aegis OpenSSH instance before approving the device.",
                    ),
                )
        return AegisSshBootstrapResult(
            username = state.authorizedUser,
            port = state.port,
            hostKeyFingerprint = HostKeyFingerprint("SHA256", fingerprintValue),
            wakeOnLanConfigs = state.wakeOnLanConfigs,
            enrollment = enrollment,
        )
    }

    override suspend fun removeAuthorizedKey(deviceId: String): AegisSshKeyRemovalResult {
        gateway.disconnectSessions()
        if (!DEVICE_ID_PATTERN.matches(deviceId)) {
            throw validationFailure(
                operation = AegisOpenSshOperation.RemoveKey,
                summary = "The device identifier is invalid for SSH key removal.",
                actual = "invalid device id syntax",
                nextAction = "Use the canonical Aegis device identifier.",
            )
        }
        return invokeScript(
            scriptName = REMOVE_KEY_SCRIPT,
            operation = AegisOpenSshOperation.RemoveKey,
            stage = "remove-authorized-key",
            arguments = listOf("-DeviceId", deviceId),
            deserializer = AegisSshKeyRemovalResult.serializer(),
        )
    }

    suspend fun enrollPublicKey(
        deviceId: String,
        publicKey: String,
    ): AegisSshKeyEnrollmentResult {
        if (!DEVICE_ID_PATTERN.matches(deviceId)) {
            throw validationFailure(
                operation = AegisOpenSshOperation.EnrollKey,
                summary = "The device identifier is invalid for SSH key enrollment.",
                actual = "invalid device id syntax",
                nextAction = "Use the canonical Aegis device identifier.",
            )
        }
        val canonicalPublicKey = canonicalPublicKey(publicKey)
        val temporaryKey = Files.createTempFile("aegis-ssh-public-", ".pub")
        return try {
            Files.writeString(temporaryKey, canonicalPublicKey, StandardCharsets.UTF_8)
            invokeScript(
                scriptName = ENROLL_SCRIPT,
                operation = AegisOpenSshOperation.EnrollKey,
                stage = "enroll-authorized-key",
                arguments = listOf("-PublicKeyFile", temporaryKey.toString(), "-DeviceId", deviceId),
                deserializer = AegisSshKeyEnrollmentResult.serializer(),
            )
        } finally {
            runCatching { Files.deleteIfExists(temporaryKey) }
        }
    }

    suspend fun uninstall(removeCapabilityInstalledByAegis: Boolean = false): AegisOpenSshRemovalResult =
        invokeScript(
            scriptName = UNINSTALL_SCRIPT,
            operation = AegisOpenSshOperation.Uninstall,
            stage = "remove-managed-instance",
            arguments = if (removeCapabilityInstalledByAegis) listOf("-RemoveCapabilityInstalledByAegis") else emptyList(),
            deserializer = AegisOpenSshRemovalResult.serializer(),
        )

    @Suppress("ThrowsCount")
    private suspend fun <T> invokeScript(
        scriptName: String,
        operation: AegisOpenSshOperation,
        stage: String,
        arguments: List<String> = emptyList(),
        deserializer: DeserializationStrategy<T>,
    ): T {
        val script = scriptDirectory.resolve(scriptName).normalize().toAbsolutePath()
        val expectedDirectory = scriptDirectory.normalize().toAbsolutePath()
        if (!script.startsWith(expectedDirectory) || !Files.isRegularFile(script)) {
            throw AegisOpenSshException(
                AegisOpenSshFailure(
                    code = "SSH-7305",
                    operation = operation,
                    stage = "locate-script",
                    summary = "The packaged OpenSSH management script is missing.",
                    technicalCause = "Expected a regular file at $script",
                    expected = "Signed Aegis package contains $scriptName",
                    actual = "Script was not found",
                    retryable = false,
                    nextAction = "Repair or reinstall Aegis Remote Desktop.",
                ),
            )
        }
        val command =
            listOf(
                powershellExecutable,
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
            ) + arguments
        val result =
            runCatching { runner.run(command, timeoutMillis) }
                .getOrElse { error ->
                    throw AegisOpenSshException(
                        AegisOpenSshFailure(
                            code = "SSH-7301",
                            operation = operation,
                            stage = stage,
                            summary = "Windows could not execute the OpenSSH management command.",
                            technicalCause = error.message ?: error.javaClass.name,
                            expected = "PowerShell process starts and returns one JSON result",
                            actual = "Process launch or I/O failed",
                            retryable = true,
                            nextAction = "Verify Windows PowerShell and retry with administrator privileges.",
                            underlyingType = error.javaClass.name,
                        ),
                        error,
                    )
                }
        if (result.timedOut) {
            throw AegisOpenSshException(
                AegisOpenSshFailure(
                    code = "SSH-7304",
                    operation = operation,
                    stage = stage,
                    summary = "The OpenSSH management command timed out.",
                    technicalCause = result.stderr.take(MAX_DIAGNOSTIC_LENGTH).ifBlank { null },
                    expected = "Command completes within $timeoutMillis ms",
                    actual = "PowerShell was terminated after the timeout",
                    retryable = true,
                    nextAction = "Inspect Windows servicing and service logs before retrying.",
                ),
            )
        }
        if (result.exitCode != 0) {
            throw AegisOpenSshException(
                AegisOpenSshFailure(
                    code = scriptFailureCode(result.stderr),
                    operation = operation,
                    stage = stage,
                    summary = "The isolated Aegis OpenSSH operation failed.",
                    technicalCause = result.stderr.take(MAX_DIAGNOSTIC_LENGTH).ifBlank { "PowerShell exited without stderr" },
                    expected = "PowerShell exits with code 0",
                    actual = "Exit code ${result.exitCode}",
                    retryable = isRetryableScriptFailure(result.stderr),
                    nextAction = scriptFailureNextAction(result.stderr),
                ),
            )
        }
        val payload =
            result.stdout
                .lineSequence()
                .map(String::trim)
                .lastOrNull(String::isNotBlank)
        if (payload == null) {
            throw invalidOutputFailure(operation, stage, "PowerShell returned no JSON output")
        }
        return runCatching { json.decodeFromString(deserializer, payload) }
            .getOrElse { error ->
                throw invalidOutputFailure(operation, stage, error.message ?: error.javaClass.name, error)
            }
    }

    @Suppress("ThrowsCount")
    private fun canonicalPublicKey(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length !in 1..MAX_PUBLIC_KEY_LENGTH || trimmed.contains('\n') || trimmed.contains('\r')) {
            throw invalidPublicKey("Expected one OpenSSH public-key record no larger than 16 KiB")
        }
        val fields = trimmed.split(Regex("\\s+"), limit = 3)
        if (fields.size < 2 || fields[0] !in SUPPORTED_PUBLIC_KEY_ALGORITHMS || !BASE64_PATTERN.matches(fields[1])) {
            throw invalidPublicKey("Unsupported algorithm or malformed Base64 key blob")
        }
        runCatching { Base64.getDecoder().decode(fields[1]) }
            .getOrElse { throw invalidPublicKey("The OpenSSH key blob is not valid Base64", it) }
        return "${fields[0]} ${fields[1]}"
    }

    private fun invalidPublicKey(
        cause: String,
        error: Throwable? = null,
    ): AegisOpenSshException =
        AegisOpenSshException(
            AegisOpenSshFailure(
                code = "SSH-7303",
                operation = AegisOpenSshOperation.EnrollKey,
                stage = "validate-public-key",
                summary = "The Android SSH public key is invalid.",
                technicalCause = cause,
                expected = "One supported OpenSSH public-key record",
                actual = "Input rejected before privileged execution",
                retryable = false,
                nextAction = "Generate a new SSH keypair and submit only its public key.",
                underlyingType = error?.javaClass?.name,
            ),
            error,
        )

    private fun invalidOutputFailure(
        operation: AegisOpenSshOperation,
        stage: String,
        cause: String,
        error: Throwable? = null,
    ): AegisOpenSshException =
        AegisOpenSshException(
            AegisOpenSshFailure(
                code = "SSH-7302",
                operation = operation,
                stage = stage,
                summary = "The OpenSSH management script returned an invalid result.",
                technicalCause = cause,
                expected = "One JSON object matching the Aegis provisioning contract",
                actual = "Output could not be decoded",
                retryable = false,
                nextAction = "Repair the Aegis installation and preserve the provisioning log for diagnosis.",
                underlyingType = error?.javaClass?.name,
            ),
            error,
        )
}

private fun validationFailure(
    operation: AegisOpenSshOperation,
    summary: String,
    actual: String,
    nextAction: String,
): AegisOpenSshException =
    AegisOpenSshException(
        AegisOpenSshFailure(
            code = "SSH-7303",
            operation = operation,
            stage = "validate-request",
            summary = summary,
            technicalCause = "Request validation failed before privileged execution",
            expected = "Canonical, bounded provisioning parameters",
            actual = actual,
            retryable = false,
            nextAction = nextAction,
        ),
    )

private fun scriptFailureCode(stderr: String): String = SCRIPT_ERROR_CODE.find(stderr)?.value ?: "SSH-7301"

private fun isRetryableScriptFailure(stderr: String): Boolean = listOf("SSH-7309", "SSH-7316", "SSH-7317", "SSH-7324").any(stderr::contains)

private fun scriptFailureNextAction(stderr: String): String =
    when {
        "SSH-7306" in stderr -> "Run the operation from the elevated Aegis installer or approve the administrator prompt."
        "SSH-7309" in stderr -> "Choose another private port or stop the process currently listening on it."
        "SSH-7311" in stderr -> "Resolve the AegisOpenSSH service-name collision manually; Aegis will not overwrite it."
        "SSH-7315" in stderr -> "Inspect the isolated sshd_config and OpenSSH event log; do not edit the system sshd_config."
        else -> "Inspect the causal PowerShell error and Windows service log before retrying."
    }

private fun defaultWindowsPowerShell(): String {
    val windows = System.getenv("WINDIR")?.takeIf(String::isNotBlank) ?: "C:\\Windows"
    return Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString()
}

private const val DEFAULT_AEGIS_SSH_PORT = 48222
private const val DEFAULT_OPENSSH_COMMAND_TIMEOUT_MILLIS = 120_000L
private const val MAX_PUBLIC_KEY_LENGTH = 16_384
private const val MAX_DIAGNOSTIC_LENGTH = 2_000
private const val INSTALL_SCRIPT = "install-aegis-openssh.ps1"
private const val INSPECT_SCRIPT = "inspect-aegis-openssh.ps1"
private const val ENROLL_SCRIPT = "enroll-aegis-ssh-key.ps1"
private const val REMOVE_KEY_SCRIPT = "remove-aegis-ssh-key.ps1"
private const val UNINSTALL_SCRIPT = "uninstall-aegis-openssh.ps1"
private val WINDOWS_ACCOUNT_PATTERN = Regex("^[A-Za-z0-9_.@\\\\-]{1,128}$")
private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,128}$")
private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/]+={0,3}$")
private val SCRIPT_ERROR_CODE = Regex("SSH-7[0-9]{3}")
private val SUPPORTED_PUBLIC_KEY_ALGORITHMS =
    setOf(
        "ssh-ed25519",
        "ecdsa-sha2-nistp256",
        "sk-ssh-ed25519@openssh.com",
        "rsa-sha2-512",
        "rsa-sha2-256",
        "ssh-rsa",
    )
