package github.lms.lemuel.loan.config;

import github.lms.lemuel.loan.application.service.CreditPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 선정산 대출 정책 빈 구성. LTV·일할이율·평판 haircut 을 {@code app.loan.*} 설정에서 주입한다.
 */
@Configuration
public class LoanPolicyConfig {

    @Bean
    public CreditPolicy creditPolicy(
            @Value("${app.loan.ltv}") BigDecimal ltv,
            @Value("${app.loan.daily-rate}") BigDecimal dailyRate,
            @Value("${app.loan.reputation.haircut.a:1.0}") BigDecimal hairA,
            @Value("${app.loan.reputation.haircut.b:1.0}") BigDecimal hairB,
            @Value("${app.loan.reputation.haircut.c:0.85}") BigDecimal hairC,
            @Value("${app.loan.reputation.haircut.d:0.70}") BigDecimal hairD,
            @Value("${app.loan.reputation.haircut.e:0.0}") BigDecimal hairE) {
        Map<String, BigDecimal> haircut = Map.of(
                "A", hairA, "B", hairB, "C", hairC, "D", hairD, "E", hairE);
        return new CreditPolicy(ltv, dailyRate, haircut);
    }
}
