package github.lms.lemuel.financial.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneId;

/**
 * DART 자동 수집 스케줄링 활성화.
 *
 * <p>{@code Clock} 은 스케줄러가 대상 사업연도를 계산할 때 쓴다 — 테스트에서 {@link Clock#fixed} 로
 * 갈아끼워 연도 의존 로직을 결정적으로 검증할 수 있게 빈으로 분리. 존은 스케줄 cron 존과 동일.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock financialClock(@Value("${app.financial.sync.schedule.zone:Asia/Seoul}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
