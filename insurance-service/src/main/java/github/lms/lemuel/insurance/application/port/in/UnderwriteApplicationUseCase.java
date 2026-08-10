package github.lms.lemuel.insurance.application.port.in;

import java.math.BigDecimal;

/**
 * 언더라이팅 유스케이스 — 심사 착수 · 승인 · 반려.
 *
 * <p><b>승인은 돈 경로다</b>: 계약(Policy) 발행 + 초년도 수수료 12회 스케줄 확정(D4) +
 * policy_issued·commission_confirmed 이벤트 발행이 같은 트랜잭션에서 일어난다.
 *
 * <p><b>완전판매 게이트</b>: 해당 청약의 상품설명서 교부 증빙(disclosure_deliveries)이
 * 없으면 승인이 거부된다({@code DisclosureNotDeliveredException}).
 */
public interface UnderwriteApplicationUseCase {

    /** 심사 착수 — SUBMITTED → UNDER_REVIEW. */
    void startReview(String applicationId);

    /**
     * 승인 — UNDER_REVIEW → APPROVED + 계약 발행 + 수수료 스케줄 확정.
     *
     * <p>효력일은 승인일(KST). 초년도 수수료 총액 = 연 보험료 × 상품 초년도 수수료율
     * (통화 최소단위 절사).
     */
    IssuedPolicySummary approve(String applicationId);

    /** 반려 — UNDER_REVIEW → REJECTED. 사유 필수. */
    void reject(String applicationId, String reason);

    /**
     * @param firstYearCommissionTotal 확정된 초년도 수수료 총액 (12회 합계와 동일)
     */
    record IssuedPolicySummary(String applicationId, String policyId, String policyNumber,
                               BigDecimal firstYearCommissionTotal, int installmentCount) {
    }
}
