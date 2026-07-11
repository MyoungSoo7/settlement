package github.lms.lemuel.financial.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler — 예외→HTTP 상태 매핑(404/400/409)과 null 메시지 방어를 검증.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NoSuchElementException → 404, null 메시지는 빈 문자열")
    void notFound() {
        ResponseEntity<Map<String, String>> response = handler.notFound(new NoSuchElementException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("message", "");
    }

    @Test
    @DisplayName("IllegalArgumentException → 400")
    void badRequest() {
        ResponseEntity<Map<String, String>> response =
                handler.badRequest(new IllegalArgumentException("잘못된 파라미터"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "잘못된 파라미터");
    }

    @Test
    @DisplayName("IllegalStateException → 409")
    void conflict() {
        ResponseEntity<Map<String, String>> response =
                handler.conflict(new IllegalStateException("이미 실행 중"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "이미 실행 중");
    }
}
