package github.lms.lemuel.account.adapter.in.kafka;

import github.lms.lemuel.common.config.kafka.KafkaConsumerErrorHandlingConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import static org.assertj.core.api.Assertions.assertThat;

/** 공용 DLT 배선과 레거시 서비스별 배선이 동시에 로드되지 않는지 고정한다. */
class KafkaConfigurationCollisionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestSupportConfig.class,
                    KafkaConsumerErrorHandlingConfig.class,
                    KafkaErrorHandlerConfig.class)
            .withPropertyValues(
                    "app.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=localhost:9092",
                    "spring.application.name=lemuel-account",
                    "spring.profiles.active=prod");

    @Test
    void production_profile_uses_one_dlt_producer_factory() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasBean("dltProducerFactory"));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestSupportConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
