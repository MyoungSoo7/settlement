package github.lms.lemuel.integrity.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-13 반송 재지급 대사 리포트 — "payout_bounces 체인과 재지급 payout(settlement_id=NULL)이
 * 1:1로 정합한다" (Seed D1 후속, 독립 코드리뷰 MEDIUM 시정).
 *
 * <p>{@code settlement_id=NULL} payout 은 반송 재지급 경로({@code RecordPayoutBounceService})만
 * 만들어 내므로, integrity 의 기존 스코프(정산-스코프 payout 대사, INV-6)가 이 경로를 전혀 보지
 * 못한다 — 이 리포트가 그 사각지대를 메운다.
 *
 * <p>판정 기준:
 * <ul>
 *   <li><b>BLOCK</b>: 재지급 금액이 원 payout 금액과 다름(amountMismatches) / 재지급 payout 이
 *       {@code settlement_id} 를 갖고 있음(reissuedWithSettlement — 이중지급 가드 우회 흔적) /
 *       {@code payout_bounces} 에 연결되지 않은 고아 {@code settlement_id=NULL} payout 존재
 *       (orphanNullSettlementPayoutIds — 반송 경로 밖에서 생성된 미설명 수동 송금).</li>
 *   <li><b>정보성</b>: 아직 재지급이 안 된 반송(unresolvedBounces) — 계좌 정정 대기 중일 수 있어
 *       그 자체로는 위반이 아니다.</li>
 * </ul>
 */
public record PayoutBounceReconReport(
        long totalBounces,
        long resolvedBounces,
        long unresolvedBounces,               // 정보성 — 계좌 정정 대기 등
        List<AmountMismatch> amountMismatches,
        List<Long> reissuedWithSettlement,    // resolved_payout_id 값 — settlement_id 가 non-null(불변식 위반)
        List<Long> orphanNullSettlementPayoutIds, // settlement_id=NULL 인데 어떤 반송에도 안 걸린 payout
        boolean ok,
        List<String> reasons
) {

    /** 반송-재지급 금액 불일치 — 양쪽 금액을 병기해 즉시 대조하게 한다. */
    public record AmountMismatch(Long bounceId, Long payoutId, Long resolvedPayoutId,
                                 BigDecimal originalAmount, BigDecimal reissuedAmount) {
    }

    public static PayoutBounceReconReport of(long totalBounces,
                                             long resolvedBounces,
                                             long unresolvedBounces,
                                             List<AmountMismatch> amountMismatches,
                                             List<Long> reissuedWithSettlement,
                                             List<Long> orphanNullSettlementPayoutIds) {
        List<String> reasons = new ArrayList<>();
        if (!amountMismatches.isEmpty()) {
            reasons.add("반송-재지급 금액 불일치 " + amountMismatches.size()
                    + "건 — 원 payout 금액과 다르게 재지급됨 (INV-13 위반, 즉시 확인)");
        }
        if (!reissuedWithSettlement.isEmpty()) {
            reasons.add("재지급 payout 이 settlement_id 를 가짐 " + reissuedWithSettlement.size()
                    + "건 — 이중지급 가드(uq_payouts_settlement_type) 우회 흔적 (INV-13 위반)");
        }
        if (!orphanNullSettlementPayoutIds.isEmpty()) {
            reasons.add("고아 수동 payout(settlement_id=NULL, 반송 미연결) " + orphanNullSettlementPayoutIds.size()
                    + "건 — 반송 경로 밖에서 생성된 미설명 송금 (INV-13 위반)");
        }
        return new PayoutBounceReconReport(totalBounces, resolvedBounces, unresolvedBounces,
                List.copyOf(amountMismatches), List.copyOf(reissuedWithSettlement),
                List.copyOf(orphanNullSettlementPayoutIds), reasons.isEmpty(), List.copyOf(reasons));
    }
}
