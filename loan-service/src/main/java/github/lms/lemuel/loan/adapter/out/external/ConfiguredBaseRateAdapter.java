package github.lms.lemuel.loan.adapter.out.external;

import github.lms.lemuel.loan.application.port.out.BaseRatePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 기준금리 Phase 1 구현 — 설정값(yml)을 돌려준다.
 *
 * <p>Phase 2 에서 economics-service(한국은행 기준금리) 조회 어댑터로 <em>이 클래스만</em> 교체된다.
 * 금리 산정 로직({@code SecuredLoanPolicy})과 신청 서비스는 {@link BaseRatePort} 만 보므로 수정되지 않는다.
 */
@Component
public class ConfiguredBaseRateAdapter implements BaseRatePort {

    private final BigDecimal baseRatePercent;

    public ConfiguredBaseRateAdapter(@Value("${app.loan.secured.base-rate-percent:3.5}")
                                     BigDecimal baseRatePercent) {
        this.baseRatePercent = baseRatePercent;
    }

    @Override
    public BigDecimal currentBaseRatePercent() {
        return baseRatePercent;
    }
}
