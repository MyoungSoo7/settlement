package github.lms.lemuel.integrity.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * INV-6 지급 대사 리포트 — "그날 확정된 정산의 payout 은 정산 net 을 넘지 않고, 1건뿐이다".
 *
 * <p>판정 기준:
 * <ul>
 *   <li><b>BLOCK</b>: 과다 지급({@code payout.amount > net_amount}) / 이중 payout
 *       (스키마의 {@code uq_payouts_settlement} 가 1차 방어지만 belt-and-suspenders 로 재확인).</li>
 *   <li><b>정보성</b>: payout 미생성 정산 — payout 생성은 운영자/후속 배치 시점에 따라
 *       뒤따라오므로 그 자체로는 위반이 아니다. 건수·목록만 노출한다.</li>
 * </ul>
 */
public record PayoutReconReport(
        LocalDate targetDate,
        long confirmedSettlements,          // 그날 확정(DONE) 정산 건수
        BigDecimal confirmedNetTotal,       // 그 정산들의 net_amount 합 (지급 가능 상한)
        long activePayouts,                 // 그 정산들에 연결된 payout (CANCELED 제외)
        BigDecimal activePayoutTotal,
        long completedPayouts,
        List<Long> settlementsWithoutPayout, // payout 없는 정산 (정보성, 상한 절단)
        List<OverpaidPayout> overpaidPayouts, // payout.amount > net_amount — 과다 지급
        List<Long> duplicatePayoutSettlementIds,
        boolean ok,
        List<String> reasons
) {

    /** 과다 지급 의심 payout — 양쪽 숫자를 병기해 에이전트/운영자가 즉시 대조하게 한다. */
    public record OverpaidPayout(Long payoutId, Long settlementId,
                                 BigDecimal payoutAmount, BigDecimal netAmount) {
    }

    public static PayoutReconReport of(LocalDate targetDate,
                                       long confirmedSettlements,
                                       BigDecimal confirmedNetTotal,
                                       long activePayouts,
                                       BigDecimal activePayoutTotal,
                                       long completedPayouts,
                                       List<Long> settlementsWithoutPayout,
                                       List<OverpaidPayout> overpaidPayouts,
                                       List<Long> duplicatePayoutSettlementIds) {
        List<String> reasons = new ArrayList<>();
        if (!overpaidPayouts.isEmpty()) {
            reasons.add("정산 net 을 초과하는 payout " + overpaidPayouts.size()
                    + "건 — 과다 지급 (INV-6 위반, 즉시 확인)");
        }
        if (!duplicatePayoutSettlementIds.isEmpty()) {
            reasons.add("같은 정산에 활성 payout 이 2건 이상: "
                    + duplicatePayoutSettlementIds.size() + "건 — 이중 지급 위험 (INV-6 위반)");
        }
        return new PayoutReconReport(targetDate, confirmedSettlements, confirmedNetTotal,
                activePayouts, activePayoutTotal, completedPayouts,
                List.copyOf(settlementsWithoutPayout), List.copyOf(overpaidPayouts),
                List.copyOf(duplicatePayoutSettlementIds),
                reasons.isEmpty(), List.copyOf(reasons));
    }
}
