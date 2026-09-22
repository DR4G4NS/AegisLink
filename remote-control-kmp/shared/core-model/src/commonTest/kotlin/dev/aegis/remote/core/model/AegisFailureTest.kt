package dev.aegis.remote.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AegisFailureTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun serializableFailureKeepsCausalFieldsAndAppErrorConstructorsRemainCompatible() {
        val failure = sampleFailure()

        val decoded = json.decodeFromString<AegisFailure>(json.encodeToString(failure))
        assertEquals(failure, decoded)

        val legacyError = AppError.Authorization("Input is not authorized")
        assertNull(legacyError.failure)

        val causalError = AppError.Authorization("Input is not authorized", failure)
        val decodedError = json.decodeFromString<AppError>(json.encodeToString<AppError>(causalError))
        assertEquals(causalError, decodedError)
    }

    @Test
    fun acceptsEveryDocumentedNamespaceAndRejectsUnregisteredOrMalformedCodes() {
        FailureCodeNamespaces.all.forEach { namespace ->
            assertTrue(FailureCodeNamespaces.isSupported("$namespace-1000"), namespace)
        }

        assertFailsWith<IllegalArgumentException> { sampleFailure(code = "PRO-1000") }
        assertFailsWith<IllegalArgumentException> { sampleFailure(code = "SES-100") }
        assertFailsWith<IllegalArgumentException> { sampleFailure(code = "SES-abcd") }
    }

    private fun sampleFailure(
        code: String = AegisFailureCodes.SESSION_REMOTE_ACTION_NOT_AUTHORIZED,
    ) = AegisFailure(
        code = code,
        component = "desktop-protocol-bridge",
        operation = "process-protocol-message",
        stage = "authorization",
        category = FailureCategory.AUTHORIZATION,
        summary = "The remote protocol action is not authorized.",
        technicalCause = "Input permission is disabled.",
        expected = "An authorized input action",
        actual = "Input permission is disabled",
        retryable = false,
        correlationId = "session-1",
        evidenceRef = "trace/session-1",
        nextAction = "Enable the permission only after local confirmation.",
        underlyingType = "dev.aegis.remote.core.model.AppError.Authorization",
    )
}
