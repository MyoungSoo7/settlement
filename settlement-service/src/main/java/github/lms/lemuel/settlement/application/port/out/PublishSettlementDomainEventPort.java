package github.lms.lemuel.settlement.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 정산 도메인 이벤트를 외부(loan-service·account-service)로 발행하는 아웃바운드 포트 (Transactional Outbox 경유).
 *
 * <p>Outbox 폴러가 aggregateType="Settlement" + eventType 으로 토픽을 자동 라우팅한다:
 * <ul>
 *   <li>SettlementCreated          → lemuel.settlement.created           (loan: 담보 적재 / account: 미지급 전기)</li>
 *   <li>SettlementConfirmed        → lemuel.settlement.confirmed         (loan: 상환 차감 트리거)</li>
 *   <li>SettlementHoldbackReleased → lemuel.settlement.holdback_released (account: 유보 해제 재분류)</li>
 *   <li>SettlementHoldbackConsumed → lemuel.settlement.holdback_consumed (account: 유보 소진 현금유출)</li>
 *   <li>SettlementAdjusted         → lemuel.settlement.adjusted          (account: 조정 반제)</li>
 *   <li>SettlementCanceled         → lemuel.settlement.canceled          (account: 잔여 즉시분·유보분 2전표 반제)</li>
 *   <li>SettlementWithholdingAccrued → lemuel.settlement.withholding_accrued (account: Dr SELLER_PAYABLE/Cr WITHHOLDING_PAYABLE)</li>
 * </ul>
 *
 * <p>ADR 0026 Option ① — account-service GL 전기가 이 계약들에 의존한다(amount 는 settlement 계열 규약대로 JSON number).
 *
 * <p>기존 {@code PublishSettlementEventPort}(인프로세스 ES 색인 이벤트)와 별개의 Kafka 발행 경로다.
 */
public interface PublishSettlementDomainEventPort {

    void publishSettlementCreated(long settlementId, long sellerId, BigDecimal amount,
                                  LocalDate dueDate, BigDecimal holdbackAmount);

    void publishSettlementConfirmed(long settlementId, long sellerId, BigDecimal amount);

    /** 홀드백 보유기간 만료로 유보금이 지급 가능 상태로 재분류될 때. */
    void publishHoldbackReleased(long settlementId, long sellerId, BigDecimal amount);

    /**
     * 보유 중 홀드백이 조정·차감으로 소진되어 현금 유출로 정산될 때.
     * @param settlementId 소진 정산 — 수동 경로에서 미상 시 {@code null}(그때는 payload 에서 생략).
     */
    void publishHoldbackConsumed(long sourceAdjustmentId, Long settlementId, long sellerId, BigDecimal amount);

    /**
     * 확정 전 정산금 조정(오류 정정·차감 등)이 즉시분/유보분 다리에 반영될 때.
     * @param settlementId 조정 정산 — 미상 시 {@code null}(payload 에서 생략).
     * @param targetLeg    "SELLER_PAYABLE" 또는 "HOLDBACK_PAYABLE".
     */
    void publishSettlementAdjusted(long adjustmentId, Long settlementId, long sellerId,
                                   BigDecimal amount, String targetLeg);

    /** 정산 자체가 취소되어 잔여 즉시분·유보분을 각각 반제해야 할 때. */
    void publishSettlementCanceled(long settlementId, long sellerId,
                                   BigDecimal immediateAmount, BigDecimal holdbackAmount);

    /**
     * 정산 확정(payout 산정) 시점에 개인 셀러 원천징수가 실제 지급액에서 공제될 때(ADR 0027, 2026-07-24
     * 정정 — HIGH #4 실지급 통합 봉합). account-service 가 소비해 Dr SELLER_PAYABLE / Cr WITHHOLDING_PAYABLE
     * 로 전기해 payout 감액으로 남은 SELLER_PAYABLE 잔여를 닫는다(ADR 0026 폐루프의 확장).
     */
    void publishWithholdingAccrued(long settlementId, long sellerId, BigDecimal withholdingAmount);
}
