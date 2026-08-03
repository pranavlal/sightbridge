package com.sightbridge.core.error

enum class ErrorSeverity {
    DEBUG,
    INFORMATIONAL,
    DEGRADED,
    SERIOUS,
    FATAL
}

sealed interface NavigationError {
    val code: String
    val severity: ErrorSeverity
    val recoverable: Boolean
    val userMessage: String?
    val technicalMessage: String
    val cause: Throwable?

    data class CameraError(
        override val code: String = "ERR_CAMERA",
        override val severity: ErrorSeverity = ErrorSeverity.SERIOUS,
        override val recoverable: Boolean = true,
        override val userMessage: String? = "Camera unavailable",
        override val technicalMessage: String,
        override val cause: Throwable? = null
    ) : NavigationError

    data class MetaSdkError(
        override val code: String = "ERR_META_SDK",
        override val severity: ErrorSeverity = ErrorSeverity.SERIOUS,
        override val recoverable: Boolean = true,
        override val userMessage: String? = "Glasses connection error",
        override val technicalMessage: String,
        override val cause: Throwable? = null
    ) : NavigationError

    data class VoiceStreamError(
        override val code: String = "ERR_VOICE_STREAM",
        override val severity: ErrorSeverity = ErrorSeverity.DEGRADED,
        override val recoverable: Boolean = true,
        override val userMessage: String? = "Visual sound stream unavailable",
        override val technicalMessage: String,
        override val cause: Throwable? = null
    ) : NavigationError

    data class MemoryError(
        override val code: String = "ERR_MEMORY",
        override val severity: ErrorSeverity = ErrorSeverity.FATAL,
        override val recoverable: Boolean = false,
        override val userMessage: String? = "System memory low",
        override val technicalMessage: String,
        override val cause: Throwable? = null
    ) : NavigationError
}
