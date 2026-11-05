package com.jarvis.assistant.androllm.models

/**
 * Port of AndroLLM's `io.androllm.core.common.Result`: a success-or-error wrapper used by the
 * model catalog / repository layer so operations never throw across module boundaries.
 * Renamed to avoid clashing with [kotlin.Result].
 */
sealed interface ModelResult<out T> {
    data class Success<out T>(val data: T) : ModelResult<T>
    data class Error(val exception: Exception) : ModelResult<Nothing>

    companion object {
        fun <T> success(data: T): ModelResult<T> = Success(data)
        fun <T> error(exception: Exception): ModelResult<T> = Error(exception)
        fun <T> error(message: String): ModelResult<T> = Error(Exception(message))
        fun <T> error(throwable: Throwable): ModelResult<T> =
            Error(Exception(throwable.message ?: "Unknown error", throwable))
    }
}

inline fun <T> ModelResult<T>.onSuccess(action: (T) -> Unit): ModelResult<T> {
    if (this is ModelResult.Success) action(data)
    return this
}

inline fun <T> ModelResult<T>.onError(action: (Exception) -> Unit): ModelResult<T> {
    if (this is ModelResult.Error) action(exception)
    return this
}

inline fun <T, R> ModelResult<T>.map(transform: (T) -> R): ModelResult<R> = when (this) {
    is ModelResult.Success -> ModelResult.Success(transform(data))
    is ModelResult.Error -> this
}

fun <T> ModelResult<T>.getOrNull(): T? = when (this) {
    is ModelResult.Success -> data
    is ModelResult.Error -> null
}

fun <T> ModelResult<T>.errorMessage(): String? = (this as? ModelResult.Error)?.exception?.message

/** Executes [block] and wraps its value, catching any [Exception] as [ModelResult.Error]. */
inline fun <T> modelRunCatching(block: () -> T): ModelResult<T> {
    return try {
        ModelResult.Success(block())
    } catch (e: Exception) {
        ModelResult.Error(e)
    }
}
