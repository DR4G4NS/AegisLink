package dev.aegis.remote.android.home

import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.HostAddress
import java.net.URI

data class DirectAccessDraft(
    val host: String = "",
    val connectionPort: String = "48291",
    val filePort: String = "48222",
) {
    fun applyTo(profile: DeviceProfile): DeviceProfile {
        val normalized = host.trim().removePrefix("[").removeSuffix("]")
        require(normalized.isNotBlank() && normalized.none { it.isWhitespace() || it in "/\\@?#%" })
        val uri =
            runCatching { URI("https", null, normalized, -1, null, null, null) }
                .getOrElse { throw IllegalArgumentException("Invalid address", it) }
        require(uri.host != null && !normalized.contains("://"))
        val port = connectionPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid connection port")
        val sshPort = filePort.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid file port")
        return profile.copy(vpnHost = HostAddress(normalized, port), remoteSshPort = sshPort, advertisedEndpoints = emptyList())
    }
}
