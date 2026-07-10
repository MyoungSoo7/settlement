package github.lms.lemuel.commondata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigBeansTest {

    @Test
    void httpClientConfig_는_RestClientBuilder_와_ObjectMapper_를_제공한다() {
        HttpClientConfig config = new HttpClientConfig();
        assertThat(config.restClientBuilder()).isInstanceOf(RestClient.Builder.class);
        assertThat(config.portalObjectMapper()).isInstanceOf(ObjectMapper.class);
    }

    @Test
    void asyncConfig_는_가상스레드_실행기를_제공한다() {
        TaskExecutor executor = new AsyncConfig().syncTaskExecutor();
        assertThat(executor).isNotNull();
    }

    @Test
    void cacheConfig_는_생성된다() {
        assertThat(new CacheConfig()).isNotNull();
    }

    @Test
    void securityConfig_cors_기본_오리진() {
        SecurityConfig config = new SecurityConfig(new AdminApiKeyFilter(""));
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration cfg = source.getCorsConfigurations().get("/**");
        assertThat(cfg.getAllowedOrigins()).contains("http://localhost:3000");
        assertThat(cfg.getAllowedMethods()).contains("GET", "POST", "OPTIONS");
    }

    @Test
    void securityConfig_cors_환경변수_오리진() {
        SecurityConfig config = new SecurityConfig(new AdminApiKeyFilter(""));
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "https://a.test,https://b.test");
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration cfg = source.getCorsConfigurations().get("/**");
        assertThat(cfg.getAllowedOrigins()).containsExactly("https://a.test", "https://b.test");
    }

    @Test
    void adminApiKeyFilter_는_admin_경로만_필터한다() {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");
        HttpServletRequest admin = mock(HttpServletRequest.class);
        when(admin.getRequestURI()).thenReturn("/admin/commondata/sources");
        assertThat(filter.shouldNotFilter(admin)).isFalse();

        HttpServletRequest publicApi = mock(HttpServletRequest.class);
        when(publicApi.getRequestURI()).thenReturn("/api/common-data/sources");
        assertThat(filter.shouldNotFilter(publicApi)).isTrue();
    }

    @Test
    void adminApiKeyFilter_키미설정이면_통과() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        verify(res, never()).sendError(anyInt(), anyString());
    }

    @Test
    void adminApiKeyFilter_키불일치면_403() throws Exception {
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
    void adminApiKeyFilter_키일치면_통과() throws Exception {
        AdminApiKeyFilter filter = new AdminApiKeyFilter("secret");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("X-Internal-Api-Key")).thenReturn("secret");

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
    }
}
