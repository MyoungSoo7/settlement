package github.lms.lemuel.financial.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.financial.adapter.out.external.DartProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동구성되지 않는 인프라 빈(RestClient.Builder·ObjectMapper·비동기 실행기)과
 * DartProperties 기본값 보정 로직을 검증한다.
 */
class FinancialConfigTest {

    @Test
    @DisplayName("HttpClientConfig — RestClient.Builder·ObjectMapper 빈 제공")
    void httpClientConfig() {
        HttpClientConfig config = new HttpClientConfig();
        assertThat(config.restClientBuilder()).isInstanceOf(RestClient.Builder.class);
        assertThat(config.dartObjectMapper()).isInstanceOf(ObjectMapper.class);
    }

    @Test
    @DisplayName("AsyncConfig — 가상스레드 실행기 제공")
    void asyncConfig() {
        TaskExecutor executor = new AsyncConfig().syncTaskExecutor();
        assertThat(executor).isNotNull();
    }

    @Test
    @DisplayName("DartProperties — baseUrl/apiKey 기본값 보정")
    void dartPropertiesDefaults() {
        DartProperties blank = new DartProperties(null, null);
        assertThat(blank.baseUrl()).isEqualTo("https://opendart.fss.or.kr/api");
        assertThat(blank.apiKey()).isEmpty();
        assertThat(blank.configured()).isFalse();

        DartProperties blankUrl = new DartProperties("KEY", "  ");
        assertThat(blankUrl.baseUrl()).isEqualTo("https://opendart.fss.or.kr/api");

        DartProperties full = new DartProperties("KEY", "https://custom/api");
        assertThat(full.baseUrl()).isEqualTo("https://custom/api");
        assertThat(full.configured()).isTrue();
    }
}
