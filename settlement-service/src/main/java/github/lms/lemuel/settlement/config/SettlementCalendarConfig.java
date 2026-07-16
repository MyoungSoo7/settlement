package github.lms.lemuel.settlement.config;

import github.lms.lemuel.settlement.domain.BusinessDayCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 정산 영업일 캘린더 배선 — 설정({@code app.settlement.extra-holidays})의 추가 공휴일을
 * 도메인 {@link BusinessDayCalculator} 에 주입하는 유일한 지점.
 *
 * <p>도메인은 프레임워크 의존이 없으므로 주입 배선을 config 계층이 담당한다. 여기서
 * {@link BusinessDayCalculator#withExtraHolidays} 로 임시공휴일을 얹은 캘린더를 만든 뒤
 * {@link BusinessDayCalculator#installDefault} 로 프로세스 기본 캘린더로 설치한다. 그러면 정적
 * 도메인 호출부({@code SettlementCycle}·{@code HoldbackPolicy})의 {@code addBusinessDays} 가
 * 추가 공휴일을 반영해 정산일·홀드백 해제일을 계산한다 — 코드 변경 없이 임시공휴일 대응.
 */
@Configuration
@EnableConfigurationProperties(SettlementCalendarProperties.class)
public class SettlementCalendarConfig {

    private static final Logger log = LoggerFactory.getLogger(SettlementCalendarConfig.class);

    private final SettlementCalendarProperties properties;

    public SettlementCalendarConfig(SettlementCalendarProperties properties) {
        this.properties = properties;
    }

    /**
     * 설정된 추가 공휴일을 얹은 캘린더 빈. 생성과 동시에 프로세스 기본 캘린더로 설치해 정적 도메인
     * 호출부가 이를 반영하도록 한다(기동 시 1회). 추가 공휴일이 없으면 표준 캘린더와 동일하다.
     */
    @Bean
    public BusinessDayCalculator businessDayCalculator() {
        BusinessDayCalculator calculator = BusinessDayCalculator.withExtraHolidays(properties.extraHolidays());
        BusinessDayCalculator.installDefault(calculator);
        if (!properties.extraHolidays().isEmpty()) {
            log.info("[정산 캘린더] 임시공휴일 {}건 주입 — {}",
                    properties.extraHolidays().size(), properties.extraHolidays());
        }
        return calculator;
    }
}
