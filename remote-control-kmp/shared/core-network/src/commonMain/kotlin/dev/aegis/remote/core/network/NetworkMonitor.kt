package dev.aegis.remote.core.network

import kotlinx.coroutines.flow.Flow

enum class NetworkKind {
    Offline,
    Wifi,
    Cellular,
    Ethernet,
    Vpn,
    Unknown,
}

data class NetworkSnapshot(
    val kind: NetworkKind,
    val metered: Boolean,
    val vpnActive: Boolean,
)

interface NetworkMonitor {
    val snapshots: Flow<NetworkSnapshot>

    suspend fun current(): NetworkSnapshot
}
