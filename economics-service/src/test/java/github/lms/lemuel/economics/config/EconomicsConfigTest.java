package github.lms.lemuel.economics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.economics.adapter.out.external.EcosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동구성되지 않는 인프라 빈(RestClient.Builder·ObjectMapper·비동기 실행기·캐시 설정)과
 * EcosProperties 기본값 보정 로직을 검증한다.
 */
class EconomicsConfigTest {

    @Test
    @DisplayName("HttpClientConfig — 타임아웃 얹은 RestClient.Builder·ObjectMapper 빈 제공")
    void httpClientConfig() {
        HttpClientConfig config = new HttpClientConfig();
        assertThat(config.restClientBuilder()).isInstanceOf(RestClient.Builder.class);
        assertThat(config.ecosObjectMapper()).isInstanceOf(ObjectMapper.class);
    }

    @Test
    @DisplayName("AsyncConfig — 가상스레드 실행기 제공")
    void asyncConfig() {
        TaskExecutor executor = new AsyncConfig().syncTaskExecutor();
        assertThat(executor).isNotNull();
    }

    @Test
    @DisplayName("CacheConfig — 인스턴스화 가능")
    void cacheConfig() {
        assertThat(new CacheConfig()).isNotNull();
    }

    @Test
    @DisplayName("EcosProperties — baseUrl/apiKey 기본값 보정")
    void ecosPropertiesDefaults() {
        EcosProperties blank = new EcosProperties(null, null);
        assertThat(blank.baseUrl()).isEqualTo("https://ecos.bok.or.kr/api");
        assertThat(blank.apiKey()).isEmpty();
        assertThat(blank.configured()).isFalse();

        EcosProperties blankUrl = new EcosProperties("KEY", "  ");
        assertThat(blankUrl.baseUrl()).isEqualTo("https://ecos.bok.or.kr/api");

        EcosProperties full = new EcosProperties("KEY", "https://custom/api");
        assertThat(full.baseUrl()).isEqualTo("https://custom/api");
        assertThat(full.configured()).isTrue();
    }
}
