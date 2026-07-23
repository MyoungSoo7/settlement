package github.lms.lemuel.tax.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 세무 도메인이 settlement 도메인을 직접 참조하지 않기 위한 read-only DTO(ledger 의 SettlementSummary 와 동형).
 *
 * <p>세무 계산·전기·대사에 필요한 최소 필드만 담는다. {@code immediatePayoutAmount} 는
 * {@code Settlement.getImmediatePayoutAmount()} 와 동일한 규칙(holdbackReleased ? net : max(net−holdback,0))
 * 으로 어댑터가 미리 계산해 넘긴다 — 세무 3자 대사(TaxReconciliation)가 실제 payout 금액과 교차검증할 때
 * "확정 시점에 기대했던 즉시지급 산정액"의 기준으로 쓴다(ADR 0027, 2026-07-24 정정).
 */
public record TaxSettlementView(
        Long id,
        BigDecimal commission,
        BigDecimal netAmount,
        LocalDate settlementDate,
        String status,
        BigDecimal immediatePayoutAmount
) {
    public boolean isDone() {
        return "DONE".equals(status);
    }
}
