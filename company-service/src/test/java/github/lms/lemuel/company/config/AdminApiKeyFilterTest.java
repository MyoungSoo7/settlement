package github.lms.lemuel.company.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminApiKeyFilterTest {

    private final FilterChain chain = mock(FilterChain.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    private HttpServletRequest requestTo(String uri, String headerValue) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        if (headerValue != null) {
            when(request.getHeader("X-Internal-Api-Key")).thenReturn(headerValue);
        }
        return request;
    }

    @Test
    @DisplayName("shouldNotFilter — /admin/company/ 이외 경로는 필터를 건너뛴다")
    void shouldNotFilter() {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");
        assertTrue(filter.shouldNotFilter(requestTo("/api/company/companies", null)));
        assertFalse(filter.shouldNotFilter(requestTo("/admin/company/collect", null)));
    }

    @Test
    @DisplayName("키 미설정이면 게이팅 없이 통과")
    void passesWhenKeyBlank() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("");
        HttpServletRequest request = requestTo("/admin/company/collect", null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendError(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("올바른 키면 통과")
    void passesWithMatchingKey() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");
        HttpServletRequest request = requestTo("/admin/company/collect", "secret");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("키 불일치면 403 + 체인 미진행")
    void rejectsWrongKey() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");
        HttpServletRequest request = requestTo("/admin/company/collect", "wrong");

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "invalid internal api key");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("키 미설정 + keyRequired(운영) — 통과가 아니라 403")
    void rejectsBlankKeyWhenRequired() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("", true);
        HttpServletRequest request = requestTo("/admin/company/collect", null);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "internal api key not configured");
        verify(chain, never()).doFilter(request, response);
    }

    /**
     * 운영에서 키가 빠지면 게이트가 조용히 열리는 사고를 막는 회귀 가드.
     *
     * <p>2026-08-11 market-service 가 정확히 이 조합(시크릿에 키 없음 + prod 프로파일 없음)으로
     * fail-open 이었다. application-prod.yml 이 지워지거나 플래그가 뒤집히면 여기서 깨진다.
     */
    @Test
    @DisplayName("prod 프로파일이 internal-key-required 를 켠다")
    void prodProfileEnablesKeyRequired() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"));

        assertFalse(sources.isEmpty());
        assertEquals(true, sources.get(0).getProperty("app.security.internal-key-required"));
    }
}
