package dev.aegis.remote.android.data

import android.content.Context
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.storage.RelayConfigRepository

class AndroidRelayConfigRepository(
    context: Context,
) : RelayConfigRepository {
    private val preferences = context.applicationContext.getSharedPreferences("aegis-relay", Context.MODE_PRIVATE)

    override suspend fun getRelayConfig(): RelayConfig? {
        val relayUrl = preferences.getString(KEY_RELAY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val deviceId =
            preferences
                .getString(KEY_DEVICE_ID, null)
                ?.takeIf { it.isNotBlank() }
                ?.let(::RelayDeviceId)
        return RelayConfig(
            relayUrl = relayUrl,
            deviceId = deviceId,
            authTokenRef = null,
            enabled = preferences.getBoolean(KEY_ENABLED, false),
        )
    }

    override suspend fun saveRelayConfig(config: RelayConfig) {
        preferences
            .edit()
            .putString(KEY_RELAY_URL, config.relayUrl)
            .putString(KEY_DEVICE_ID, config.deviceId?.value)
            .putBoolean(KEY_ENABLED, config.enabled)
            .apply()
    }

    private companion object {
        const val KEY_RELAY_URL = "relayUrl"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_ENABLED = "enabled"
    }
}
