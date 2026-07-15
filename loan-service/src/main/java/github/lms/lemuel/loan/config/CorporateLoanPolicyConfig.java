package github.lms.lemuel.loan.config;

import github.lms.lemuel.loan.domain.CorporateCreditPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 기업 신용대출 신용평가 정책 빈 구성.
 *
 * <p>수수료 일할이율은 기존 {@code app.loan.daily-rate}(선정산 대출과 공용)를 재사용하고,
 * 자본총계 대비 한도 비율은 {@code app.loan.corporate.equity-limit-ratio}(기본 10%)로 주입한다.
 */
@Configuration
public class CorporateLoanPolicyConfig {

    @Bean
    public CorporateCreditPolicy corporateCreditPolicy(
            @Value("${app.loan.daily-rate}") BigDecimal dailyRate,
            @Value("${app.loan.corporate.equity-limit-ratio:0.10}") BigDecimal equityLimitRatio) {
        return new CorporateCreditPolicy(dailyRate, equityLimitRatio);
    }
}
