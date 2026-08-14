package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.CommissionRatePolicy;
import github.lms.lemuel.settlement.domain.SellerTier;

import java.time.LocalDate;
import java.util.List;

/**
 * 요율 정책 후보 조회 (ADR 0032).
 *
 * <p>어댑터는 인덱스로 좁힐 수 있는 조건(scope_key 후보 · 유효기간)까지만 거른다 —
 * 우선순위 판정은 {@link CommissionRatePolicy#resolve} 가 도메인에서 한다.
 */
public interface LoadCommissionRatePolicyPort {

    /** 해당 셀러·등급에 걸릴 수 있는 정책 후보. 없으면 빈 목록(→ 등급 기본율 폴백). */
    List<CommissionRatePolicy> findEffectiveCandidates(Long sellerId, SellerTier tier, LocalDate at);
}
