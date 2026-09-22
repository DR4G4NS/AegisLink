package dev.aegis.remote.desktop.agent

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

fun interface DesktopManualConnectionInfoProvider {
    fun inspect(lanHost: String?): DesktopManualConnectionInfo
}

class SystemDesktopManualConnectionInfoProvider(
    private val hostKeyCandidates: List<Path> = defaultOpenSshHostPublicKeyCandidates(),
    private val usernameProvider: () -> String? = ::systemLocalUsername,
) : DesktopManualConnectionInfoProvider {
    override fun inspect(lanHost: String?): DesktopManualConnectionInfo {
        val normalizedHost = lanHost?.trim()?.takeIf(String::isNotBlank)
        val username = usernameProvider()?.trim()?.takeIf(String::isNotBlank)
        val hostKey = inspectHostKey()
        return DesktopManualConnectionInfo(
            lanHost = normalizedHost.toManualValue(detailCode = "LAN_HOST_UNAVAILABLE"),
            localUsername = username.toManualValue(detailCode = "LOCAL_USERNAME_UNAVAILABLE"),
            sshHostKeyFingerprint = hostKey.fingerprint,
            sshHostKeyAlgorithm = hostKey.algorithm,
        )
    }

    private fun inspectHostKey(): HostKeyInspection {
        var accessDenied = false
        var lastError: Throwable? = null
        hostKeyCandidates.forEach { candidate ->
            try {
                if (!Files.isRegularFile(candidate)) return@forEach
                val fields = Files.readString(candidate).trim().split(Regex("\\s+"), limit = 3)
                require(fields.size >= 2) { "Invalid OpenSSH public host-key format" }
                val keyBlob = Base64.getDecoder().decode(fields[1])
                val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
                val fingerprint = "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
                return HostKeyInspection(
                    fingerprint =
                        ManualConnectionValue(
                            value = fingerprint,
                            status = ManualConnectionValueStatus.Available,
                        ),
                    algorithm = fields[0],
                )
            } catch (denied: AccessDeniedException) {
                accessDenied = true
                lastError = denied
            } catch (error: Throwable) {
                lastError = error
            }
        }
        return when {
            accessDenied -> {
                HostKeyInspection(
                    fingerprint =
                        ManualConnectionValue(
                            status = ManualConnectionValueStatus.PermissionRequired,
                            detailCode = "SSH_HOST_KEY_PERMISSION_REQUIRED",
                        ),
                )
            }

            lastError != null -> {
                HostKeyInspection(
                    fingerprint =
                        ManualConnectionValue(
                            status = ManualConnectionValueStatus.Error,
                            detailCode = "SSH_HOST_KEY_INVALID_OR_UNREADABLE",
                        ),
                )
            }

            else -> {
                HostKeyInspection(
                    fingerprint =
                        ManualConnectionValue(
                            status = ManualConnectionValueStatus.NotAvailable,
                            detailCode = "SSH_HOST_KEY_NOT_FOUND",
                        ),
                )
            }
        }
    }
}

private data class HostKeyInspection(
    val fingerprint: ManualConnectionValue,
    val algorithm: String? = null,
)

private fun String?.toManualValue(detailCode: String): ManualConnectionValue =
    if (this == null) {
        ManualConnectionValue(
            status = ManualConnectionValueStatus.NotAvailable,
            detailCode = detailCode,
        )
    } else {
        ManualConnectionValue(value = this, status = ManualConnectionValueStatus.Available)
    }

private fun systemLocalUsername(): String? =
    System.getenv("USERNAME")
        ?: System.getenv("USER")
        ?: System.getProperty("user.name")

private fun defaultOpenSshHostPublicKeyCandidates(): List<Path> {
    val candidates = mutableListOf<Path>()
    System.getenv("PROGRAMDATA")?.takeIf(String::isNotBlank)?.let { programData ->
        val sshDirectory = Path.of(programData, "ssh")
        candidates.add(sshDirectory.resolve("ssh_host_ed25519_key.pub"))
        candidates.add(sshDirectory.resolve("ssh_host_ecdsa_key.pub"))
        candidates.add(sshDirectory.resolve("ssh_host_rsa_key.pub"))
    }
    val unixSshDirectory = Path.of("/etc/ssh")
    candidates.add(unixSshDirectory.resolve("ssh_host_ed25519_key.pub"))
    candidates.add(unixSshDirectory.resolve("ssh_host_ecdsa_key.pub"))
    candidates.add(unixSshDirectory.resolve("ssh_host_rsa_key.pub"))
    return candidates
}
