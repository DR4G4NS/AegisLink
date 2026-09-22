package dev.aegis.remote.core.monitor

import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo

interface MonitorProvider {
    suspend fun listMonitors(): List<MonitorInfo>
}

class MonitorSelector {
    fun select(
        monitors: List<MonitorInfo>,
        preferred: MonitorId?,
    ): MonitorInfo? =
        preferred?.let { id -> monitors.firstOrNull { it.id == id } }
            ?: monitors.firstOrNull { it.primary }
            ?: monitors.firstOrNull()
}
