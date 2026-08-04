package com.example.data

/**
 * Custom exception hierarchy for V2Ray operations
 * Provides detailed error context for different failure scenarios
 */
sealed class V2RayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    
    /**
     * Database operation failed
     */
    class DatabaseException(message: String, cause: Throwable? = null) 
        : V2RayException("Database Error: $message", cause)
    
    /**
     * Network/subscription operation failed
     */
    class NetworkException(message: String, cause: Throwable? = null) 
        : V2RayException("Network Error: $message", cause)
    
    /**
     * Link parsing or validation failed
     */
    class ParseException(message: String, cause: Throwable? = null) 
        : V2RayException("Parse Error: $message", cause)
    
    /**
     * Server connectivity/ping failed
     */
    class ServerConnectionException(message: String, cause: Throwable? = null) 
        : V2RayException("Connection Error: $message", cause)
    
    /**
     * Validation of entity failed
     */
    class ValidationException(message: String, cause: Throwable? = null) 
        : V2RayException("Validation Error: $message", cause)
}
