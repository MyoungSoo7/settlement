package github.lms.lemuel.company.adapter.out.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaverNewsPropertiesTest {

    @Test
    @DisplayName("null/blank baseUrl 은 기본 openapi.naver.com 으로 대체된다")
    void defaultsBaseUrl() {
        assertEquals("https://openapi.naver.com", new NaverNewsProperties("c", "s", null, 20).baseUrl());
        assertEquals("https://openapi.naver.com", new NaverNewsProperties("c", "s", "  ", 20).baseUrl());
        assertEquals("https://custom", new NaverNewsProperties("c", "s", "https://custom", 20).baseUrl());
    }

    @Test
    @DisplayName("null clientId/clientSecret 은 빈 문자열로 정규화된다")
    void normalizesNullCredentials() {
        NaverNewsProperties props = new NaverNewsProperties(null, null, null, 20);
        assertEquals("", props.clientId());
        assertEquals("", props.clientSecret());
    }

    @Test
    @DisplayName("display 는 1~100 범위를 벗어나면 20 으로 보정된다")
    void clampsDisplay() {
        assertEquals(20, new NaverNewsProperties("c", "s", null, 0).display());
        assertEquals(20, new NaverNewsProperties("c", "s", null, -5).display());
        assertEquals(20, new NaverNewsProperties("c", "s", null, 101).display());
        assertEquals(50, new NaverNewsProperties("c", "s", null, 50).display());
    }

    @Test
    @DisplayName("configured 는 clientId·clientSecret 이 모두 있을 때만 true")
    void configured() {
        assertTrue(new NaverNewsProperties("c", "s", null, 20).configured());
        assertFalse(new NaverNewsProperties("", "s", null, 20).configured());
        assertFalse(new NaverNewsProperties("c", "", null, 20).configured());
        assertFalse(new NaverNewsProperties(null, null, null, 20).configured());
    }
}
