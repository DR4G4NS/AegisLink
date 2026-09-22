package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.pairing.DeviceTrustStore

class InMemoryDeviceTrustStore : DeletableDeviceTrustStore {
    private val authorizations = mutableMapOf<String, DeviceAuthorization>()

    override suspend fun saveAuthorization(authorization: DeviceAuthorization) {
        authorizations[authorization.remoteDeviceId.value] = authorization
    }

    override suspend fun revoke(remoteDeviceId: String) {
        val current = authorizations[remoteDeviceId] ?: return
        authorizations[remoteDeviceId] = current.copy(revokedAtEpochMillis = System.currentTimeMillis())
    }

    override suspend fun listAuthorizedDevices(): List<DeviceAuthorization> = authorizations.values.sortedBy { it.displayName }

    override suspend fun deleteRevoked(remoteDeviceId: String): DeleteRevokedDeviceResult {
        val authorization = authorizations[remoteDeviceId] ?: return DeleteRevokedDeviceResult.NotFound
        if (authorization.revokedAtEpochMillis == null) return DeleteRevokedDeviceResult.MustRevokeFirst
        authorizations.remove(remoteDeviceId)
        return DeleteRevokedDeviceResult.Deleted
    }
}
