package dev.aegis.remote.desktop.agent

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AegisOpenSshProvisionerTest {
    private val scripts =
        createTempDirectory("aegis-openssh-scripts").also { directory ->
            SCRIPT_NAMES.forEach { name -> Files.writeString(directory.resolve(name), "# test") }
        }

    @AfterTest
    fun cleanup() {
        Files.walk(scripts).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `provision parses isolated instance state and sends bounded arguments`() =
        runTest {
            val runner = RecordingOpenSshRunner(success(STATE_JSON))
            val provisioner = provisioner(runner)

            val state = provisioner.provision(port = 48222, authorizedUser = "workstation\\ian")

            assertEquals("AegisOpenSSH", state.serviceName)
            assertEquals("Running", state.serviceStatus)
            assertEquals(48222, state.port)
            assertEquals("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", state.hostKeyFingerprint)
            val command = runner.commands.single()
            assertTrue(command.contains("-Port"))
            assertTrue(command.contains("48222"))
            assertTrue(command.contains("-AuthorizedUser"))
            assertTrue(command.contains("workstation\\ian"))
            assertTrue(command.lastIndexOf("-File") < command.lastIndexOf("-Port"))
        }

    @Test
    fun `old installations require repair before advertising app scoped access`() =
        runTest {
            val manager = provisioner(RecordingOpenSshRunner(success(STATE_JSON)))
            try {
                val error = assertFailsWith<IllegalStateException> { manager.startAccess() }
                assertTrue(error.message.orEmpty().contains("SSH-7334"))
            } finally {
                manager.shutdown()
            }
        }

    @Test
    fun `inspect preserves script failure code and causal evidence`() =
        runTest {
            val runner = RecordingOpenSshRunner(OpenSshCommandResult(1, "", "SSH-7311 SERVICE_NAME_COLLISION"))
            val error = assertFailsWith<AegisOpenSshException> { provisioner(runner).inspect() }

            assertEquals("SSH-7311", error.failure.code)
            assertEquals(AegisOpenSshOperation.Inspect, error.failure.operation)
            assertEquals("inspect-managed-instance", error.failure.stage)
            assertTrue(
                error.failure.technicalCause
                    .orEmpty()
                    .contains("SERVICE_NAME_COLLISION"),
            )
            assertFalse(error.failure.retryable)
            assertTrue(error.failure.nextAction.contains("collision", ignoreCase = true))
        }

    @Test
    fun `public key is validated before privileged runner is called`() =
        runTest {
            val runner = RecordingOpenSshRunner(success(ENROLLMENT_JSON))
            val error =
                assertFailsWith<AegisOpenSshException> {
                    provisioner(runner).enrollPublicKey("phone-1", "ssh-ed25519 not-base64!!")
                }

            assertEquals("SSH-7303", error.failure.code)
            assertEquals("validate-public-key", error.failure.stage)
            assertTrue(runner.commands.isEmpty())
        }

    @Test
    fun `enrollment passes a one-record temporary file and removes it afterwards`() =
        runTest {
            val runner =
                RecordingOpenSshRunner(success(ENROLLMENT_JSON)) { command ->
                    val keyPath = Path.of(command[command.indexOf("-PublicKeyFile") + 1])
                    assertEquals("ssh-ed25519 AAAA", Files.readString(keyPath))
                }
            val result = provisioner(runner).enrollPublicKey("phone-1", "ssh-ed25519 AAAA untrusted comment")

            assertEquals("phone-1", result.deviceId)
            assertTrue(result.added)
            val command = runner.commands.single()
            val keyPath = Path.of(command[command.indexOf("-PublicKeyFile") + 1])
            assertFalse(Files.exists(keyPath))
            assertTrue(command.contains("-DeviceId"))
        }

    @Test
    fun `authorized key enrollment returns the complete bootstrap profile`() =
        runTest {
            val runner =
                SequencedOpenSshRunner(
                    success(ENROLLMENT_JSON),
                    success(STATE_JSON),
                )
            val result =
                WindowsAegisOpenSshProvisioner(
                    scriptDirectory = scripts,
                    runner = runner,
                    powershellExecutable = "powershell-test.exe",
                    timeoutMillis = 1_000,
                ).enrollAuthorizedKey(
                    publicKey = "ssh-ed25519 AAAA",
                    deviceId = "phone-1",
                )

            assertEquals("workstation\\ian", result.username)
            assertEquals(48222, result.port)
            assertEquals("SHA256", result.hostKeyFingerprint.algorithm)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", result.hostKeyFingerprint.value)
            assertTrue(result.wakeOnLanConfigs.isEmpty())
            assertEquals(2, runner.commands.size)
        }

    @Test
    fun `authorized key enrollment reuses a previous inspect instead of a second PowerShell`() =
        runTest {
            val runner =
                SequencedOpenSshRunner(
                    success(STATE_JSON),
                    success(ENROLLMENT_JSON),
                )
            val provisioner =
                WindowsAegisOpenSshProvisioner(
                    scriptDirectory = scripts,
                    runner = runner,
                    powershellExecutable = "powershell-test.exe",
                    timeoutMillis = 1_000,
                )

            provisioner.inspect()
            val result =
                provisioner.enrollAuthorizedKey(
                    publicKey = "ssh-ed25519 AAAA",
                    deviceId = "phone-1",
                )

            assertEquals("workstation\\ian", result.username)
            assertEquals(48222, result.port)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", result.hostKeyFingerprint.value)
            assertEquals(2, runner.commands.size)
        }

    @Test
    fun `timeout is reported with stable retryable failure`() =
        runTest {
            val runner = RecordingOpenSshRunner(OpenSshCommandResult(-1, "", "servicing still running", timedOut = true))
            val error = assertFailsWith<AegisOpenSshException> { provisioner(runner).provision() }

            assertEquals("SSH-7304", error.failure.code)
            assertTrue(error.failure.retryable)
            assertEquals("PowerShell was terminated after the timeout", error.failure.actual)
        }

    private fun provisioner(runner: OpenSshCommandRunner) =
        WindowsAegisOpenSshProvisioner(
            scriptDirectory = scripts,
            runner = runner,
            powershellExecutable = "powershell-test.exe",
            timeoutMillis = 1_000,
        )

    private fun success(stdout: String) = OpenSshCommandResult(0, stdout, "")

    private companion object {
        const val STATE_JSON =
            "{" +
                "\"serviceName\":\"AegisOpenSSH\"," +
                "\"serviceStatus\":\"Running\"," +
                "\"port\":48222," +
                "\"rootDirectory\":\"C:\\\\ProgramData\\\\Aegis\\\\OpenSSH\"," +
                "\"configPath\":\"C:\\\\ProgramData\\\\Aegis\\\\OpenSSH\\\\sshd_config\"," +
                "\"authorizedKeysPath\":\"C:\\\\ProgramData\\\\Aegis\\\\OpenSSH\\\\authorized_keys\"," +
                "\"hostKeyPath\":\"C:\\\\ProgramData\\\\Aegis\\\\OpenSSH\\\\ssh_host_ed25519_key\"," +
                "\"hostKeyFingerprint\":\"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"," +
                "\"hostKeyAlgorithm\":\"ssh-ed25519\"," +
                "\"firewallRuleName\":\"Aegis OpenSSH (Private and Domain)\"," +
                "\"sshdPath\":\"C:\\\\Windows\\\\System32\\\\OpenSSH\\\\sshd.exe\"," +
                "\"authorizedUser\":\"workstation\\\\ian\"" +
                "}"

        const val ENROLLMENT_JSON =
            "{" +
                "\"deviceId\":\"phone-1\"," +
                "\"fingerprint\":\"SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB\"," +
                "\"algorithm\":\"ssh-ed25519\"," +
                "\"added\":true," +
                "\"authorizedKeysPath\":\"C:\\\\ProgramData\\\\Aegis\\\\OpenSSH\\\\authorized_keys\"" +
                "}"

        val SCRIPT_NAMES =
            listOf(
                "aegis-openssh-managed-instance.ps1",
                "install-aegis-openssh.ps1",
                "inspect-aegis-openssh.ps1",
                "enroll-aegis-ssh-key.ps1",
                "remove-aegis-ssh-key.ps1",
                "uninstall-aegis-openssh.ps1",
            )
    }
}

private class RecordingOpenSshRunner(
    private val result: OpenSshCommandResult,
    private val onRun: (List<String>) -> Unit = {},
) : OpenSshCommandRunner {
    val commands = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): OpenSshCommandResult {
        commands += command
        onRun(command)
        return result
    }
}

private class SequencedOpenSshRunner(
    vararg results: OpenSshCommandResult,
) : OpenSshCommandRunner {
    private val results = ArrayDeque(results.toList())
    val commands = mutableListOf<List<String>>()

    override suspend fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): OpenSshCommandResult {
        commands += command
        return results.removeFirst()
    }
}
