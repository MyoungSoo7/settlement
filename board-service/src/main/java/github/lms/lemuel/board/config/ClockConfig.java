package github.lms.lemuel.board.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시계를 빈으로 주입한다 — 응용 서비스가 {@code OffsetDateTime.now()} 를 직접 부르면
 * 시간에 의존하는 로직을 테스트에서 고정할 수 없다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
