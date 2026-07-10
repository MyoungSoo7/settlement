package github.lms.lemuel.economics.adapter.in.web;

import github.lms.lemuel.economics.domain.IndicatorNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler — 예외→HTTP 상태 매핑(404/400/409)을 검증.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("IndicatorNotFoundException → 404")
    void notFound() {
        ResponseEntity<Map<String, String>> response =
                handler.notFound(new IndicatorNotFoundException("NOPE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("message");
    }

    @Test
    @DisplayName("IllegalArgumentException → 400")
    void badRequest() {
        ResponseEntity<Map<String, String>> response =
                handler.badRequest(new IllegalArgumentException("잘못된 기간"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "잘못된 기간");
    }

    @Test
    @DisplayName("IllegalStateException → 409, null 메시지는 빈 문자열")
    void conflict() {
        ResponseEntity<Map<String, String>> response =
                handler.conflict(new IllegalStateException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "");
    }
}
