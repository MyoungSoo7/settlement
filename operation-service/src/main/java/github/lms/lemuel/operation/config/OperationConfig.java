package github.lms.lemuel.operation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** operation-service 공용 빈 조립. */
@Configuration
public class OperationConfig {

    /** 시각 의존 로직(refire 억제, MTTR window)의 테스트 가능성을 위해 Clock 을 빈으로 주입. */
    @Bean
    public Clock operationClock() {
        return Clock.systemUTC();
    }
}
