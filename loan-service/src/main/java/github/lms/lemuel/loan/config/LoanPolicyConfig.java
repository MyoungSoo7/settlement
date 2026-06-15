package github.lms.lemuel.loan.config;

import github.lms.lemuel.loan.application.service.CreditPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 선정산 대출 정책 빈 구성. LTV·일할이율을 {@code app.loan.*} 설정에서 주입한다.
 */
@Configuration
public class LoanPolicyConfig {

    @Bean
    public CreditPolicy creditPolicy(
            @Value("${app.loan.ltv}") BigDecimal ltv,
            @Value("${app.loan.daily-rate}") BigDecimal dailyRate) {
        return new CreditPolicy(ltv, dailyRate);
    }
}
