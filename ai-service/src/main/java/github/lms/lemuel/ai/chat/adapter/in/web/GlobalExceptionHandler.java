package github.lms.lemuel.ai.chat.adapter.in.web;

import github.lms.lemuel.ai.chat.adapter.in.web.dto.ChatDtos.ErrorResponse;
import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** /api/ai/** 예외 → HTTP 상태 매핑 (설계 §5). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ConversationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler({AiNotConfiguredException.class, AiUnavailableException.class})
    public ResponseEntity<ErrorResponse> aiUnavailable(RuntimeException e) {
        if (e instanceof AiUnavailableException) {
            log.warn("LLM 호출 실패: {}", e.getMessage(), e.getCause());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> rateLimited(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("잘못된 요청입니다");
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }
}
