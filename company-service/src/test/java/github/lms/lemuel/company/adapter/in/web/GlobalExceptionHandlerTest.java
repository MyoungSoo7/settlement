package github.lms.lemuel.company.adapter.in.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NoSuchElementException → 404 + message")
    void notFound() {
        ResponseEntity<Map<String, String>> response = handler.notFound(new NoSuchElementException("없음"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("없음", response.getBody().get("message"));
    }

    @Test
    @DisplayName("IllegalArgumentException → 400 + message")
    void badRequest() {
        ResponseEntity<Map<String, String>> response = handler.badRequest(new IllegalArgumentException("잘못됨"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("잘못됨", response.getBody().get("message"));
    }

    @Test
    @DisplayName("IllegalStateException → 409 + message")
    void conflict() {
        ResponseEntity<Map<String, String>> response = handler.conflict(new IllegalStateException("충돌"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("충돌", response.getBody().get("message"));
    }

    @Test
    @DisplayName("message 가 null 이면 빈 문자열로 대체")
    void nullMessage() {
        ResponseEntity<Map<String, String>> response = handler.notFound(new NoSuchElementException());
        assertEquals("", response.getBody().get("message"));
    }
}
