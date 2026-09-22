package dev.aegis.remote.android.data

import android.content.Context
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.aegis.remote.android.db.AegisDatabase
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.HostKeyFingerprint
import dev.aegis.remote.core.model.MacAddress
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SshCredentialsRef
import dev.aegis.remote.core.model.WakeOnLanConfig
import dev.aegis.remote.core.storage.DeviceProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import dev.aegis.remote.android.db.DeviceProfile as DeviceProfileRow

class AndroidDeviceProfileRepository private constructor(
    private val database: AegisDatabase,
) : DeviceProfileRepository {
    override fun observeProfiles(): Flow<List<DeviceProfile>> =
        database.deviceProfileQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun getProfile(id: DeviceProfileId): DeviceProfile? =
        database.deviceProfileQueries
            .selectById(id.value)
            .executeAsOneOrNull()
            ?.toDomain()

    override suspend fun saveProfile(profile: DeviceProfile) {
        database.deviceProfileQueries.upsert(
            id = profile.id.value,
            displayName = profile.displayName,
            localHost = profile.localHost.host,
            localPort = profile.localHost.port?.toLong(),
            vpnHost = profile.vpnHost?.host,
            vpnPort = profile.vpnHost?.port?.toLong(),
            relayDeviceId = profile.relayDeviceId?.value,
            sshPort = profile.sshPort.toLong(),
            username = profile.username,
            authMethod = profile.authMethod.name,
            credentialsRef = profile.credentialsRef?.value,
            localProtocolTokenRef = profile.localProtocolTokenRef?.value,
            localAgentCertificateFingerprint = profile.localAgentCertificateFingerprint,
            authorizedDeviceId = profile.authorizedDeviceId,
            pairedHostIdentityJson = profile.pairedHostIdentity?.let { profileJson.encodeToString(DevicePublicIdentity.serializer(), it) },
            hostKeyAlgorithm = profile.hostKeyFingerprint?.algorithm,
            hostKeyValue = profile.hostKeyFingerprint?.value,
            macAddress = profile.wakeOnLanConfig?.macAddress?.value,
            wolBroadcastAddress = profile.wakeOnLanConfig?.broadcastAddress,
            wolPort = profile.wakeOnLanConfig?.port?.toLong(),
            wakeOnLanConfigsJson =
                profile.availableWakeOnLanConfigs
                    .takeIf { it.isNotEmpty() }
                    ?.let { profileJson.encodeToString(ListSerializer(WakeOnLanConfig.serializer()), it) },
            lastConnectedAt = profile.lastConnectedAtEpochMillis,
            lastSuccessfulRoute = profile.lastSuccessfulRoute?.name,
            defaultMonitorId = profile.defaultMonitorId?.value,
            qualityPreference = profile.qualityPreference.name,
            permissionTerminal = profile.permissions.terminal.asLong(),
            permissionVisual = profile.permissions.visual.asLong(),
            permissionInput = profile.permissions.input.asLong(),
            permissionSftp = profile.permissions.sftp.asLong(),
            permissionClipboard = profile.permissions.clipboard.asLong(),
            permissionWakeOnLan = profile.permissions.wakeOnLan.asLong(),
            permissionRemoteAccess = profile.permissions.remoteAccess.asLong(),
            remoteAccessEnabled = profile.remoteAccessEnabled.asLong(),
            remoteSshPort = profile.remoteSshPort?.toLong(),
        )
    }

    override suspend fun deleteProfile(id: DeviceProfileId) {
        database.deviceProfileQueries.deleteById(id.value)
    }

    companion object {
        fun create(context: Context): AndroidDeviceProfileRepository {
            val driver =
                AndroidSqliteDriver(
                    schema = AegisDatabase.Schema,
                    context = context.applicationContext,
                    name = "aegis.db",
                )
            return AndroidDeviceProfileRepository(AegisDatabase(driver))
        }
    }
}

@Suppress("CyclomaticComplexMethod")
private fun DeviceProfileRow.toDomain(): DeviceProfile {
    val legacyWakeConfig =
        macAddress?.let { mac ->
            WakeOnLanConfig(
                macAddress = MacAddress(mac),
                broadcastAddress = wolBroadcastAddress ?: "255.255.255.255",
                port = wolPort?.toInt() ?: 9,
            )
        }
    val wakeConfigs =
        wakeOnLanConfigsJson
            ?.let { encoded ->
                runCatching {
                    profileJson.decodeFromString(ListSerializer(WakeOnLanConfig.serializer()), encoded)
                }.getOrNull()
            }.orEmpty()
            .ifEmpty { listOfNotNull(legacyWakeConfig) }
    val selectedWakeConfig =
        legacyWakeConfig?.let { selected ->
            wakeConfigs.firstOrNull { candidate ->
                candidate.macAddress == selected.macAddress &&
                    candidate.broadcastAddress == selected.broadcastAddress &&
                    candidate.port == selected.port
            } ?: selected
        } ?: wakeConfigs.firstOrNull()
    val hostKey =
        if (hostKeyAlgorithm != null && hostKeyValue != null) {
            HostKeyFingerprint(hostKeyAlgorithm, hostKeyValue)
        } else {
            null
        }
    return DeviceProfile(
        id = DeviceProfileId(id),
        displayName = displayName,
        localHost = HostAddress(localHost, localPort?.toInt()),
        vpnHost = vpnHost?.let { HostAddress(it, vpnPort?.toInt()) },
        relayDeviceId = relayDeviceId?.let(::RelayDeviceId),
        sshPort = sshPort.toInt(),
        username = username,
        authMethod = enumValueOrDefault(authMethod, AuthMethod.Password),
        credentialsRef = credentialsRef?.let(::SshCredentialsRef),
        localProtocolTokenRef = localProtocolTokenRef?.let(::SshCredentialsRef),
        localAgentCertificateFingerprint = localAgentCertificateFingerprint,
        authorizedDeviceId = authorizedDeviceId,
        pairedHostIdentity =
            pairedHostIdentityJson?.let { encoded ->
                runCatching { profileJson.decodeFromString(DevicePublicIdentity.serializer(), encoded) }.getOrNull()
            },
        hostKeyFingerprint = hostKey,
        wakeOnLanConfig = selectedWakeConfig,
        availableWakeOnLanConfigs = wakeConfigs,
        lastConnectedAtEpochMillis = lastConnectedAt,
        lastSuccessfulRoute = lastSuccessfulRoute?.let { enumValueOrNull<ConnectionRouteType>(it) },
        defaultMonitorId = defaultMonitorId?.let(::MonitorId),
        qualityPreference = enumValueOrDefault(qualityPreference, QualityMode.Balanced),
        permissions =
            DevicePermissions(
                terminal = permissionTerminal.asBoolean(),
                visual = permissionVisual.asBoolean(),
                input = permissionInput.asBoolean(),
                sftp = permissionSftp.asBoolean(),
                clipboard = permissionClipboard.asBoolean(),
                wakeOnLan = permissionWakeOnLan.asBoolean(),
                remoteAccess = permissionRemoteAccess.asBoolean(),
            ),
        remoteAccessEnabled = remoteAccessEnabled.asBoolean(),
        remoteSshPort = remoteSshPort?.toInt(),
    )
}

private fun Boolean.asLong(): Long = if (this) 1L else 0L

private fun Long.asBoolean(): Boolean = this != 0L

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? = enumValues<T>().firstOrNull { it.name == value }

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String,
    default: T,
): T = enumValueOrNull<T>(value) ?: default

private val profileJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
