package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.AeadAlgorithm
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.KeyAgreementAlgorithm
import dev.aegis.remote.core.security.AeadCapability
import dev.aegis.remote.core.security.CryptoCapabilityFailure
import dev.aegis.remote.core.security.CryptoCapabilityReport
import dev.aegis.remote.core.security.IdentityCapability
import dev.aegis.remote.core.security.KeyAgreementCapability
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopCryptoCapabilityProbeTest {
    @Test
    fun cachesByCompatibilityInputsAndHonorsForceRefresh() =
        runTest {
            val cache = Files.createTempDirectory("aegis-capability-cache").resolve("capabilities.json")
            var compatibility = listOf("app-1", "windows-build-1", "provider-1", "amd64")
            var evaluations = 0
            val report = p256Report()
            val probe =
                DesktopCryptoCapabilityProbe(
                    privateKeyProtector = XorCapabilityProtector,
                    cacheFile = cache,
                    compatibilityInputs = { compatibility },
                    evaluator = {
                        evaluations += 1
                        report
                    },
                )

            assertEquals(report, probe.evaluate())
            assertEquals(report, probe.evaluate())
            assertEquals(1, evaluations)

            val reopened =
                DesktopCryptoCapabilityProbe(
                    privateKeyProtector = XorCapabilityProtector,
                    cacheFile = cache,
                    compatibilityInputs = { compatibility },
                    evaluator = {
                        evaluations += 1
                        report
                    },
                )
            assertEquals(report, reopened.evaluate())
            assertEquals(1, evaluations)

            compatibility = listOf("app-2", "windows-build-1", "provider-1", "amd64")
            assertEquals(report, reopened.evaluate())
            assertEquals(2, evaluations)
            assertEquals(report, reopened.evaluate(forceRefresh = true))
            assertEquals(3, evaluations)
        }

    @Test
    fun corruptedCacheIsDiscardedAndReprobed() =
        runTest {
            val cache = Files.createTempDirectory("aegis-corrupt-capability-cache").resolve("capabilities.json")
            Files.writeString(cache, "not-json")
            var evaluations = 0
            val probe =
                DesktopCryptoCapabilityProbe(
                    privateKeyProtector = XorCapabilityProtector,
                    cacheFile = cache,
                    compatibilityInputs = { listOf("stable") },
                    evaluator = {
                        evaluations += 1
                        p256Report()
                    },
                )

            assertEquals(p256Report(), probe.evaluate())
            assertEquals(1, evaluations)
            assertTrue(Files.readString(cache).contains("compatibilityKey"))
        }

    @Test
    fun realProbeProvesTheMandatoryP256Suite() {
        val report = evaluateDesktopCryptoCapabilities(XorCapabilityProtector)

        assertTrue(
            report.identityOptions.any {
                it.algorithm == IdentitySignatureAlgorithm.ECDSA_P256_SHA256 && it.supported
            },
        )
        assertTrue(AEGIS_P256_AESGCM_V1 in report.supportedSuites)
    }

    @Test
    fun exactCurveValidationRejectsSameSizeDomainSubstitution() {
        val key =
            KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair().public as ECPublicKey
            }
        requireExactP256PublicKey(key)
        val substituted = SubstitutedEcPublicKey(key)

        assertFailsWith<DeviceIdentityStorageException> {
            requireExactP256PublicKey(substituted)
        }
    }
}

private object XorCapabilityProtector : DesktopPrivateKeyProtector {
    override val scheme: String = "test-xor:v1"

    override fun protect(pkcs8: ByteArray): ByteArray = pkcs8.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

    override fun unprotect(protectedValue: ByteArray): ByteArray = protect(protectedValue)
}

private class SubstitutedEcPublicKey(
    private val delegate: ECPublicKey,
) : ECPublicKey {
    override fun getW() = delegate.w

    override fun getParams(): ECParameterSpec =
        ECParameterSpec(
            delegate.params.curve,
            delegate.params.generator,
            delegate.params.order,
            2,
        )

    override fun getAlgorithm(): String = delegate.algorithm

    override fun getFormat(): String = delegate.format

    override fun getEncoded(): ByteArray = delegate.encoded.copyOf()
}

private fun p256Report(): CryptoCapabilityReport =
    CryptoCapabilityReport(
        identityOptions =
            listOf(
                IdentityCapability(
                    IdentitySignatureAlgorithm.ECDSA_P256_SHA256,
                    supported = true,
                    securityLevel = IdentitySecurityLevel.WRAPPED_SOFTWARE,
                ),
                IdentityCapability(
                    IdentitySignatureAlgorithm.ED25519,
                    supported = false,
                    securityLevel = IdentitySecurityLevel.UNSUPPORTED,
                    failureCode = "IDN-1006_ED25519_SELF_TEST_FAILED",
                ),
            ),
        keyAgreementOptions =
            listOf(
                KeyAgreementCapability(KeyAgreementAlgorithm.ECDH_P256, supported = true),
                KeyAgreementCapability(
                    KeyAgreementAlgorithm.X25519,
                    supported = false,
                    failureCode = "IDN-1006_X25519_SELF_TEST_FAILED",
                ),
            ),
        aeadOptions =
            listOf(
                AeadCapability(AeadAlgorithm.AES_256_GCM, supported = true),
                AeadCapability(
                    AeadAlgorithm.CHACHA20_POLY1305,
                    supported = false,
                    failureCode = "IDN-1006_CHACHA20_POLY1305_SELF_TEST_FAILED",
                ),
            ),
        supportedSuites = listOf(AEGIS_P256_AESGCM_V1),
        recommendedSuite = AEGIS_P256_AESGCM_V1,
        failures =
            listOf(
                CryptoCapabilityFailure("IDN-1006_ED25519_SELF_TEST_FAILED", "ED25519"),
                CryptoCapabilityFailure("IDN-1006_X25519_SELF_TEST_FAILED", "X25519"),
                CryptoCapabilityFailure("IDN-1006_CHACHA20_POLY1305_SELF_TEST_FAILED", "CHACHA20_POLY1305"),
            ),
    )
