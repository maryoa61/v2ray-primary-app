package com.example.data

/**
 * Sealed class to handle operation results in a type-safe manner
 * Replaces exceptions with explicit result types
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error<T>(val exception: V2RayException) : Result<T>()
    
    /**
     * Execute block if result is Success
     */
    inline fun <U> map(transform: (T) -> U): Result<U> = when (this) {
        is Success -> Success(transform(data))
        is Error -> Error(exception)
    }
    
    /**
     * Execute block if result is Error
     */
    inline fun <U> flatMap(transform: (T) -> Result<U>): Result<U> = when (this) {
        is Success -> transform(data)
        is Error -> Error(exception)
    }
    
    /**
     * Get data or null
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    
    /**
     * Get data or throw exception
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
    }
    
    /**
     * Execute action on Success
     */
    inline fun onSuccess(action: (T) -> Unit): Result<T> = apply {
        if (this is Success) action(data)
    }
    
    /**
     * Execute action on Error
     */
    inline fun onError(action: (V2RayException) -> Unit): Result<T> = apply {
        if (this is Error) action(exception)
    }
}
