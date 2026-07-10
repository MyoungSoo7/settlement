package github.lms.lemuel.economics.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig 의 CORS 소스 두 분기(환경변수 미설정 시 로컬 기본 화이트리스트 /
 * 설정 시 콤마 분리 목록)를 직접 검증한다. 필터체인 구성은 SecurityConfigWebMvcTest 가 부팅으로 커버.
 */
class SecurityConfigTest {

    private SecurityConfig config() {
        return new SecurityConfig(new AdminApiKeyFilter(""));
    }

    @Test
    @DisplayName("cors.origins 미설정 — 로컬 기본 오리진 화이트리스트")
    void defaultOrigins() {
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config().corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfigurations().get("/**");

        assertThat(cors.getAllowedOrigins()).contains("http://localhost:5173", "http://localhost:3000");
        assertThat(cors.getAllowedMethods()).containsExactly("GET", "POST", "OPTIONS");
        assertThat(cors.getMaxAge()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("cors.origins 설정 — 콤마 분리 목록으로 대체")
    void customOrigins() {
        SecurityConfig config = config();
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "https://a.example,https://b.example");

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        CorsConfiguration cors = source.getCorsConfigurations().get("/**");

        assertThat(cors.getAllowedOrigins()).containsExactly("https://a.example", "https://b.example");
    }
}
