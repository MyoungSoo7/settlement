package github.lms.lemuel.economics.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminApiKeyFilter — /admin/economics/** 공유 시크릿 게이트의 세 경로
 * (미설정 통과 / 헤더 일치 통과 / 불일치 403)와 shouldNotFilter 범위를 검증.
 */
class AdminApiKeyFilterTest {

    @Test
    @DisplayName("shouldNotFilter — /admin/economics/ 경로만 필터 대상")
    void shouldNotFilter() {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");

        HttpServletRequest admin = mock(HttpServletRequest.class);
        when(admin.getRequestURI()).thenReturn("/admin/economics/sync");
        assertThat(filter.shouldNotFilter(admin)).isFalse();

        HttpServletRequest publicApi = mock(HttpServletRequest.class);
        when(publicApi.getRequestURI()).thenReturn("/api/economics/indicators");
        assertThat(filter.shouldNotFilter(publicApi)).isTrue();
    }

    @Test
    @DisplayName("키 미설정 — 게이트 없이 통과")
    void passesWhenKeyBlank() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(res, never()).sendError(anyInt(), anyString());
    }

    @Test
    @DisplayName("키 일치 — 통과")
    void passesWhenKeyMatches() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Internal-Api-Key")).thenReturn("secret");

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    @Test
    @DisplayName("키 불일치 — 403, 체인 진행 안 함")
    void forbidsWhenKeyMismatch() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Internal-Api-Key")).thenReturn("wrong");

        filter.doFilterInternal(req, res, chain);

        verify(res).sendError(HttpServletResponse.SC_FORBIDDEN, "invalid internal api key");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    @DisplayName("키 미설정 + keyRequired(운영) — 통과가 아니라 403")
    void forbidsWhenKeyBlankAndRequired() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("", true);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(res).sendError(HttpServletResponse.SC_FORBIDDEN, "internal api key not configured");
        verify(chain, never()).doFilter(req, res);
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

        assertThat(sources).isNotEmpty();
        assertThat(sources.get(0).getProperty("app.security.internal-key-required")).isEqualTo(true);
    }
}
