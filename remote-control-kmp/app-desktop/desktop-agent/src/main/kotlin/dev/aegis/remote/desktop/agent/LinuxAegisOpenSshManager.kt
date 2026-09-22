package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.security.canonicalOpenSshSha256Fingerprint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * User-scoped Linux OpenSSH host.
 *
 * This manager owns only its child process and files below [rootDirectory]. It
 * never invokes a service manager, edits a system sshd configuration, changes
 * firewall policy, or requires root privileges.
 */
class LinuxAegisOpenSshManager(
    private val rootDirectory: Path = defaultLinuxAegisOpenSshDirectory(),
    private val authorizedUser: String = System.getProperty("user.name").orEmpty(),
    private val port: Int = LINUX_AEGIS_SSH_PORT,
    private val sshdPath: Path = defaultLinuxSshdPath(),
    private val sshKeygenPath: Path = Path.of("/usr/bin/ssh-keygen"),
    private val commandRunner: OpenSshCommandRunner = ProcessOpenSshCommandRunner(),
    private val processLauncher: LinuxSshdProcessLauncher = ProcessLinuxSshdProcessLauncher(),
    private val managedProcessFinder: (Path, Path, Path, Path, Int, String) -> LinuxManagedSshdProcess? =
        ::findManagedLinuxSshdProcess,
    private val executableCheck: (Path) -> Boolean = { path -> Files.isRegularFile(path) && Files.isExecutable(path) },
    private val commandTimeoutMillis: Long = LINUX_OPENSSH_COMMAND_TIMEOUT_MILLIS,
    private val startupGraceMillis: Long = LINUX_OPENSSH_STARTUP_GRACE_MILLIS,
    private val shutdownTimeoutMillis: Long = LINUX_OPENSSH_SHUTDOWN_TIMEOUT_MILLIS,
) : AegisOpenSshManager {
    private val lifecycleMutex = Mutex()
    private val configPath = rootDirectory.resolve("sshd_config")
    private val authorizedKeysPath = rootDirectory.resolve("authorized_keys")
    private val hostKeyPath = rootDirectory.resolve("ssh_host_ed25519_key")
    private val hostPublicKeyPath = rootDirectory.resolve("ssh_host_ed25519_key.pub")
    private val pidPath = rootDirectory.resolve("sshd.pid")

    @Volatile
    private var process: LinuxManagedSshdProcess? = null

    override suspend fun inspect(): AegisOpenSshState =
        lifecycleMutex.withLock {
            ensureStarted()
            currentState()
        }

    override suspend fun enrollAuthorizedKey(
        publicKey: String,
        deviceId: String,
    ): AegisSshBootstrapResult =
        lifecycleMutex.withLock {
            val canonicalKey = canonicalLinuxPublicKey(publicKey)
            requireDeviceId(deviceId, AegisOpenSshOperation.EnrollKey)
            ensureStarted()

            val tag = linuxAegisKeyTag(deviceId)
            val desiredLine = "${canonicalKey.algorithm} ${canonicalKey.encodedBlob} $tag"
            val currentLines = readAuthorizedKeyLines()
            val alreadyPresent = currentLines.count { it.trim() == desiredLine } == 1
            val nextLines =
                currentLines.filterNot { line -> line.hasExactAegisDeviceTag(tag) } + desiredLine
            if (!alreadyPresent || nextLines != currentLines) {
                writeOwnerOnlyFile(authorizedKeysPath, nextLines.joinToString(separator = "\n", postfix = "\n"))
            }

            val state = currentState()
            val hostFingerprint =
                state.hostKeyFingerprint
                    ?.let(::canonicalOpenSshSha256Fingerprint)
                    ?: throw linuxOpenSshFailure(
                        code = "SSH-7318",
                        operation = AegisOpenSshOperation.EnrollKey,
                        stage = "read-host-key",
                        summary = "The user-scoped OpenSSH host-key fingerprint is unavailable.",
                        cause = "The generated host public key did not produce a canonical SHA256 fingerprint",
                        retryable = false,
                        nextAction = "Remove only the Aegis user-scoped host key and restart Aegis to regenerate it.",
                    )
            val enrollment =
                AegisSshKeyEnrollmentResult(
                    deviceId = deviceId,
                    fingerprint = "SHA256:${canonicalKey.fingerprint}",
                    algorithm = canonicalKey.algorithm,
                    added = !alreadyPresent,
                    authorizedKeysPath = authorizedKeysPath.toString(),
                )
            AegisSshBootstrapResult(
                username = authorizedUser,
                port = port,
                hostKeyFingerprint = HostKeyFingerprint("SHA256", hostFingerprint),
                wakeOnLanConfigs = emptyList(),
                enrollment = enrollment,
            )
        }

    override suspend fun removeAuthorizedKey(deviceId: String): AegisSshKeyRemovalResult =
        lifecycleMutex.withLock {
            requireDeviceId(deviceId, AegisOpenSshOperation.RemoveKey)
            prepareStorage()
            val tag = linuxAegisKeyTag(deviceId)
            val currentLines = readAuthorizedKeyLines()
            val nextLines = currentLines.filterNot { line -> line.hasExactAegisDeviceTag(tag) }
            val removed = nextLines.size != currentLines.size
            if (removed) {
                val content = nextLines.joinToString(separator = "\n", postfix = "\n").takeIf { nextLines.isNotEmpty() }.orEmpty()
                writeOwnerOnlyFile(authorizedKeysPath, content)
            }
            AegisSshKeyRemovalResult(
                deviceId = deviceId,
                removed = removed,
                authorizedKeysPath = authorizedKeysPath.toString(),
            )
        }

    override suspend fun shutdown() {
        lifecycleMutex.withLock {
            val ownedProcess = process
            process = null
            if (ownedProcess != null && ownedProcess.isAlive()) {
                ownedProcess.terminate(shutdownTimeoutMillis)
            }
            runCatching { Files.deleteIfExists(pidPath) }
        }
    }

    private suspend fun ensureStarted() {
        validateRequest()
        process?.takeIf(LinuxManagedSshdProcess::isAlive)?.let { return }
        process = null
        prepareStorage()
        managedProcessFinder(pidPath, configPath, authorizedKeysPath, sshdPath, port, authorizedUser)
            ?.takeIf(LinuxManagedSshdProcess::isAlive)
            ?.let { stale ->
                stale.terminate(shutdownTimeoutMillis)
                if (stale.isAlive()) {
                    throw linuxOpenSshFailure(
                        code = "SSH-7309",
                        operation = AegisOpenSshOperation.Inspect,
                        stage = "stop-stale-user-sshd",
                        summary = "The previous Aegis OpenSSH process did not stop.",
                        cause = "The verified Aegis-managed process still owns TCP port $port",
                        retryable = true,
                        nextAction = "Retry after the previous Aegis process exits.",
                    )
                }
                Files.deleteIfExists(pidPath)
            }
        ensureHostKey()
        writeSshdConfig()
        validateSshdConfig()

        val command = listOf(sshdPath.toString(), "-D", "-e", "-f", configPath.toString())
        val started =
            runCatching { processLauncher.start(command, rootDirectory) }
                .getOrElse { error ->
                    throw linuxOpenSshFailure(
                        code = "SSH-7301",
                        operation = AegisOpenSshOperation.Inspect,
                        stage = "start-user-sshd",
                        summary = "Linux could not start the user-scoped OpenSSH host.",
                        cause = error.message ?: error.javaClass.name,
                        retryable = true,
                        nextAction = "Verify the OpenSSH server package and that TCP port $port is unused.",
                        underlying = error,
                    )
                }
        process = started
        if (started.waitForExit(startupGraceMillis) || !started.isAlive()) {
            process = null
            throw linuxOpenSshFailure(
                code = "SSH-7309",
                operation = AegisOpenSshOperation.Inspect,
                stage = "start-user-sshd",
                summary = "The user-scoped OpenSSH host exited during startup.",
                cause = started.diagnostics().take(LINUX_OPENSSH_MAX_DIAGNOSTIC_LENGTH).ifBlank { "sshd exited without diagnostics" },
                retryable = true,
                nextAction = "Verify TCP port $port, the local OpenSSH installation, and the Aegis data-directory permissions.",
            )
        }
    }

    private fun validateRequest() {
        if (port !in 1024..65_535) {
            throw linuxOpenSshFailure(
                code = "SSH-7303",
                operation = AegisOpenSshOperation.Inspect,
                stage = "validate-request",
                summary = "The Linux Aegis SSH port is outside the user-scoped range.",
                cause = "Port $port is not between 1024 and 65535",
                retryable = false,
                nextAction = "Configure an unused TCP port between 1024 and 65535.",
            )
        }
        if (!LINUX_ACCOUNT_PATTERN.matches(authorizedUser)) {
            throw linuxOpenSshFailure(
                code = "SSH-7303",
                operation = AegisOpenSshOperation.Inspect,
                stage = "validate-request",
                summary = "The Linux account cannot be represented safely in AllowUsers.",
                cause = "The account name contains unsupported sshd_config characters",
                retryable = false,
                nextAction = "Run Aegis as a local account containing only letters, digits, dot, underscore or dash.",
            )
        }
        if (!executableCheck(sshdPath)) {
            throw linuxOpenSshFailure(
                code = "SSH-7305",
                operation = AegisOpenSshOperation.Inspect,
                stage = "locate-sshd",
                summary = "The OpenSSH server executable is unavailable.",
                cause = "Expected an executable sshd at $sshdPath",
                retryable = false,
                nextAction = "Install the distribution's OpenSSH server package and restart Aegis.",
            )
        }
    }

    private fun prepareStorage() {
        ensureManagedDirectory(rootDirectory)
        ensureOwnerOnlyFile(authorizedKeysPath)
    }

    private suspend fun ensureHostKey() {
        rejectSymlink(hostKeyPath)
        rejectSymlink(hostPublicKeyPath)
        val privateExists = Files.isRegularFile(hostKeyPath, LinkOption.NOFOLLOW_LINKS)
        val publicExists = Files.isRegularFile(hostPublicKeyPath, LinkOption.NOFOLLOW_LINKS)
        if (publicExists && !privateExists) {
            throw linuxOpenSshFailure(
                code = "SSH-7318",
                operation = AegisOpenSshOperation.Inspect,
                stage = "prepare-host-key",
                summary = "The Aegis OpenSSH private host key is missing.",
                cause = "A public host key exists without its private key",
                retryable = false,
                nextAction = "Remove only the incomplete Aegis user-scoped host-key files and restart Aegis.",
            )
        }
        if (!privateExists) {
            if (!executableCheck(sshKeygenPath)) {
                throw linuxOpenSshFailure(
                    code = "SSH-7305",
                    operation = AegisOpenSshOperation.Inspect,
                    stage = "locate-ssh-keygen",
                    summary = "The OpenSSH key generator is unavailable.",
                    cause = "Expected an executable ssh-keygen at $sshKeygenPath",
                    retryable = false,
                    nextAction = "Install the distribution's OpenSSH client tools and restart Aegis.",
                )
            }
            runCheckedCommand(
                command =
                    listOf(
                        sshKeygenPath.toString(),
                        "-q",
                        "-t",
                        "ed25519",
                        "-N",
                        "",
                        "-C",
                        "aegis-user-scoped-host",
                        "-f",
                        hostKeyPath.toString(),
                    ),
                operation = AegisOpenSshOperation.Inspect,
                stage = "generate-host-key",
            )
        } else if (!publicExists) {
            val result =
                runCheckedCommand(
                    command = listOf(sshKeygenPath.toString(), "-y", "-f", hostKeyPath.toString()),
                    operation = AegisOpenSshOperation.Inspect,
                    stage = "derive-host-public-key",
                )
            writeOwnerOnlyFile(hostPublicKeyPath, result.stdout.trim() + "\n")
        }
        requireManagedRegularFile(hostKeyPath)
        requireManagedRegularFile(hostPublicKeyPath)
        setOwnerOnlyPermissions(hostKeyPath)
        setOwnerOnlyPermissions(hostPublicKeyPath)
        canonicalLinuxPublicKey(Files.readString(hostPublicKeyPath, StandardCharsets.UTF_8))
    }

    private fun writeSshdConfig() {
        val config =
            """
            |# Generated by Aegis. This is not the system sshd_config.
            |Port $port
            |ListenAddress 0.0.0.0
            |ListenAddress ::
            |HostKey ${sshdConfigValue(hostKeyPath)}
            |PidFile ${sshdConfigValue(pidPath)}
            |AuthorizedKeysFile ${sshdConfigValue(authorizedKeysPath)}
            |AllowUsers $authorizedUser
            |PubkeyAuthentication yes
            |AuthenticationMethods publickey
            |PasswordAuthentication no
            |KbdInteractiveAuthentication no
            |ChallengeResponseAuthentication no
            |HostbasedAuthentication no
            |PermitEmptyPasswords no
            |PermitRootLogin no
            |StrictModes yes
            |UsePAM no
            |AllowAgentForwarding no
            |AllowTcpForwarding no
            |AllowStreamLocalForwarding no
            |GatewayPorts no
            |X11Forwarding no
            |PermitTunnel no
            |PermitUserEnvironment no
            |MaxAuthTries 3
            |MaxSessions 4
            |MaxStartups 3:30:5
            |LoginGraceTime 30
            |ClientAliveInterval 60
            |ClientAliveCountMax 3
            |Subsystem sftp internal-sftp
            |LogLevel VERBOSE
            """.trimMargin() + "\n"
        writeOwnerOnlyFile(configPath, config)
    }

    private suspend fun validateSshdConfig() {
        runCheckedCommand(
            command = listOf(sshdPath.toString(), "-t", "-f", configPath.toString()),
            operation = AegisOpenSshOperation.Inspect,
            stage = "validate-user-sshd-config",
        )
    }

    private suspend fun runCheckedCommand(
        command: List<String>,
        operation: AegisOpenSshOperation,
        stage: String,
    ): OpenSshCommandResult {
        val result =
            runCatching { commandRunner.run(command, commandTimeoutMillis) }
                .getOrElse { error ->
                    throw linuxOpenSshFailure(
                        code = "SSH-7301",
                        operation = operation,
                        stage = stage,
                        summary = "A bounded Linux OpenSSH command could not be executed.",
                        cause = error.message ?: error.javaClass.name,
                        retryable = true,
                        nextAction = "Verify the local OpenSSH package and retry.",
                        underlying = error,
                    )
                }
        if (result.timedOut) {
            throw linuxOpenSshFailure(
                code = "SSH-7304",
                operation = operation,
                stage = stage,
                summary = "A Linux OpenSSH command timed out.",
                cause = result.stderr.take(LINUX_OPENSSH_MAX_DIAGNOSTIC_LENGTH).ifBlank { "Command exceeded $commandTimeoutMillis ms" },
                retryable = true,
                nextAction = "Inspect the local OpenSSH installation and retry.",
            )
        }
        if (result.exitCode != 0) {
            throw linuxOpenSshFailure(
                code = "SSH-7301",
                operation = operation,
                stage = stage,
                summary = "The isolated Linux OpenSSH operation failed.",
                cause =
                    result.stderr
                        .ifBlank { result.stdout }
                        .take(LINUX_OPENSSH_MAX_DIAGNOSTIC_LENGTH)
                        .ifBlank { "Command exited with code ${result.exitCode}" },
                retryable = true,
                nextAction = "Inspect the reported OpenSSH error; do not modify the system sshd configuration.",
            )
        }
        return result
    }

    private fun currentState(): AegisOpenSshState {
        val hostKey = canonicalLinuxPublicKey(Files.readString(hostPublicKeyPath, StandardCharsets.UTF_8))
        return AegisOpenSshState(
            serviceName = "AegisOpenSSH-user",
            serviceStatus = if (process?.isAlive() == true) "Running" else "Stopped",
            port = port,
            rootDirectory = rootDirectory.toString(),
            configPath = configPath.toString(),
            authorizedKeysPath = authorizedKeysPath.toString(),
            hostKeyPath = hostKeyPath.toString(),
            hostKeyFingerprint = "SHA256:${hostKey.fingerprint}",
            hostKeyAlgorithm = hostKey.algorithm,
            firewallRuleName = "",
            sshdPath = sshdPath.toString(),
            authorizedUser = authorizedUser,
            firewallEnabled = false,
            wakeOnLanConfigs = emptyList(),
        )
    }

    private fun readAuthorizedKeyLines(): List<String> {
        ensureOwnerOnlyFile(authorizedKeysPath)
        val size = Files.size(authorizedKeysPath)
        if (size > LINUX_AUTHORIZED_KEYS_MAX_BYTES) {
            throw linuxOpenSshFailure(
                code = "SSH-7303",
                operation = AegisOpenSshOperation.EnrollKey,
                stage = "read-authorized-keys",
                summary = "The Aegis authorized_keys file exceeds its safety bound.",
                cause = "File size $size exceeds $LINUX_AUTHORIZED_KEYS_MAX_BYTES bytes",
                retryable = false,
                nextAction = "Inspect the user-scoped Aegis authorized_keys file before retrying.",
            )
        }
        val text = Files.readString(authorizedKeysPath, StandardCharsets.UTF_8)
        return text.lineSequence().toList().dropLastWhile(String::isEmpty)
    }

    private fun ensureOwnerOnlyFile(path: Path) {
        rejectSymlink(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            writeOwnerOnlyFile(path, "")
        } else {
            requireManagedRegularFile(path)
            setOwnerOnlyPermissions(path)
        }
    }

    private fun writeOwnerOnlyFile(
        path: Path,
        content: String,
    ) {
        check(content.toByteArray(StandardCharsets.UTF_8).size <= LINUX_AUTHORIZED_KEYS_MAX_BYTES || path != authorizedKeysPath) {
            "SSH-7303: Refusing to write an oversized Aegis authorized_keys file"
        }
        rejectSymlink(path)
        val directory = checkNotNull(path.parent) { "Managed OpenSSH file must have a parent directory" }
        ensureManagedDirectory(directory)
        val temporary = Files.createTempFile(directory, ".${path.fileName}.", ".tmp")
        try {
            setOwnerOnlyPermissions(temporary)
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            runCatching {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(path)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun ensureManagedDirectory(path: Path) {
        rejectSymlink(path)
        Files.createDirectories(path)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            error("SSH-7303: Managed OpenSSH path is not a directory: $path")
        }
        val view =
            Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?: error("SSH-7303: POSIX permissions are required for the Linux OpenSSH data directory")
        view.setPermissions(LINUX_DIRECTORY_PERMISSIONS)
    }

    private fun setOwnerOnlyPermissions(path: Path) {
        val view =
            Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?: error("SSH-7303: POSIX permissions are required for Linux OpenSSH files")
        view.setPermissions(LINUX_FILE_PERMISSIONS)
    }

    private fun requireManagedRegularFile(path: Path) {
        rejectSymlink(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            error("SSH-7303: Managed OpenSSH path is not a regular file: $path")
        }
    }

    private fun rejectSymlink(path: Path) {
        if (Files.isSymbolicLink(path)) {
            error("SSH-7303: Refusing a symbolic link in managed OpenSSH storage: $path")
        }
    }
}

fun interface LinuxSshdProcessLauncher {
    fun start(
        command: List<String>,
        workingDirectory: Path,
    ): LinuxManagedSshdProcess
}

interface LinuxManagedSshdProcess {
    fun isAlive(): Boolean

    /** Returns true when the child exited before the timeout. */
    fun waitForExit(timeoutMillis: Long): Boolean

    /** Attempts graceful termination, then forcibly terminates within the same bound. */
    fun terminate(timeoutMillis: Long)

    fun diagnostics(): String
}

internal fun findManagedLinuxSshdProcess(
    pidPath: Path,
    configPath: Path,
    authorizedKeysPath: Path,
    sshdPath: Path,
    port: Int,
    authorizedUser: String,
): LinuxManagedSshdProcess? {
    if (Files.isSymbolicLink(pidPath) || Files.isSymbolicLink(configPath)) return null
    if (!Files.isRegularFile(pidPath, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS) ||
        Files.size(pidPath) > 32L ||
        Files.size(configPath) > LINUX_MANAGED_CONFIG_MAX_BYTES
    ) {
        return null
    }
    val pid = runCatching { Files.readString(pidPath).trim().toLong() }.getOrNull()?.takeIf { it > 1L } ?: return null
    val handle = ProcessHandle.of(pid).orElse(null)?.takeIf(ProcessHandle::isAlive) ?: return null
    val info = handle.info()
    val command = info.command().orElse(null) ?: return null
    val expectedCommand = sshdPath.toAbsolutePath().normalize()
    val actualCommand = runCatching { Path.of(command).toAbsolutePath().normalize() }.getOrNull() ?: return null
    if (actualCommand != expectedCommand) return null
    val expectedArguments = listOf("-D", "-e", "-f", configPath.toString())
    val argumentsMatch = info.arguments().orElse(emptyArray()).toList() == expectedArguments
    if (!argumentsMatch && !linuxProcessTitleMatchesManagedSshd(handle.pid(), configPath)) return null
    val processUser = info.user().orElse(null)
    if (processUser != null && processUser != System.getProperty("user.name")) return null

    val configLines = runCatching { Files.readAllLines(configPath, StandardCharsets.UTF_8) }.getOrNull() ?: return null
    val requiredLines =
        setOf(
            "Port $port",
            "PidFile ${sshdConfigValue(pidPath)}",
            "AuthorizedKeysFile ${sshdConfigValue(authorizedKeysPath)}",
            "AllowUsers $authorizedUser",
        )
    if (!configLines.map(String::trim).containsAll(requiredLines)) return null
    return ProcessHandleLinuxManagedSshdProcess(handle)
}

private fun linuxProcessTitleMatchesManagedSshd(
    pid: Long,
    configPath: Path,
): Boolean =
    runCatching {
        val bytes =
            Files.newInputStream(Path.of("/proc", pid.toString(), "cmdline")).use { input ->
                input.readNBytes(LINUX_PROCESS_TITLE_MAX_BYTES + 1)
            }
        if (bytes.size > LINUX_PROCESS_TITLE_MAX_BYTES) return@runCatching false
        val title = bytes.toString(StandardCharsets.UTF_8).replace('\u0000', ' ').trim()
        title.startsWith("sshd:") && title.contains("-f ${configPath.toAbsolutePath().normalize()}")
    }.getOrDefault(false)

private class ProcessHandleLinuxManagedSshdProcess(
    private val handle: ProcessHandle,
) : LinuxManagedSshdProcess {
    override fun isAlive(): Boolean = handle.isAlive

    override fun waitForExit(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "Process wait timeout must not be negative" }
        if (!handle.isAlive) return true
        return runCatching {
            handle.onExit().get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    override fun terminate(timeoutMillis: Long) {
        require(timeoutMillis > 0) { "Process termination timeout must be positive" }
        handle.destroy()
        if (!waitForExit(timeoutMillis)) {
            handle.destroyForcibly()
            waitForExit(timeoutMillis)
        }
    }

    override fun diagnostics(): String = "Reattached to the existing Aegis-managed sshd process"
}

class ProcessLinuxSshdProcessLauncher : LinuxSshdProcessLauncher {
    override fun start(
        command: List<String>,
        workingDirectory: Path,
    ): LinuxManagedSshdProcess {
        require(command.isNotEmpty()) { "sshd command must not be empty" }
        val process =
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        return ProcessLinuxManagedSshdProcess(process)
    }
}

private class ProcessLinuxManagedSshdProcess(
    private val process: Process,
) : LinuxManagedSshdProcess {
    private val output = BoundedProcessDiagnostics(LINUX_OPENSSH_DIAGNOSTIC_BUFFER_BYTES)
    private val reader =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "aegis-user-sshd-output").apply { isDaemon = true }
        }

    init {
        reader.submit { process.inputStream.use(output::readFrom) }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun waitForExit(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "Process wait timeout must not be negative" }
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    override fun terminate(timeoutMillis: Long) {
        require(timeoutMillis > 0) { "Process termination timeout must be positive" }
        val startedAt = System.nanoTime()
        process.destroy()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val remainingMillis = (timeoutMillis - elapsedMillis).coerceAtLeast(1)
            process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)
        }
        reader.shutdownNow()
    }

    override fun diagnostics(): String = output.value()
}

private class BoundedProcessDiagnostics(
    private val maxBytes: Int,
) {
    private val lock = Any()
    private var bytes = ByteArray(0)

    fun readFrom(input: InputStream) {
        val buffer = ByteArray(2048)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            synchronized(lock) {
                val combined = bytes + buffer.copyOf(count)
                bytes =
                    if (combined.size <= maxBytes) {
                        combined
                    } else {
                        combined.copyOfRange(combined.size - maxBytes, combined.size)
                    }
            }
        }
    }

    fun value(): String = synchronized(lock) { bytes.toString(StandardCharsets.UTF_8) }
}

private data class CanonicalLinuxPublicKey(
    val algorithm: String,
    val encodedBlob: String,
    val fingerprint: String,
)

private fun canonicalLinuxPublicKey(value: String): CanonicalLinuxPublicKey {
    val trimmed = value.trim()
    if (trimmed.length !in 1..LINUX_PUBLIC_KEY_MAX_CHARS || '\n' in trimmed || '\r' in trimmed) {
        throw linuxInvalidPublicKey("Expected one bounded OpenSSH public-key record")
    }
    val fields = trimmed.split(Regex("\\s+"), limit = 3)
    if (fields.size < 2 || fields[0] !in LINUX_SUPPORTED_PUBLIC_KEY_ALGORITHMS || !LINUX_BASE64_PATTERN.matches(fields[1])) {
        throw linuxInvalidPublicKey("Unsupported algorithm or malformed Base64 key blob")
    }
    val blob =
        runCatching { Base64.getDecoder().decode(fields[1]) }
            .getOrElse { error -> throw linuxInvalidPublicKey("The OpenSSH key blob is not valid Base64", error) }
    if (blob.isEmpty()) throw linuxInvalidPublicKey("The OpenSSH key blob is empty")
    val digest =
        Base64
            .getEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    val canonicalDigest =
        canonicalOpenSshSha256Fingerprint("SHA256:$digest")
            ?: throw linuxInvalidPublicKey("The OpenSSH key fingerprint is not canonical SHA256")
    return CanonicalLinuxPublicKey(fields[0], fields[1], canonicalDigest)
}

private fun requireDeviceId(
    deviceId: String,
    operation: AegisOpenSshOperation,
) {
    if (!LINUX_DEVICE_ID_PATTERN.matches(deviceId)) {
        throw linuxOpenSshFailure(
            code = "SSH-7303",
            operation = operation,
            stage = "validate-device-id",
            summary = "The device identifier is invalid for SSH key management.",
            cause = "The identifier contains unsupported marker characters",
            retryable = false,
            nextAction = "Use the canonical Aegis device identifier.",
        )
    }
}

private fun linuxInvalidPublicKey(
    cause: String,
    underlying: Throwable? = null,
): AegisOpenSshException =
    linuxOpenSshFailure(
        code = "SSH-7303",
        operation = AegisOpenSshOperation.EnrollKey,
        stage = "validate-public-key",
        summary = "The submitted SSH public key is invalid.",
        cause = cause,
        retryable = false,
        nextAction = "Generate a supported SSH keypair and submit only its public key.",
        underlying = underlying,
    )

private fun linuxOpenSshFailure(
    code: String,
    operation: AegisOpenSshOperation,
    stage: String,
    summary: String,
    cause: String,
    retryable: Boolean,
    nextAction: String,
    underlying: Throwable? = null,
): AegisOpenSshException =
    AegisOpenSshException(
        AegisOpenSshFailure(
            code = code,
            component = "linux-openssh",
            operation = operation,
            stage = stage,
            summary = summary,
            technicalCause = cause,
            expected = "An isolated, user-scoped, public-key-only OpenSSH host",
            actual = cause,
            retryable = retryable,
            nextAction = nextAction,
            underlyingType = underlying?.javaClass?.name,
        ),
        underlying,
    )

private fun linuxAegisKeyTag(deviceId: String): String = "aegis-device:$deviceId"

private fun String.hasExactAegisDeviceTag(tag: String): Boolean {
    val fields = trim().split(Regex("\\s+"), limit = 3)
    return fields.size == 3 && fields[2] == tag
}

private fun sshdConfigValue(path: Path): String {
    val value = path.toAbsolutePath().normalize().toString()
    require('\n' !in value && '\r' !in value && '\u0000' !in value) { "Invalid managed OpenSSH path" }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private fun defaultLinuxAegisOpenSshDirectory(): Path {
    val home = System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: "."
    return Path.of(home, ".aegis", "openssh").toAbsolutePath().normalize()
}

internal fun defaultLinuxSshdPath(): Path =
    listOf(Path.of("/usr/bin/sshd"), Path.of("/usr/sbin/sshd"), Path.of("/sbin/sshd"))
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        ?: Path.of("/usr/bin/sshd")

private const val LINUX_AEGIS_SSH_PORT = 48_222
private const val LINUX_OPENSSH_COMMAND_TIMEOUT_MILLIS = 15_000L
private const val LINUX_OPENSSH_STARTUP_GRACE_MILLIS = 250L
private const val LINUX_OPENSSH_SHUTDOWN_TIMEOUT_MILLIS = 3_000L
private const val LINUX_OPENSSH_MAX_DIAGNOSTIC_LENGTH = 2_000
private const val LINUX_OPENSSH_DIAGNOSTIC_BUFFER_BYTES = 32 * 1024
private const val LINUX_MANAGED_CONFIG_MAX_BYTES = 64 * 1024L
private const val LINUX_PROCESS_TITLE_MAX_BYTES = 4 * 1024
private const val LINUX_AUTHORIZED_KEYS_MAX_BYTES = 1024 * 1024L
private const val LINUX_PUBLIC_KEY_MAX_CHARS = 16_384
private val LINUX_ACCOUNT_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,127}\\$?$")
private val LINUX_DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,128}$")
private val LINUX_BASE64_PATTERN = Regex("^[A-Za-z0-9+/]+={0,3}$")
private val LINUX_SUPPORTED_PUBLIC_KEY_ALGORITHMS =
    setOf(
        "ssh-ed25519",
        "ecdsa-sha2-nistp256",
        "sk-ssh-ed25519@openssh.com",
        "rsa-sha2-512",
        "rsa-sha2-256",
        "ssh-rsa",
    )
private val LINUX_DIRECTORY_PERMISSIONS =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )
private val LINUX_FILE_PERMISSIONS =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
