package dev.aegis.remote.core.logging

interface Logger {
    fun debug(
        message: String,
        fields: Map<String, String> = emptyMap(),
    )

    fun info(
        message: String,
        fields: Map<String, String> = emptyMap(),
    )

    fun warn(
        message: String,
        fields: Map<String, String> = emptyMap(),
    )

    fun error(
        message: String,
        fields: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    )
}

class SanitizingLogFields {
    private val sensitiveKeys = setOf("password", "privateKey", "token", "secret", "credential")

    fun sanitize(fields: Map<String, String>): Map<String, String> =
        fields.mapValues { (key, value) ->
            if (sensitiveKeys.any { key.contains(it, ignoreCase = true) }) "<redacted>" else value
        }
}
