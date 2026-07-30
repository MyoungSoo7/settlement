package github.lms.lemuel.loan.application.port.out;

import java.math.BigDecimal;

/**
 * 기준금리 조달 아웃바운드 포트.
 *
 * <p><b>Phase 1 은 설정값(yml)을 돌려주는 구현체</b>를 쓴다. Phase 2 에서 economics-service(한국은행
 * 기준금리) 어댑터로 교체되며, 그때 금리 산정 로직({@code SecuredLoanPolicy})은 수정되지 않는다.
 */
public interface BaseRatePort {

    /** 현재 기준금리(%). */
    BigDecimal currentBaseRatePercent();
}
