package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceProfile(
    val id: DeviceProfileId,
    val displayName: String,
    val localHost: HostAddress,
    val vpnHost: HostAddress? = null,
    val relayDeviceId: RelayDeviceId? = null,
    val sshPort: Int = 22,
    val username: String,
    val authMethod: AuthMethod,
    val credentialsRef: SshCredentialsRef? = null,
    val localProtocolTokenRef: SshCredentialsRef? = null,
    val localAgentCertificateFingerprint: String? = null,
    /**
     * The Android device authorization principal recorded by the paired host.
     * This is intentionally independent from [id], which identifies the host
     * profile itself and must remain stable when the same phone pairs with
     * more than one Windows PC.
     */
    val authorizedDeviceId: String? = null,
    val pairedHostIdentity: DevicePublicIdentity? = null,
    val hostKeyFingerprint: HostKeyFingerprint? = null,
    val wakeOnLanConfig: WakeOnLanConfig? = null,
    val availableWakeOnLanConfigs: List<WakeOnLanConfig> = emptyList(),
    val lastConnectedAtEpochMillis: Long? = null,
    val lastSuccessfulRoute: ConnectionRouteType? = null,
    val defaultMonitorId: MonitorId? = null,
    val qualityPreference: QualityMode = QualityMode.Balanced,
    val permissions: DevicePermissions = DevicePermissions(),
    val remoteAccessEnabled: Boolean = false,
    val advertisedEndpoints: List<AdvertisedEndpoint> = emptyList(),
    /** External terminal/file port; vpnHost.port belongs to the visual protocol. */
    val remoteSshPort: Int? = null,
)
