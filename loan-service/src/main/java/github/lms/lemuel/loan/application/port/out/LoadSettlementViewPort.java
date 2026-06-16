package github.lms.lemuel.loan.application.port.out;

import java.math.BigDecimal;

/**
 * 로컬 정산 뷰 조회 아웃바운드 포트.
 */
public interface LoadSettlementViewPort {

    /** 셀러의 미지급(PENDING) 정산예정금 합계 — 한도 산정 근거(담보 가치). */
    BigDecimal sumUnpaidBySeller(Long sellerId);

    /**
     * 대출 실행 직전 재검증용 — 비관적 락으로 미지급 합계를 조회한다.
     * 동시 선지급 2건이 같은 담보로 둘 다 통과하는 것을 막는다.
     */
    BigDecimal sumUnpaidBySellerForUpdate(Long sellerId);
}
