package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.AeadAlgorithm
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.KeyAgreementAlgorithm
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CryptoCapabilityReportTest {
    @Test
    fun reportHasStableSerializableCacheRepresentation() {
        val report =
            CryptoCapabilityReport(
                identityOptions =
                    listOf(
                        IdentityCapability(
                            algorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256,
                            supported = true,
                            securityLevel = IdentitySecurityLevel.OS_KEYSTORE,
                        ),
                    ),
                keyAgreementOptions = listOf(KeyAgreementCapability(KeyAgreementAlgorithm.ECDH_P256, true)),
                aeadOptions = listOf(AeadCapability(AeadAlgorithm.AES_256_GCM, true)),
                supportedSuites = listOf(AEGIS_P256_AESGCM_V1),
                recommendedSuite = AEGIS_P256_AESGCM_V1,
                failures = emptyList(),
            )

        val encoded = Json.encodeToString(report)

        assertEquals(report, Json.decodeFromString<CryptoCapabilityReport>(encoded))
    }
}
