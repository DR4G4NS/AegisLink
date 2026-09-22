package dev.aegis.remote.android.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import dev.aegis.remote.core.model.AEGIS_25519_CHACHA_V1
import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.AeadAlgorithm
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.KeyAgreementAlgorithm
import dev.aegis.remote.core.security.AeadCapability
import dev.aegis.remote.core.security.CryptoCapabilityFailure
import dev.aegis.remote.core.security.CryptoCapabilityProbe
import dev.aegis.remote.core.security.CryptoCapabilityReport
import dev.aegis.remote.core.security.ExactP256Curve
import dev.aegis.remote.core.security.IdentityCapability
import dev.aegis.remote.core.security.IdentityRotationPhase
import dev.aegis.remote.core.security.KeyAgreementCapability
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.security.PreparedDeviceIdentityRotation
import dev.aegis.remote.core.security.TransactionalDeviceIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Persistent, non-exportable Android identity with verified algorithm agility. */
class AndroidDeviceIdentityStore(
    context: Context,
    private val capabilityProbe: CryptoCapabilityProbe = AndroidCryptoCapabilityProbe(context),
) : TransactionalDeviceIdentityStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val keyStore: KeyStore by lazy { androidKeyStore() }

    override suspend fun capabilityReport(): CryptoCapabilityReport = capabilityProbe.evaluate()

    override suspend fun getOrCreate(): LocalDeviceIdentity =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = readMetadata()
                if (existing != null) {
                    if (!keyStore.containsAlias(existing.alias)) {
                        throw AndroidDeviceIdentityUnavailableException(
                            "The active Android Keystore identity key is missing; refusing to silently replace it.",
                        )
                    }
                    val pending = readPendingMetadata()
                    if (pending?.phase == IdentityRotationPhase.RemoteConfirmed) {
                        return@withContext commitPendingRotation(existing, pending)
                    }
                    return@withContext loadIdentity(existing)
                }
                if (readPendingMetadata() != null) {
                    throw AndroidDeviceIdentityUnavailableException(
                        "Prepared rotation exists without an active identity; refusing replacement generation.",
                    )
                }
                val report = capabilityProbe.evaluate()
                val selected =
                    report.identityOptions.firstOrNull {
                        it.supported && it.algorithm == IdentitySignatureAlgorithm.ECDSA_P256_SHA256
                    } ?: report.identityOptions.firstOrNull {
                        it.supported && it.algorithm == IdentitySignatureAlgorithm.ED25519
                    } ?: throw AndroidDeviceIdentityUnavailableException(
                        "No verified non-exportable Android Keystore identity algorithm is available.",
                    )
                createAndActivate(INITIAL_KEY_GENERATION, selected.algorithm, selected.securityLevel)
            }
        }

    override suspend fun rotate(reason: KeyRotationReason): LocalDeviceIdentity =
        throw AndroidDeviceIdentityUnavailableException(
            "Local-only identity rotation is disabled; use the relay identity lifecycle coordinator for ${reason.label()}.",
        )

    override suspend fun prepareRotation(reason: KeyRotationReason): PreparedDeviceIdentityRotation =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current =
                    readMetadata() ?: throw AndroidDeviceIdentityUnavailableException(
                        "A local identity must exist before it can be rotated for ${reason.label()}.",
                    )
                if (!keyStore.containsAlias(current.alias)) {
                    throw AndroidDeviceIdentityUnavailableException("The active identity key is missing; rotation is unsafe.")
                }
                readPendingMetadata()?.let { pending ->
                    return@withContext preparedRotation(current, pending)
                }
                val next =
                    try {
                        Math.addExact(current.generation, 1L)
                    } catch (error: ArithmeticException) {
                        throw AndroidDeviceIdentityUnavailableException("Device key generation overflowed.", error)
                    }
                val alias = aliasFor(current.algorithm, next)
                if (keyStore.containsAlias(alias)) {
                    deleteAliasBestEffort(alias)
                    if (keyStore.containsAlias(alias)) {
                        throw AndroidDeviceIdentityUnavailableException(
                            "An inactive replacement identity could not be cleaned up; refusing rotation.",
                        )
                    }
                }
                val replacement = createIdentityMetadata(next, current.algorithm, current.securityLevel)
                val pending =
                    PendingIdentityRotation(
                        operationId = UUID.randomUUID().toString(),
                        reason = reason,
                        phase = IdentityRotationPhase.Prepared,
                        replacement = replacement,
                    )
                if (!writePendingMetadata(pending)) {
                    deleteAliasBestEffort(replacement.alias)
                    throw AndroidDeviceIdentityUnavailableException(
                        "Could not persist the prepared identity rotation; the active key was not changed.",
                    )
                }
                preparedRotation(current, pending)
            }
        }

    override suspend fun pendingRotation(): PreparedDeviceIdentityRotation? =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = readMetadata() ?: return@withContext null
                readPendingMetadata()?.let { pending -> preparedRotation(current, pending) }
            }
        }

    override suspend fun markRotationRemoteConfirmed(operationId: String): PreparedDeviceIdentityRotation =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = readMetadata() ?: throw AndroidDeviceIdentityUnavailableException("The active identity is missing.")
                val pending = requirePendingOperation(operationId)
                if (pending.phase == IdentityRotationPhase.RemoteConfirmed) {
                    return@withContext preparedRotation(current, pending)
                }
                val confirmed = pending.copy(phase = IdentityRotationPhase.RemoteConfirmed)
                if (!writePendingMetadata(confirmed)) {
                    throw AndroidDeviceIdentityUnavailableException("Could not durably record relay rotation confirmation.")
                }
                preparedRotation(current, confirmed)
            }
        }

    override suspend fun commitRotation(operationId: String): LocalDeviceIdentity =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val current = readMetadata() ?: throw AndroidDeviceIdentityUnavailableException("The active identity is missing.")
                val pending = requirePendingOperation(operationId)
                check(pending.phase == IdentityRotationPhase.RemoteConfirmed) { "IDENTITY_ROTATION_NOT_REMOTE_CONFIRMED" }
                commitPendingRotation(current, pending)
            }
        }

    override suspend fun rollbackRotation(operationId: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val pending = requirePendingOperation(operationId)
                check(pending.phase == IdentityRotationPhase.Prepared) { "CONFIRMED_IDENTITY_ROTATION_CANNOT_ROLL_BACK" }
                if (!clearPendingMetadata()) {
                    throw AndroidDeviceIdentityUnavailableException("Could not clear the prepared identity rotation.")
                }
                deleteAliasBestEffort(pending.replacement.alias)
            }
        }
    }

    private fun createIdentityMetadata(
        generation: Long,
        algorithm: IdentitySignatureAlgorithm,
        requestedSecurityLevel: IdentitySecurityLevel,
    ): IdentityMetadata {
        val alias = aliasFor(algorithm, generation)
        if (keyStore.containsAlias(alias)) {
            throw AndroidDeviceIdentityUnavailableException("Unexpected identity alias already exists; refusing adoption.")
        }
        val pair = generateIdentityKey(alias, algorithm, requestedSecurityLevel == IdentitySecurityLevel.STRONGBOX)
        validateGeneratedKey(algorithm, pair)
        val metadata = IdentityMetadata(alias, generation, algorithm, securityLevelOf(pair.private))
        try {
            loadIdentity(metadata)
        } catch (error: Exception) {
            deleteAliasBestEffort(alias)
            throw error
        }
        return metadata
    }

    private fun createAndActivate(
        generation: Long,
        algorithm: IdentitySignatureAlgorithm,
        requestedSecurityLevel: IdentitySecurityLevel,
    ): LocalDeviceIdentity {
        val metadata = createIdentityMetadata(generation, algorithm, requestedSecurityLevel)
        val identity = loadIdentity(metadata)
        if (!writeMetadata(metadata)) {
            deleteAliasBestEffort(metadata.alias)
            throw AndroidDeviceIdentityUnavailableException("Could not persist identity metadata; new key was not activated.")
        }
        return identity
    }

    private fun preparedRotation(
        current: IdentityMetadata,
        pending: PendingIdentityRotation,
    ): PreparedDeviceIdentityRotation {
        require(pending.replacement.generation == current.generation + 1) { "IDENTITY_ROTATION_GENERATION_MISMATCH" }
        require(keyStore.containsAlias(current.alias)) { "ACTIVE_IDENTITY_KEY_MISSING" }
        require(keyStore.containsAlias(pending.replacement.alias)) { "PREPARED_IDENTITY_KEY_MISSING" }
        return PreparedDeviceIdentityRotation(
            operationId = pending.operationId,
            reason = pending.reason,
            phase = pending.phase,
            currentIdentity = loadIdentity(current),
            replacementIdentity = loadIdentity(pending.replacement),
        )
    }

    private fun commitPendingRotation(
        current: IdentityMetadata,
        pending: PendingIdentityRotation,
    ): LocalDeviceIdentity {
        require(pending.phase == IdentityRotationPhase.RemoteConfirmed) { "IDENTITY_ROTATION_NOT_REMOTE_CONFIRMED" }
        val prepared = preparedRotation(current, pending)
        val replacement = pending.replacement
        val editor =
            preferences
                .edit()
                .putInt(FORMAT_VERSION_KEY, FORMAT_VERSION)
                .putString(ACTIVE_ALIAS_KEY, replacement.alias)
                .putLong(KEY_GENERATION_KEY, replacement.generation)
                .putString(ALGORITHM_KEY, replacement.algorithm.wireId)
                .putString(SECURITY_LEVEL_KEY, replacement.securityLevel.name)
        removePendingMetadata(editor)
        if (!editor.commit()) {
            throw AndroidDeviceIdentityUnavailableException(
                "Could not activate the relay-confirmed identity; recovery remains fail-closed.",
            )
        }
        return prepared.replacementIdentity
    }

    private fun requirePendingOperation(operationId: String): PendingIdentityRotation {
        require(operationId.isNotBlank()) { "IDENTITY_ROTATION_OPERATION_ID_REQUIRED" }
        val pending = readPendingMetadata() ?: error("NO_PREPARED_IDENTITY_ROTATION")
        require(pending.operationId == operationId) { "IDENTITY_ROTATION_OPERATION_MISMATCH" }
        return pending
    }

    private fun loadIdentity(metadata: IdentityMetadata): LocalDeviceIdentity {
        try {
            val entry =
                keyStore.getEntry(metadata.alias, null) as? KeyStore.PrivateKeyEntry
                    ?: throw AndroidDeviceIdentityUnavailableException("Identity entry is not a private-key entry.")
            requireNonExportable(entry.privateKey)
            val publicKey = entry.certificate.publicKey
            validatePublicKey(metadata.algorithm, publicKey.algorithm, publicKey)
            val spki =
                publicKey.encoded
                    ?: throw AndroidDeviceIdentityUnavailableException("Identity has no canonical public key encoding.")
            val actualLevel = securityLevelOf(entry.privateKey)
            if (actualLevel != metadata.securityLevel) {
                throw AndroidDeviceIdentityUnavailableException("Identity security level changed unexpectedly.")
            }
            return AndroidKeystoreLocalDeviceIdentity(
                privateKey = entry.privateKey,
                publicKeySpki = spki,
                algorithm = metadata.algorithm,
                securityLevel = actualLevel,
                keyGeneration = metadata.generation,
            )
        } catch (error: AndroidDeviceIdentityUnavailableException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw unavailable("load identity key", error)
        } catch (error: RuntimeException) {
            throw unavailable("load identity key", error)
        }
    }

    private fun readMetadata(): IdentityMetadata? {
        try {
            val version = preferences.getInt(FORMAT_VERSION_KEY, 0)
            val alias = preferences.getString(ACTIVE_ALIAS_KEY, null)
            val generation = preferences.getLong(KEY_GENERATION_KEY, -1L)
            if (version == 0 && alias == null && generation == -1L) return null
            if (alias.isNullOrBlank() || generation <= 0L) throw invalidMetadata()
            if (version == 1) {
                val expected = "$LEGACY_ED25519_ALIAS_PREFIX$generation"
                if (alias != expected) throw invalidMetadata()
                return IdentityMetadata(alias, generation, IdentitySignatureAlgorithm.ED25519, IdentitySecurityLevel.OS_KEYSTORE)
            }
            if (version != FORMAT_VERSION) throw invalidMetadata()
            val algorithm =
                preferences
                    .getString(ALGORITHM_KEY, null)
                    ?.let(IdentitySignatureAlgorithm::fromWireId) ?: throw invalidMetadata()
            val level =
                preferences
                    .getString(SECURITY_LEVEL_KEY, null)
                    ?.let(IdentitySecurityLevel::valueOf) ?: throw invalidMetadata()
            if (alias != aliasFor(algorithm, generation) || level == IdentitySecurityLevel.UNSUPPORTED) throw invalidMetadata()
            return IdentityMetadata(alias, generation, algorithm, level)
        } catch (error: ClassCastException) {
            throw AndroidDeviceIdentityUnavailableException("Identity metadata has an invalid type.", error)
        } catch (error: IllegalArgumentException) {
            throw AndroidDeviceIdentityUnavailableException("Identity metadata is unsupported.", error)
        }
    }

    private fun writeMetadata(metadata: IdentityMetadata): Boolean =
        preferences
            .edit()
            .putInt(FORMAT_VERSION_KEY, FORMAT_VERSION)
            .putString(ACTIVE_ALIAS_KEY, metadata.alias)
            .putLong(KEY_GENERATION_KEY, metadata.generation)
            .putString(ALGORITHM_KEY, metadata.algorithm.wireId)
            .putString(SECURITY_LEVEL_KEY, metadata.securityLevel.name)
            .commit()

    @Suppress("ThrowsCount")
    private fun readPendingMetadata(): PendingIdentityRotation? {
        val operationId = preferences.getString(PENDING_OPERATION_ID_KEY, null)
        val alias = preferences.getString(PENDING_ALIAS_KEY, null)
        val generation = preferences.getLong(PENDING_GENERATION_KEY, -1L)
        if (operationId == null && alias == null && generation == -1L) return null
        if (operationId.isNullOrBlank() || alias.isNullOrBlank() || generation <= 0L) throw invalidPendingMetadata()
        return try {
            PendingIdentityRotation(
                operationId = operationId,
                reason = KeyRotationReason.valueOf(preferences.getString(PENDING_REASON_KEY, null) ?: throw invalidPendingMetadata()),
                phase = IdentityRotationPhase.valueOf(preferences.getString(PENDING_PHASE_KEY, null) ?: throw invalidPendingMetadata()),
                replacement =
                    IdentityMetadata(
                        alias = alias,
                        generation = generation,
                        algorithm =
                            IdentitySignatureAlgorithm.fromWireId(
                                preferences.getString(PENDING_ALGORITHM_KEY, null) ?: throw invalidPendingMetadata(),
                            ),
                        securityLevel =
                            IdentitySecurityLevel.valueOf(
                                preferences.getString(PENDING_SECURITY_LEVEL_KEY, null) ?: throw invalidPendingMetadata(),
                            ),
                    ),
            ).also { pending ->
                require(pending.replacement.alias == aliasFor(pending.replacement.algorithm, pending.replacement.generation)) {
                    "INVALID_PENDING_IDENTITY_ALIAS"
                }
            }
        } catch (error: AndroidDeviceIdentityUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw AndroidDeviceIdentityUnavailableException("Prepared identity rotation metadata is invalid.", error)
        }
    }

    private fun writePendingMetadata(pending: PendingIdentityRotation): Boolean =
        preferences
            .edit()
            .putString(PENDING_OPERATION_ID_KEY, pending.operationId)
            .putString(PENDING_REASON_KEY, pending.reason.name)
            .putString(PENDING_PHASE_KEY, pending.phase.name)
            .putString(PENDING_ALIAS_KEY, pending.replacement.alias)
            .putLong(PENDING_GENERATION_KEY, pending.replacement.generation)
            .putString(PENDING_ALGORITHM_KEY, pending.replacement.algorithm.wireId)
            .putString(PENDING_SECURITY_LEVEL_KEY, pending.replacement.securityLevel.name)
            .commit()

    private fun clearPendingMetadata(): Boolean = removePendingMetadata(preferences.edit()).commit()

    private fun removePendingMetadata(editor: android.content.SharedPreferences.Editor): android.content.SharedPreferences.Editor =
        editor
            .remove(PENDING_OPERATION_ID_KEY)
            .remove(PENDING_REASON_KEY)
            .remove(PENDING_PHASE_KEY)
            .remove(PENDING_ALIAS_KEY)
            .remove(PENDING_GENERATION_KEY)
            .remove(PENDING_ALGORITHM_KEY)
            .remove(PENDING_SECURITY_LEVEL_KEY)

    private fun deleteAliasBestEffort(alias: String) = runCatching { keyStore.deleteEntry(alias) }.let { Unit }

    private data class IdentityMetadata(
        val alias: String,
        val generation: Long,
        val algorithm: IdentitySignatureAlgorithm,
        val securityLevel: IdentitySecurityLevel,
    )

    private data class PendingIdentityRotation(
        val operationId: String,
        val reason: KeyRotationReason,
        val phase: IdentityRotationPhase,
        val replacement: IdentityMetadata,
    )

    private companion object {
        const val PREFERENCES_NAME = "device_identity_metadata"
        const val FORMAT_VERSION_KEY = "format_version"
        const val ACTIVE_ALIAS_KEY = "active_alias"
        const val KEY_GENERATION_KEY = "key_generation"
        const val ALGORITHM_KEY = "algorithm"
        const val SECURITY_LEVEL_KEY = "security_level"
        const val FORMAT_VERSION = 2
        const val INITIAL_KEY_GENERATION = 1L
        const val LEGACY_ED25519_ALIAS_PREFIX = "aegis_remote_device_identity_ed25519_g"
        const val PENDING_OPERATION_ID_KEY = "pending_rotation_operation_id"
        const val PENDING_REASON_KEY = "pending_rotation_reason"
        const val PENDING_PHASE_KEY = "pending_rotation_phase"
        const val PENDING_ALIAS_KEY = "pending_rotation_alias"
        const val PENDING_GENERATION_KEY = "pending_rotation_generation"
        const val PENDING_ALGORITHM_KEY = "pending_rotation_algorithm"
        const val PENDING_SECURITY_LEVEL_KEY = "pending_rotation_security_level"

        fun aliasFor(
            algorithm: IdentitySignatureAlgorithm,
            generation: Long,
        ): String = "aegis_remote_device_identity_${algorithm.wireId.lowercase()}_g$generation"

        fun invalidMetadata() =
            AndroidDeviceIdentityUnavailableException(
                "Android device identity metadata is incomplete or unsupported.",
            )

        fun invalidPendingMetadata() =
            AndroidDeviceIdentityUnavailableException(
                "Android prepared identity rotation metadata is incomplete or unsupported.",
            )
    }
}

/** Real-operation probe; provider declarations and API level are never treated as support. */
class AndroidCryptoCapabilityProbe(
    context: Context,
) : CryptoCapabilityProbe {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(CAPABILITY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun evaluate(forceRefresh: Boolean): CryptoCapabilityReport =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val compatibilityKey = compatibilityKey()
                if (!forceRefresh) {
                    readCachedReport(compatibilityKey)?.let { return@withContext it }
                }
                evaluateUncached().also { report -> persistReport(compatibilityKey, report) }
            }
        }

    @Suppress("CyclomaticComplexMethod")
    private fun evaluateUncached(): CryptoCapabilityReport {
        val identity =
            listOf(
                probeIdentity(IdentitySignatureAlgorithm.ED25519),
                probeIdentity(IdentitySignatureAlgorithm.ECDSA_P256_SHA256),
            )
        val ecdh = probeEcdhP256()
        val x25519 = probeX25519()
        val aes = probeAesGcm()
        val chacha = probeChaCha()
        val suites =
            buildList {
                if (identity.any { it.algorithm == IdentitySignatureAlgorithm.ECDSA_P256_SHA256 && it.supported } && ecdh.supported &&
                    aes.supported
                ) {
                    add(AEGIS_P256_AESGCM_V1)
                }
                if (identity.any { it.algorithm == IdentitySignatureAlgorithm.ED25519 && it.supported } && x25519.supported &&
                    chacha.supported
                ) {
                    add(AEGIS_25519_CHACHA_V1)
                }
            }
        val failures =
            buildList {
                identity.filterNot { it.supported }.forEach {
                    add(
                        CryptoCapabilityFailure(it.failureCode ?: "IDENTITY_FAILED", it.algorithm.wireId),
                    )
                }
                listOf(
                    ecdh,
                    x25519,
                ).filterNot { it.supported }.forEach { add(CryptoCapabilityFailure(it.failureCode ?: "KEX_FAILED", it.algorithm.name)) }
                listOf(
                    aes,
                    chacha,
                ).filterNot { it.supported }.forEach {
                    add(
                        CryptoCapabilityFailure(it.failureCode ?: "AEAD_FAILED", it.algorithm.name),
                    )
                }
            }
        return CryptoCapabilityReport(
            identityOptions = identity,
            keyAgreementOptions = listOf(ecdh, x25519),
            aeadOptions = listOf(aes, chacha),
            supportedSuites = suites,
            recommendedSuite = suites.firstOrNull(),
            failures = failures,
        )
    }

    private fun readCachedReport(compatibilityKey: String): CryptoCapabilityReport? {
        if (preferences.getString(CACHE_COMPATIBILITY_KEY, null) != compatibilityKey) return null
        val payload = preferences.getString(CACHE_REPORT_KEY, null) ?: return null
        return runCatching { json.decodeFromString<CryptoCapabilityReport>(payload) }
            .onFailure {
                Log.w("AegisIdentity", "event=crypto_capability_cache_invalid code=IDN-1008")
                preferences.edit().clear().commit()
            }.getOrNull()
    }

    private fun persistReport(
        compatibilityKey: String,
        report: CryptoCapabilityReport,
    ) {
        val stored =
            preferences
                .edit()
                .putString(CACHE_COMPATIBILITY_KEY, compatibilityKey)
                .putString(CACHE_REPORT_KEY, json.encodeToString(report))
                .commit()
        if (!stored) Log.w("AegisIdentity", "event=crypto_capability_cache_write_failed code=IDN-1009")
    }

    @Suppress("DEPRECATION")
    private fun compatibilityKey(): String {
        val packageVersion =
            runCatching {
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                "${info.versionName.orEmpty()}:$code"
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
        val providers =
            Security.getProviders().joinToString(separator = "|") { provider ->
                "${provider.name}:${provider.version}:${provider.info}"
            }
        val raw =
            listOf(
                CACHE_SCHEMA_VERSION,
                appContext.packageName,
                packageVersion,
                Build.FINGERPRINT,
                Build.VERSION.SDK_INT.toString(),
                Build.VERSION.SECURITY_PATCH.orEmpty(),
                Build.SUPPORTED_ABIS.joinToString(","),
                appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE).toString(),
                providers,
            ).joinToString(separator = "\u0000")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray()),
        )
    }

    private fun probeIdentity(algorithm: IdentitySignatureAlgorithm): IdentityCapability {
        val alias = "aegis_crypto_probe_${algorithm.wireId.lowercase()}_${UUID.randomUUID()}"
        val keyStore = androidKeyStore()
        try {
            val strongBoxAvailable =
                Build.VERSION.SDK_INT >= 28 &&
                    appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            if (strongBoxAvailable) {
                runCatching { return verifyIdentityPair(alias, algorithm, requestStrongBox = true) }
                runCatching { keyStore.deleteEntry(alias) }
            }
            return verifyIdentityPair(alias, algorithm, requestStrongBox = false)
        } catch (error: AndroidDeviceIdentityUnavailableException) {
            val code =
                if (algorithm == IdentitySignatureAlgorithm.ED25519 && error.message.orEmpty().contains("substituted")) {
                    "ED25519_UNSUPPORTED_OR_SUBSTITUTED"
                } else {
                    "${algorithm.wireId}_UNSUPPORTED"
                }
            Log.w("AegisIdentity", "event=crypto_capability_failure algorithm=${algorithm.wireId} code=$code")
            return IdentityCapability(algorithm, false, IdentitySecurityLevel.UNSUPPORTED, code)
        } catch (error: Exception) {
            val code = "${algorithm.wireId}_UNSUPPORTED"
            Log.w(
                "AegisIdentity",
                "event=crypto_capability_failure algorithm=${algorithm.wireId} code=$code type=${error.javaClass.simpleName}",
            )
            return IdentityCapability(algorithm, false, IdentitySecurityLevel.UNSUPPORTED, code)
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    private fun verifyIdentityPair(
        alias: String,
        algorithm: IdentitySignatureAlgorithm,
        requestStrongBox: Boolean,
    ): IdentityCapability {
        val pair = generateIdentityKey(alias, algorithm, requestStrongBox)
        validateGeneratedKey(algorithm, pair)
        val vector = "Aegis crypto capability probe v1".encodeToByteArray()
        val signatureAlgorithm = signatureName(algorithm)
        val proof =
            Signature.getInstance(signatureAlgorithm).run {
                initSign(pair.private)
                update(vector)
                sign()
            }
        val verified =
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(pair.public)
                update(vector)
                verify(proof)
            }
        if (!verified) throw AndroidDeviceIdentityUnavailableException("Generated identity failed its sign/verify self-test.")
        return IdentityCapability(algorithm, true, securityLevelOf(pair.private))
    }

    private fun probeEcdhP256(): KeyAgreementCapability =
        runCatching {
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val a = generator.generateKeyPair()
            val b = generator.generateKeyPair()
            val ab =
                KeyAgreement.getInstance("ECDH").run {
                    init(a.private)
                    doPhase(b.public, true)
                    generateSecret()
                }
            val ba =
                KeyAgreement.getInstance("ECDH").run {
                    init(b.private)
                    doPhase(a.public, true)
                    generateSecret()
                }
            check(MessageDigest.isEqual(ab, ba))
            KeyAgreementCapability(KeyAgreementAlgorithm.ECDH_P256, true)
        }.getOrElse { KeyAgreementCapability(KeyAgreementAlgorithm.ECDH_P256, false, "ECDH_P256_SELF_TEST_FAILED") }

    private fun probeX25519(): KeyAgreementCapability =
        runCatching {
            val generator = KeyPairGenerator.getInstance("X25519")
            val a = generator.generateKeyPair()
            val b = generator.generateKeyPair()
            val ab =
                KeyAgreement.getInstance("X25519").run {
                    init(a.private)
                    doPhase(b.public, true)
                    generateSecret()
                }
            val ba =
                KeyAgreement.getInstance("X25519").run {
                    init(b.private)
                    doPhase(a.public, true)
                    generateSecret()
                }
            check(MessageDigest.isEqual(ab, ba))
            KeyAgreementCapability(KeyAgreementAlgorithm.X25519, true)
        }.getOrElse { KeyAgreementCapability(KeyAgreementAlgorithm.X25519, false, "X25519_SELF_TEST_FAILED") }

    private fun probeAesGcm(): AeadCapability =
        runCatching {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val nonce = ByteArray(12) { it.toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val encrypted = cipher.doFinal("aegis".encodeToByteArray())
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            check(cipher.doFinal(encrypted).contentEquals("aegis".encodeToByteArray()))
            AeadCapability(AeadAlgorithm.AES_256_GCM, true)
        }.getOrElse { AeadCapability(AeadAlgorithm.AES_256_GCM, false, "AES_256_GCM_SELF_TEST_FAILED") }

    private fun probeChaCha(): AeadCapability =
        runCatching {
            Cipher.getInstance("ChaCha20-Poly1305")
            AeadCapability(AeadAlgorithm.CHACHA20_POLY1305, true)
        }.getOrElse { AeadCapability(AeadAlgorithm.CHACHA20_POLY1305, false, "CHACHA20_POLY1305_UNAVAILABLE") }

    private companion object {
        const val CAPABILITY_PREFERENCES_NAME = "aegis-crypto-capabilities"
        const val CACHE_COMPATIBILITY_KEY = "compatibility_key"
        const val CACHE_REPORT_KEY = "report_json"
        const val CACHE_SCHEMA_VERSION = "aegis-crypto-capability-cache-v1"
    }
}

class AndroidDeviceIdentityUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private class AndroidKeystoreLocalDeviceIdentity(
    private val privateKey: PrivateKey,
    publicKeySpki: ByteArray,
    private val algorithm: IdentitySignatureAlgorithm,
    private val securityLevel: IdentitySecurityLevel,
    private val keyGeneration: Long,
) : LocalDeviceIdentity {
    private val immutableSpki = publicKeySpki.copyOf()
    override val publicIdentity: DevicePublicIdentity
        get() {
            val digest = MessageDigest.getInstance("SHA-256")
            val id =
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    digest.digest(DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, immutableSpki)),
                )
            val fingerprint = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(immutableSpki))
            return DevicePublicIdentity(
                deviceId = DeviceId(id),
                algorithm = algorithm,
                publicKeySpki = immutableSpki.copyOf(),
                fingerprint = "SHA256:$fingerprint",
                keyGeneration = keyGeneration,
                securityLevel = securityLevel,
            )
        }

    override suspend fun sign(payload: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                Signature.getInstance(signatureName(algorithm)).run {
                    initSign(privateKey)
                    update(payload)
                    sign()
                }
            } catch (error: GeneralSecurityException) {
                throw unavailable("sign identity transcript", error)
            }
        }
}

internal object AndroidEd25519IdentitySupport {
    const val MIN_SUPPORTED_API = 33
    const val ED25519_PUBLIC_KEY_BYTES = 32
    const val ED25519_SIGNATURE_BYTES = 64
    private val spkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    fun isEd25519Algorithm(algorithm: String): Boolean = algorithm.equals("Ed25519", true) || algorithm.equals("EdDSA", true)

    fun requirePlatformSupport(sdkInt: Int) {
        if (sdkInt < MIN_SUPPORTED_API) {
            throw AndroidDeviceIdentityUnavailableException(
                "Ed25519 Android Keystore identities require Android 13 or newer.",
            )
        }
    }

    fun rawPublicKeyFromSubjectPublicKeyInfo(encoded: ByteArray): ByteArray {
        require(encoded.size == 44 && encoded.copyOfRange(0, 12).contentEquals(spkiPrefix)) {
            "Public key is not canonical Ed25519 SubjectPublicKeyInfo."
        }
        return encoded.copyOfRange(12, encoded.size)
    }

    fun deviceIdFromRawPublicKey(rawPublicKey: ByteArray): DeviceId {
        require(rawPublicKey.size == ED25519_PUBLIC_KEY_BYTES)
        val spki = spkiPrefix + rawPublicKey
        val digest =
            MessageDigest.getInstance("SHA-256").digest(
                DeviceIdentityCanonicalEncoding.deviceIdPreimage(IdentitySignatureAlgorithm.ED25519, spki),
            )
        return DeviceId(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    }
}

private fun androidKeyStore(): KeyStore =
    try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (error: Exception) {
        throw unavailable("load Android Keystore", error)
    }

private fun generateIdentityKey(
    alias: String,
    algorithm: IdentitySignatureAlgorithm,
    requestStrongBox: Boolean,
): KeyPair {
    try {
        val generatorName = if (algorithm == IdentitySignatureAlgorithm.ED25519) ED25519 else EC
        val purposes = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        val builder =
            KeyGenParameterSpec.Builder(alias, purposes).apply {
                if (algorithm == IdentitySignatureAlgorithm.ED25519) {
                    setDigests(KeyProperties.DIGEST_NONE)
                } else {
                    setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                    setDigests(KeyProperties.DIGEST_SHA256)
                }
                if (requestStrongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true)
            }
        return KeyPairGenerator.getInstance(generatorName, ANDROID_KEYSTORE).run {
            initialize(builder.build())
            generateKeyPair()
        }
    } catch (error: Exception) {
        throw unavailable("generate ${algorithm.wireId} identity key", error)
    }
}

private fun validateGeneratedKey(
    algorithm: IdentitySignatureAlgorithm,
    pair: KeyPair,
) {
    requireNonExportable(pair.private)
    validatePublicKey(algorithm, pair.public.algorithm, pair.public)
}

private fun validatePublicKey(
    requested: IdentitySignatureAlgorithm,
    actualName: String,
    publicKey: java.security.PublicKey,
) {
    when (requested) {
        IdentitySignatureAlgorithm.ED25519 -> {
            if (!AndroidEd25519IdentitySupport.isEd25519Algorithm(actualName)) {
                throw AndroidDeviceIdentityUnavailableException(
                    "Android Keystore substituted $actualName for Ed25519; refusing an algorithm downgrade.",
                )
            }
            AndroidEd25519IdentitySupport.rawPublicKeyFromSubjectPublicKeyInfo(publicKey.encoded)
        }

        IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> {
            requireExactAndroidP256PublicKey(publicKey)
        }
    }
}

internal fun requireExactAndroidP256PublicKey(publicKey: java.security.PublicKey) {
    if (!ExactP256Curve.isSecp256r1(publicKey)) {
        throw AndroidDeviceIdentityUnavailableException(
            "Android Keystore ${ExactP256Curve.REJECTION_CODE}; refusing an algorithm downgrade.",
        )
    }
}

private fun securityLevelOf(privateKey: PrivateKey): IdentitySecurityLevel =
    try {
        val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(privateKey, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> IdentitySecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> IdentitySecurityLevel.TRUSTED_ENVIRONMENT
                else -> IdentitySecurityLevel.OS_KEYSTORE
            }
        } else if (info.isInsideSecureHardware) {
            IdentitySecurityLevel.TRUSTED_ENVIRONMENT
        } else {
            IdentitySecurityLevel.OS_KEYSTORE
        }
    } catch (_: Exception) {
        IdentitySecurityLevel.OS_KEYSTORE
    }

private fun requireNonExportable(privateKey: PrivateKey) {
    if (privateKey.encoded != null) {
        throw AndroidDeviceIdentityUnavailableException(
            "Android Keystore returned exportable private key material; refusing to use it.",
        )
    }
}

private fun signatureName(algorithm: IdentitySignatureAlgorithm): String =
    when (algorithm) {
        IdentitySignatureAlgorithm.ED25519 -> ED25519
        IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "SHA256withECDSA"
    }

private fun unavailable(
    operation: String,
    cause: Throwable,
): AndroidDeviceIdentityUnavailableException {
    Log.e(
        "AegisIdentity",
        "event=identity_keystore_failure operation=${operation.replace(' ', '_')} " +
            "errorType=${cause.javaClass.simpleName} causeType=${cause.cause?.javaClass?.simpleName ?: "none"}",
    )
    return AndroidDeviceIdentityUnavailableException(
        "Android Keystore could not $operation; refusing an insecure identity fallback.",
        cause,
    )
}

private fun KeyRotationReason.label(): String = name.lowercase()

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val ED25519 = "Ed25519"
private const val EC = "EC"
private const val P256_CURVE = "secp256r1"
