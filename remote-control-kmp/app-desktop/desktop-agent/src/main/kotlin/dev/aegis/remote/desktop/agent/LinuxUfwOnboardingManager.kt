package dev.aegis.remote.desktop.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

const val AEGIS_PAIRING_TCP_PORT: Int = 48291
const val AEGIS_SSH_TCP_PORT: Int = 48222

data class LinuxLanScope(
    val interfaceName: String,
    val cidr: String,
)

sealed interface LinuxUfwInspection {
    data class Ready(
        val scope: LinuxLanScope,
    ) : LinuxUfwInspection

    data object Unsupported : LinuxUfwInspection

    data object UfwInactive : LinuxUfwInspection

    data class Rejected(
        val reason: String,
    ) : LinuxUfwInspection
}

sealed interface LinuxUfwApplyResult {
    data object Applied : LinuxUfwApplyResult

    data object ConsentRequired : LinuxUfwApplyResult

    data class Failed(
        val reason: String,
    ) : LinuxUfwApplyResult
}

fun interface LinuxUfwCommandRunner {
    fun run(arguments: List<String>): LinuxUfwCommandResult
}

data class LinuxUfwCommandResult(
    val exitCode: Int,
    val output: String,
)

fun interface LinuxUfwConfigurationReader {
    fun isEnabled(): Boolean?
}

class LinuxUfwOnboardingManager(
    private val commandRunner: LinuxUfwCommandRunner = ProcessLinuxUfwCommandRunner(),
    private val osName: String = System.getProperty("os.name", ""),
    private val configurationReader: LinuxUfwConfigurationReader = FileLinuxUfwConfigurationReader(),
) {
    fun inspect(): LinuxUfwInspection {
        if (!osName.contains("Linux", ignoreCase = true)) return LinuxUfwInspection.Unsupported
        when (configurationReader.isEnabled()) {
            false -> return LinuxUfwInspection.UfwInactive
            null -> return LinuxUfwInspection.Rejected("Unable to inspect UFW configuration safely")
            true -> Unit
        }

        val defaults = commandRunner.run(listOf("ip", "-o", "-4", "route", "show", "default"))
        if (defaults.exitCode != 0) return LinuxUfwInspection.Rejected("Unable to determine the active LAN interface")
        val defaultLines =
            defaults.output
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        val interfaces = defaultLines.mapNotNull(::defaultInterface)
        if (defaultLines.size != 1 || interfaces.size != 1) {
            return LinuxUfwInspection.Rejected("The active LAN interface is ambiguous")
        }
        val interfaceName = interfaces.single()
        if (!SAFE_INTERFACE.matches(interfaceName) || !isLanInterface(interfaceName)) {
            return LinuxUfwInspection.Rejected("The active interface is not a physical LAN interface")
        }

        val routes =
            commandRunner.run(
                listOf("ip", "-o", "-4", "route", "show", "dev", interfaceName, "proto", "kernel", "scope", "link"),
            )
        if (routes.exitCode != 0) return LinuxUfwInspection.Rejected("Unable to determine the active LAN CIDR")
        val routeLines =
            routes.output
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        val cidrs = routeLines.mapNotNull(::connectedCidr)
        if (routeLines.size != 1 || cidrs.size != 1) {
            return LinuxUfwInspection.Rejected("The active LAN CIDR is ambiguous")
        }
        return LinuxUfwInspection.Ready(LinuxLanScope(interfaceName, cidrs.single()))
    }

    fun apply(
        scope: LinuxLanScope,
        explicitConsent: Boolean,
    ): LinuxUfwApplyResult {
        if (!explicitConsent) return LinuxUfwApplyResult.ConsentRequired
        if (!SAFE_INTERFACE.matches(scope.interfaceName) || !SAFE_CIDR.matches(scope.cidr)) {
            return LinuxUfwApplyResult.Failed("Invalid LAN scope")
        }
        val current = inspect()
        if (current !is LinuxUfwInspection.Ready || current.scope != scope) {
            return LinuxUfwApplyResult.Failed("The active LAN scope changed; inspect it again")
        }
        val result = commandRunner.run(listOf("pkexec", UFW_HELPER, scope.interfaceName, scope.cidr))
        return if (result.exitCode == 0) LinuxUfwApplyResult.Applied else LinuxUfwApplyResult.Failed("Firewall authorization failed")
    }

    private fun defaultInterface(line: String): String? {
        val fields = line.trim().split(Regex("\\s+"))
        val index = fields.indexOf("dev")
        return fields.getOrNull(index + 1)
    }

    private fun isLanInterface(value: String): Boolean {
        val lower = value.lowercase()
        return VIRTUAL_INTERFACE_PREFIXES.none(lower::startsWith)
    }

    private fun connectedCidr(line: String): String? {
        val value = line.trim().substringBefore(' ')
        return value.takeIf { SAFE_CIDR.matches(it) && validIpv4Cidr(it) }
    }

    private fun validIpv4Cidr(value: String): Boolean {
        val (address, prefixText) = value.split('/').takeIf { it.size == 2 } ?: return false
        val prefix = prefixText.toIntOrNull()?.takeIf { it in 1..32 } ?: return false
        val octets = address.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val raw = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        return raw and mask == raw
    }

    private companion object {
        const val UFW_HELPER = "/usr/lib/aegis-remote-desktop/aegis-ufw-onboard"
        val SAFE_INTERFACE = Regex("[A-Za-z0-9_.-]{1,15}")
        val SAFE_CIDR = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}/[0-9]{1,2}")
        val VIRTUAL_INTERFACE_PREFIXES =
            listOf("lo", "docker", "veth", "virbr", "br-", "tun", "tap", "wg", "tailscale", "zt", "podman", "cni")
    }
}

class FileLinuxUfwConfigurationReader(
    private val configuration: Path = Path.of("/etc/ufw/ufw.conf"),
) : LinuxUfwConfigurationReader {
    override fun isEnabled(): Boolean? =
        runCatching {
            Files
                .readAllLines(configuration)
                .map(String::trim)
                .filter { it.startsWith("ENABLED=") }
                .singleOrNull()
                ?.equals("ENABLED=yes", ignoreCase = true)
        }.getOrNull()
}

class ProcessLinuxUfwCommandRunner : LinuxUfwCommandRunner {
    override fun run(arguments: List<String>): LinuxUfwCommandResult {
        require(arguments.isNotEmpty())
        return runCatching {
            val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return LinuxUfwCommandResult(-1, "")
            }
            LinuxUfwCommandResult(process.exitValue(), process.inputStream.bufferedReader().readText())
        }.getOrElse { LinuxUfwCommandResult(-1, "") }
    }
}
