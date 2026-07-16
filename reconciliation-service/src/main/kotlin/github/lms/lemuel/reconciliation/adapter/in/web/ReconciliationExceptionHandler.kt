package github.lms.lemuel.reconciliation.adapter.`in`.web

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
 * e.g. a negative `toleranceKrw` rejected by `ReconciliationEngine.init`) to
 * HTTP 400 instead of letting them surface as opaque 500s. Infra failures
 * (source outages etc.) still flow to 500.
 */
@RestControllerAdvice
class ReconciliationExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidation(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        log.warn("validation rejected: {}", ex.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(400, "BAD_REQUEST", ex.message ?: "invalid request"))
    }
}
