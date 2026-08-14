package github.lms.lemuel.notification.adapter.`in`.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Stable error body for client-side validation failures. */
data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
)

/**
 * Maps domain validation failures ([IllegalArgumentException] from `require`,
 * e.g. blank recipient/subject in `Notification.init`) to HTTP 400 instead of
 * letting them surface as opaque 500s. Infra failures still flow to 500.
 */
@RestControllerAdvice
class NotificationExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        log.warn("validation rejected: {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(400, "BAD_REQUEST", ex.message ?: "invalid request"))
    }

    /**
     * No verified identity — 401, never 500. The reason stays generic: the
     * caller learns that it is unauthenticated, not why the token failed.
     */
    @ExceptionHandler(StreamUnauthorizedException::class)
    fun handleUnauthorized(ex: StreamUnauthorizedException): ResponseEntity<ErrorResponse> {
        log.debug("stream rejected: {}", ex.message)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(401, "UNAUTHORIZED", "authentication required"))
    }

    /**
     * No signing key configured — the push stream is disabled (503) while the
     * rest of the service keeps serving. Fail-closed, not fail-open.
     */
    @ExceptionHandler(StreamNotConfiguredException::class)
    fun handleNotConfigured(ex: StreamNotConfiguredException): ResponseEntity<ErrorResponse> {
        log.warn("stream unavailable: {}", ex.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse(503, "STREAM_NOT_CONFIGURED", "notification stream is not available"))
    }
}
