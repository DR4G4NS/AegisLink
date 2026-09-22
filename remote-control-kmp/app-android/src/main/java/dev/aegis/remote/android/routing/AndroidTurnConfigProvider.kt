package dev.aegis.remote.android.routing

import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.TurnConfig
import dev.aegis.remote.core.nat.TurnConfigProvider
import dev.aegis.remote.core.relay.RelayClient
import dev.aegis.remote.core.security.SecureCredentialStore

class AndroidTurnConfigProvider(
    private val credentialStore: SecureCredentialStore,
    private val relayClientProvider: () -> RelayClient? = { null },
) : TurnConfigProvider {
    override suspend fun getTurnConfig(): StunTurnConfig? {
        val relayClient = relayClientProvider() ?: return null
        return request(relayClient)
    }

    suspend fun request(relayClient: RelayClient): StunTurnConfig {
        val credentials = relayClient.requestTurnCredentials()
        val credentialRef =
            credentialStore.putSecret(
                label = "turn-${credentials.username}",
                secret = credentials.credential.encodeToByteArray(),
            )
        return StunTurnConfig(
            turnConfig =
                TurnConfig(
                    urls = credentials.urls,
                    username = credentials.username,
                    credentialRef = credentialRef.value,
                    expiresAtEpochMillis = credentials.expiresAtEpochMillis,
                ),
        )
    }
}
