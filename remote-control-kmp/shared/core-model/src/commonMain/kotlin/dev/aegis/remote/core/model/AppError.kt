package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface AppError {
    val message: String
    val failure: AegisFailure?

    @Serializable
    data class Network(
        override val message: String,
        override val failure: AegisFailure? = null,
    ) : AppError

    @Serializable
    data class Authentication(
        override val message: String,
        override val failure: AegisFailure? = null,
    ) : AppError

    @Serializable
    data class Authorization(
        override val message: String,
        override val failure: AegisFailure? = null,
    ) : AppError

    @Serializable
    data class HostKeyMismatch(
        override val message: String,
        override val failure: AegisFailure? = null,
    ) : AppError

    @Serializable
    data class CapabilityUnavailable(
        override val message: String,
        override val failure: AegisFailure? = null,
    ) : AppError

    @Serializable
    data class Validation(
        override val message: String,
        override val failure: AegisFailure? = null,
    ) : AppError

    @Serializable
    data class Unknown(
        override val message: String,
        override val failure: AegisFailure? = null,
    ) : AppError
}
