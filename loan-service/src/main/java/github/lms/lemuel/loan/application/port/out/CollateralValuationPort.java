package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CollateralType;

import java.math.BigDecimal;

/**
 * 담보 평가액 조달 아웃바운드 포트.
 *
 * <p><b>Phase 1 은 신청 시 입력값을 그대로 스냅샷으로 돌려주는 구현체</b>를 쓴다. Phase 2 에서
 * market-service(금융자산 시가)·common-data-service(주택 실거래가) 어댑터로 교체되며, 그때
 * <em>도메인·응용 계층은 수정되지 않는다</em> — 이 포트를 지금 정의해 두는 이유가 그것이다.
 * loan-service 가 외부 시세를 직접 조회하면 market/commondata 와 책임이 중복되므로,
 * 실연동은 반드시 위성 서비스 경유로 한다.
 */
public interface CollateralValuationPort {

    /**
     * 담보 평가액을 산출한다.
     *
     * @param type          담보 유형
     * @param description   담보물 표시(부동산 소재지 등) — Phase 2 어댑터의 조회 키가 된다
     * @param declaredValue 신청자가 제시한 평가액
     * @return 확정 평가액(Phase 1 은 {@code declaredValue} 그대로)
     */
    BigDecimal appraise(CollateralType type, String description, BigDecimal declaredValue);
}
