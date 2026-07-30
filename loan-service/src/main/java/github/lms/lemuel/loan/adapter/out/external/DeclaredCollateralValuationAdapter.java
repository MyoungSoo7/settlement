package github.lms.lemuel.loan.adapter.out.external;

import github.lms.lemuel.loan.application.port.out.CollateralValuationPort;
import github.lms.lemuel.loan.domain.CollateralType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 담보 평가액 Phase 1 구현 — <b>신청 시 제시된 값을 그대로 확정 평가액으로 삼는다</b>.
 *
 * <p>Phase 2 에서 market-service(금융자산 시가)·common-data-service(주택 실거래가)를 조회하는 어댑터로
 * <em>이 클래스만</em> 교체된다. 도메인·응용 계층은 {@link CollateralValuationPort} 만 보므로 수정되지 않는다.
 *
 * <p>loan-service 가 국토부·KRX 를 직접 호출하지 않는 이유: 시세는 market-service, 공공데이터 수집은
 * common-data-service 소관이라 직접 연동하면 책임이 중복되고 시세 정합성이 두 곳에서 갈린다.
 */
@Component
public class DeclaredCollateralValuationAdapter implements CollateralValuationPort {

    @Override
    public BigDecimal appraise(CollateralType type, String description, BigDecimal declaredValue) {
        return declaredValue;
    }
}
