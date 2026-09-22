package dev.aegis.remote.android.home

import kotlinx.coroutines.CancellationException

/** Keeps coroutine cancellation out of user-facing failure state. */
internal fun <T> Result<T>.rethrowCancellation(): Result<T> {
    exceptionOrNull()?.let { error ->
        if (error is CancellationException) throw error
    }
    return this
}
