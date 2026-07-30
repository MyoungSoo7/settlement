package github.lms.lemuel.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시간 소스 구성 — 계정계의 "지금" 판정을 한 곳으로 모은다(loan·settlement TimeConfig 패턴 동형).
 *
 * <p>현재 소비처는 대사 배치의 {@code last.success.epoch} 게이지(에포크 초라 zone 무관)뿐이지만,
 * 정적 {@code now()} 를 흩뿌리는 대신 Clock 주입으로 통일해 테스트 결정성을 확보한다.
 */
@Configuration
public class TimeConfig {

    /** 계정계 업무 표준시. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
