package github.lms.lemuel.deposit.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시간 원천을 빈으로 주입한다 — {@code LocalDateTime.now()} 직접 호출을 대신한다.
 *
 * <p>만료 회수 배치는 "지금"을 기준으로 대상을 고른다. 그 기준을 코드가 정적으로 읽으면
 * 테스트에서 만료 경계를 고정할 수 없어, 경계 근처 동작(1초 전/후)이 검증 대상에서 빠진다.
 *
 * <p>존을 KST 로 고정하는 이유는 {@code expires_at} 이 {@code TIMESTAMP}(존 없음)이고
 * hold 를 거는 쪽도 시스템 로컬 시각을 쓰기 때문이다. 여기만 UTC 로 읽으면 컨테이너의 JVM 존에
 * 따라 회수 시점이 9시간 어긋난다 — 그 어긋남은 "만료됐는데 아직 안 풀림"으로만 보여서
 * 원인을 짚기 어렵다.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
