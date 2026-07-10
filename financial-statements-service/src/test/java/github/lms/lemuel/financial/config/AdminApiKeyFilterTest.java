package github.lms.lemuel.financial.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminApiKeyFilter — /admin/financial/** 공유 시크릿 게이트의 세 경로
 * (미설정 통과 / 헤더 일치 통과 / 불일치 403)와 shouldNotFilter 범위를 검증.
 */
class AdminApiKeyFilterTest {

    @Test
    @DisplayName("shouldNotFilter — /admin/financial/ 경로만 필터 대상")
    void shouldNotFilter() {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");

        HttpServletRequest admin = mock(HttpServletRequest.class);
        when(admin.getRequestURI()).thenReturn("/admin/financial/sync/companies");
        assertThat(filter.shouldNotFilter(admin)).isFalse();

        HttpServletRequest publicApi = mock(HttpServletRequest.class);
        when(publicApi.getRequestURI()).thenReturn("/api/financial/companies");
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
        verify(res, never()).sendError(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
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
}
