package github.lms.lemuel.ledger.adapter.in.event.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 환불 처리(SettlementAdjustment 음수 row 작성)가 끝났을 때 발행되는 이벤트.
 *
 * <p>{@code settlement-service} 내부 도메인 결합을 줄이기 위해
 * settlement → ledger 트리거를 신규 이벤트로 표현한다 (SettlementIndexEvent 와 분리).
 * 리스너는 ledger 의 {@code ReverseEntryUseCase} 를 호출해 SALES_REFUND 차변의 역분개를 작성.
 */
public record LedgerReverseEntryEvent(
        Long settlementId,
        Long refundId,
        BigDecimal refundAmount,
        LocalDate adjustmentDate
) {
}
