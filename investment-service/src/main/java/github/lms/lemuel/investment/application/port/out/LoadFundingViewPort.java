package github.lms.lemuel.investment.application.port.out;

import java.math.BigDecimal;

/** 재원 프로젝션 조회 아웃바운드 포트. */
public interface LoadFundingViewPort {

    /** 셀러의 확정(CONFIRMED) 정산금 합계(락 없는 읽기 — 단순 재원 조회용). */
    BigDecimal sumConfirmedBySeller(long sellerId);

    /**
     * 재원 재검증용 — 셀러의 확정 재원 행을 비관적 락(SELECT ... FOR UPDATE)으로 잡고 합계를 계산한다.
     * 같은 셀러의 동시 신청/집행 2건이 락 없는 평문 집계로 같은 재원을 읽어 둘 다 통과(write-skew)하는
     * 것을 직렬화로 막는다(loan {@code sumUnpaidBySellerForUpdate} 패턴). 집행 시 EXECUTED 합 재조회는
     * 이 락으로 첫 트랜잭션 커밋까지 둘째를 블로킹해 정확한 최신값을 읽게 한다.
     */
    BigDecimal sumConfirmedBySellerForUpdate(long sellerId);
}
