package github.lms.lemuel.board.adapter.in.web;

import github.lms.lemuel.board.domain.exception.BoardInvariantViolationException;
import github.lms.lemuel.board.domain.exception.BoardNotFoundException;
import github.lms.lemuel.board.domain.exception.DuplicateBoardKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 게시판 API 예외 → HTTP 상태 매핑.
 *
 * <p>도메인 예외를 그대로 500 으로 흘리면 "게시판 키가 중복입니다" 같은 <b>사용자가 고칠 수 있는
 * 오류</b>가 서버 장애처럼 보인다. 매핑은 이 한 곳에만 둔다.
 *
 * <p>catch-all({@code Exception}) 핸들러는 두지 않는다 — 그런 핸들러가 있으면 스프링 시큐리티의
 * {@code AccessDeniedException} 까지 삼켜 403 이 500 으로 바뀐다(정산 쪽에서 실제로 겪은 함정).
 */
@RestControllerAdvice
public class BoardExceptionHandler {

    @ExceptionHandler(BoardNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(BoardNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(DuplicateBoardKeyException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateBoardKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(BoardInvariantViolationException.class)
    public ResponseEntity<Map<String, String>> handleInvariant(BoardInvariantViolationException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(Map.of("message", message.isEmpty() ? "요청이 올바르지 않습니다." : message));
    }
}
