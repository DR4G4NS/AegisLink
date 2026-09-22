package dev.aegis.remote.core.storage

import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.TurnConfig
import kotlinx.coroutines.flow.Flow

interface DeviceProfileRepository {
    fun observeProfiles(): Flow<List<DeviceProfile>>

    suspend fun getProfile(id: DeviceProfileId): DeviceProfile?

    suspend fun saveProfile(profile: DeviceProfile)

    suspend fun deleteProfile(id: DeviceProfileId)
}

interface RecentConnectionRepository {
    suspend fun recordConnection(
        profileId: DeviceProfileId,
        routeType: String,
        connectedAtEpochMillis: Long,
    )
}

interface AppSettingsRepository {
    suspend fun getBoolean(
        key: String,
        default: Boolean = false,
    ): Boolean

    suspend fun putBoolean(
        key: String,
        value: Boolean,
    )
}

interface RelayConfigRepository {
    suspend fun getRelayConfig(): RelayConfig?

    suspend fun saveRelayConfig(config: RelayConfig)
}

interface TurnConfigRepository {
    suspend fun getTurnConfig(): TurnConfig?

    suspend fun saveTurnConfig(config: TurnConfig)
}
