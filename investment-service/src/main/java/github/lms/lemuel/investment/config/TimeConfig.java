package github.lms.lemuel.investment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시간 소스 구성 — 투자 도메인의 "지금" 판정을 한 곳으로 모은다(settlement 의 TimeConfig 패턴 동형).
 *
 * <p>투자 주문 신청 시각(createdAt 스냅샷)은 <b>한국 표준시(KST)</b> 기준이어야 한다. JVM 기본 타임존이
 * UTC 인 컨테이너에서 {@code LocalDateTime.now()}(zone 미지정)를 쓰면 KST 자정~09시 사이 주문 이력의
 * 날짜가 하루 어긋난다. 이를 막기 위해 응용 서비스 계층은 정적 {@code now()} 대신 이 {@link Clock} 빈을
 * 주입받아 {@code LocalDateTime.now(clock)} 로 KST 기준 시각을 얻고, 도메인 팩토리에 시각 파라미터로
 * 전달한다(도메인은 시각을 만들지 않고 받는다). {@link DailyScreeningScheduler} 는 이미 zone 을 명시한
 * {@code LocalDate.now(zone)} 을 쓴다({@code app.screening.zone}, 기본 Asia/Seoul) — 동일 표준시.
 *
 * <p>테스트에서는 고정 {@link Clock#fixed}로 대체해 주문 시각을 결정적으로 검증한다.
 */
@Configuration
public class TimeConfig {

    /** 투자 도메인의 업무 표준시. 스크리닝 스케줄러 zone 과 동일 출처. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
