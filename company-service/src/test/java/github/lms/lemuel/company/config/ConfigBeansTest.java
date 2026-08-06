package github.lms.lemuel.company.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.autoconfigure.JacksonCompatAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfigurationSource;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("스캔을 좁힌 이 서비스에서도 outbox 매퍼가 채워진다 — shared-common 자동 구성이 보장한다 "
            + "(예전에는 HttpClientConfig 가 JacksonCompatConfig 를 손수 @Import 했고, 그 배선을 빠뜨리면 "
            + "outboxObjectMapper 한정자 주입 실패로 서비스가 기동조차 못 했다)")
    void outboxObjectMapperIsProvidedWithoutManualWiring() {
        // company 처럼 common.config 가 스캔에 걸리지 않는 컨텍스트를 재현한다.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonCompatAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasBean("outboxObjectMapper").hasBean("jacksonLegacyObjectMapper");
                    assertThat(context.getBean("outboxObjectMapper")).isInstanceOf(ObjectMapper.class);
                });
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
