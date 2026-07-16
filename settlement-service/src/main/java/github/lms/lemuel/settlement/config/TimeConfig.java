package github.lms.lemuel.settlement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시간 소스 구성 — 정산 도메인의 "오늘/지금" 판정을 한 곳으로 모은다.
 *
 * <p>정산·홀드백·조정의 날짜 경계는 <b>한국 영업일(KST)</b> 기준이다. JVM 기본 타임존이 UTC 인
 * 컨테이너에서 {@code LocalDate.now()}(zone 미지정)를 쓰면 KST 자정~09시 사이 하루가 어긋나
 * 정산일·홀드백 해제일이 off-by-one 된다. 이를 막기 위해 응용 서비스 계층은 정적 {@code now()} 대신
 * 이 {@link Clock} 빈을 주입받아 {@code LocalDate.now(clock)} 로 KST 기준 시각을 얻는다.
 *
 * <p>테스트에서는 고정 {@link Clock#fixed}로 대체해 자정 경계·월말·윤년을 결정적으로 검증한다.
 */
@Configuration
public class TimeConfig {

    /** 정산 도메인의 업무 표준시. 스케줄러 cron zone("Asia/Seoul")과 동일 출처. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
