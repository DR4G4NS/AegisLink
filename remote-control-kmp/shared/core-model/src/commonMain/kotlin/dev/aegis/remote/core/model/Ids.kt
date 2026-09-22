package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class DeviceProfileId(
    val value: String,
)

@Serializable
@JvmInline
value class RemoteDeviceId(
    val value: String,
)

/**
 * Cryptographic identity derived from a device signing public key.
 *
 * This is deliberately distinct from [RelayDeviceId], which is only a relay
 * locator and must never be used as an authorization principal.
 */
@Serializable
@JvmInline
value class DeviceId(
    val value: String,
)

@Serializable
@JvmInline
value class RelayDeviceId(
    val value: String,
)

@Serializable
@JvmInline
value class SshCredentialsRef(
    val value: String,
)

@Serializable
@JvmInline
value class SessionId(
    val value: String,
)

@Serializable
@JvmInline
value class MonitorId(
    val value: String,
)
