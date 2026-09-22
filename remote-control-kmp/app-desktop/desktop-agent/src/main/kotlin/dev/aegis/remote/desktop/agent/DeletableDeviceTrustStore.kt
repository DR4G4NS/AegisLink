package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.pairing.DeviceTrustStore

/**
 * Desktop trust stores that support permanent record removal.
 *
 * The storage implementation enforces that an authorization must already be
 * revoked. This keeps a UI or another caller from deleting an active trust
 * relationship without first executing the normal revoke/disconnect path.
 */
interface DeletableDeviceTrustStore : DeviceTrustStore {
    suspend fun deleteRevoked(remoteDeviceId: String): DeleteRevokedDeviceResult
}

enum class DeleteRevokedDeviceResult {
    Deleted,
    MustRevokeFirst,
    NotFound,
}
