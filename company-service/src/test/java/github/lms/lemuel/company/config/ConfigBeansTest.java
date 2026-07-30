package github.lms.lemuel.company.config;

import github.lms.lemuel.common.config.JacksonCompatConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfigurationSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigBeansTest {

    @Test
    @DisplayName("AsyncConfig — collectTaskExecutor 는 제출한 작업을 실행한다")
    void collectTaskExecutor() throws Exception {
        TaskExecutor executor = new AsyncConfig().collectTaskExecutor();
        assertNotNull(executor);

        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("HttpClientConfig — RestClient.Builder 빈 제공")
    void httpClientConfig() {
        HttpClientConfig config = new HttpClientConfig();
        RestClient.Builder builder = config.restClientBuilder();
        assertNotNull(builder);
        assertNotNull(builder.build());
    }

    @Test
    @DisplayName("HttpClientConfig — ObjectMapper 는 shared-common JacksonCompatConfig 를 @Import 해서 얻는다 "
            + "(제한 스캔이라 common.config 가 스캔에 안 걸린다. 이 @Import 가 없으면 outbox 발행 어댑터의 "
            + "@Qualifier(\"outboxObjectMapper\") 주입이 실패해 서비스가 기동조차 못 한다)")
    void importsJacksonCompatConfigForOutboxObjectMapper() {
        Import imported = HttpClientConfig.class.getAnnotation(Import.class);

        assertNotNull(imported, "HttpClientConfig 에 @Import 가 없다");
        assertTrue(List.of(imported.value()).contains(JacksonCompatConfig.class));
        assertNotNull(new JacksonCompatConfig().outboxObjectMapper());
    }

    @Test
    @DisplayName("SecurityConfig — CORS 소스는 기본 화이트리스트로 생성된다")
    void corsDefaultOrigins() {
        SecurityConfig config = new SecurityConfig(new AdminApiKeyFilter(""), null);
        CorsConfigurationSource source = config.corsConfigurationSource();
        assertNotNull(source);
    }

    @Test
    @DisplayName("SecurityConfig — CORS origins 프로퍼티가 있으면 그 값을 쓴다")
    void corsCustomOrigins() throws Exception {
        SecurityConfig config = new SecurityConfig(new AdminApiKeyFilter(""), null);
        Field field = SecurityConfig.class.getDeclaredField("corsAllowedOrigins");
        field.setAccessible(true);
        field.set(config, "http://a.example.com,http://b.example.com");

        assertNotNull(config.corsConfigurationSource());
    }
}
