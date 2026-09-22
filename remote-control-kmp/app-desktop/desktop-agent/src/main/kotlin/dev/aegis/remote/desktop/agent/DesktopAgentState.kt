package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import kotlinx.serialization.Serializable

@Serializable
data class DesktopAgentState(
    val visible: Boolean = true,
    val pairingServerRunning: Boolean = false,
    val pairingUrl: String? = null,
    val pairingCode: String? = null,
    val agentFingerprint: String? = null,
    val pairingQrPayload: String? = null,
    val pairingQrExpiresAtEpochMillis: Long? = null,
    val pairingQrRefreshing: Boolean = false,
    val relayConnected: Boolean = false,
    val relayUrl: String? = null,
    val relayDeviceId: String? = null,
    val relayConfigurationBusy: Boolean = false,
    val relayConfigurationMessage: String? = null,
    val relayConfigurationMessageCode: RelayConfigurationMessageCode? = null,
    val relayConfigurationMessageContext: Map<String, String> = emptyMap(),
    val remoteAccessEnabled: Boolean = false,
    val remoteInputEnabled: Boolean = true,
    val clipboardSyncEnabled: Boolean = false,
    val autostartAvailable: Boolean = false,
    val autostartEnabled: Boolean = false,
    val autostartMessage: String? = null,
    val autostartMessageCode: AutostartMessageCode? = null,
    val autostartMessageContext: Map<String, String> = emptyMap(),
    val openSshAvailable: Boolean = false,
    val manualConnectionInfo: DesktopManualConnectionInfo = DesktopManualConnectionInfo(),
    val captureCapability: CapabilityStatus = CapabilityStatus.Unknown,
    val inputCapability: CapabilityStatus = CapabilityStatus.Unknown,
    val linuxPreflight: LinuxPreflightReport? = null,
    val windowsPreflight: WindowsPreflightReport? = null,
    val monitors: List<MonitorInfo> = emptyList(),
    val authorizedDevices: List<DeviceAuthorization> = emptyList(),
    /** Set when the persisted trust store cannot be read and must be recovered explicitly. */
    val trustStoreRecoveryRequired: Boolean = false,
    val trustStoreFailureReason: TrustStoreFailureReason? = null,
    /** Revoked devices whose managed SSH key removal is being retried. */
    val sshKeyRemovalActionsInProgress: Set<String> = emptySet(),
    val deviceRecordActionsInProgress: Set<String> = emptySet(),
    val permissionChangesInProgress: Set<String> = emptySet(),
    val permissionChangeErrors: Map<String, String> = emptyMap(),
    val lastDeviceRecordAction: DeviceRecordActionResult? = null,
    val pendingPairingRequests: List<DesktopPairingRequest> = emptyList(),
    val pendingRelaySessions: List<DesktopRelaySessionRequest> = emptyList(),
    val logs: List<AgentLogEntry> = emptyList(),
)

@Serializable
enum class CapabilityStatus {
    Unknown,
    Available,
    Degraded,
    PermissionRequired,
    Unavailable,
}

@Serializable
data class DesktopPairingRequest(
    val requestId: String,
    val deviceName: String,
    val fingerprint: String,
    val requestedAtEpochMillis: Long,
    val remote: Boolean,
    val localProtocolToken: String? = null,
    val localHost: String? = null,
    val publicIdentity: DevicePublicIdentity? = null,
    val sshPublicKey: String? = null,
)

@Serializable
data class DesktopRelaySessionRequest(
    val sessionId: SessionId,
    val sourceRelayDeviceId: RelayDeviceId,
    val targetRelayDeviceId: RelayDeviceId,
    val expiresAtEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val sourceDisplayName: String? = null,
    val sourcePublicKeyFingerprint: String? = null,
    val sourcePublicIdentity: DevicePublicIdentity? = null,
    val decisionInProgress: Boolean = false,
)

@Serializable
data class AgentLogEntry(
    val timestampEpochMillis: Long,
    val level: String,
    val message: String,
    val eventCode: AgentLogEventCode = AgentLogEventCode.Generic,
    val context: Map<String, String> = emptyMap(),
    val errorType: String? = null,
)

@Serializable
enum class AgentLogEventCode {
    Generic,
    PairingServerStarted,
    PairingServerStartFailed,
    PairingHttpRequestReceived,
    PairingRequestValidated,
    PairingRequestDispatched,
    PairingRequestVisible,
    PairingRequestDispatchRejected,
    PairingStatusPolled,
    TrustStoreUnavailable,
    TrustStoreRecovered,
    TrustStoreRecoveryFailed,
    PairingRequestReceived,
    PairingRequestExpired,
    PairingRequestApproved,
    PairingApprovalFailed,
    PairingRequestRejected,
    LocalProtocolAuthorizationFailed,
    DeviceRevoked,
    SshKeyRemovalPending,
    SshKeyRemovalCompleted,
    DeviceRecordDeleted,
    DeviceRecordDeletionRefused,
    DeviceRecordDeletionFailed,
    RelayConfigurationChanged,
    RelayConnectionChanged,
    RelayIdentityChanged,
    RelaySessionChanged,
}

@Serializable
data class DesktopManualConnectionInfo(
    val lanHost: ManualConnectionValue = ManualConnectionValue(),
    val localUsername: ManualConnectionValue = ManualConnectionValue(),
    val sshHostKeyFingerprint: ManualConnectionValue = ManualConnectionValue(),
    val sshHostKeyAlgorithm: String? = null,
)

@Serializable
data class ManualConnectionValue(
    val value: String? = null,
    val status: ManualConnectionValueStatus = ManualConnectionValueStatus.NotAvailable,
    val detailCode: String? = null,
)

@Serializable
enum class ManualConnectionValueStatus {
    Available,
    NotAvailable,
    PermissionRequired,
    Error,
}

@Serializable
data class DeviceRecordActionResult(
    val remoteDeviceId: String,
    val outcome: DeviceRecordActionOutcome,
    val timestampEpochMillis: Long,
)

@Serializable
enum class DeviceRecordActionOutcome {
    Deleted,
    MustRevokeFirst,
    NotFound,
    Unsupported,
    SshKeyRemovalPending,
    Failed,
}

@Serializable
enum class RelayConfigurationMessageCode {
    NotConfigured,
    Loaded,
    ValidationFailed,
    Saving,
    SavedConnecting,
    SaveFailed,
    Removing,
    Removed,
    RemoveFailed,
    Connected,
    ConnectionFailed,
    IdentityRotating,
    IdentityRotated,
    IdentityRotationPending,
    IdentityRotationFailed,
}

@Serializable
enum class RelayValidationReason {
    UrlRequired,
    InvalidUrl,
    CompleteHttpUrlRequired,
    CredentialsOrFragmentNotAllowed,
    InvalidDeviceId,
}

@Serializable
enum class AutostartMessageCode {
    Path,
    Unsupported,
    PackagedCommandRequired,
    UpdateFailed,
}

fun defaultDesktopPermissions(remote: Boolean): DevicePermissions =
    DevicePermissions(
        terminal = true,
        visual = true,
        input = true,
        sftp = true,
        clipboard = true,
        wakeOnLan = false,
        remoteAccess = remote,
    )

fun defaultRemoteRelayPermissions(): DevicePermissions =
    DevicePermissions(
        terminal = false,
        visual = true,
        input = true,
        sftp = false,
        clipboard = true,
        wakeOnLan = false,
        remoteAccess = true,
    )
