package github.lms.lemuel.settlement.application.port.in;

import java.math.BigDecimal;

/**
 * Use Case: PG 대사 승인 → 정산 역정산(clawback) 반영.
 *
 * <p>승인된 대사 차이 중 <b>회수(clawback)</b> 방향으로 확정된 건에 한해, 셀러가 과다 정산받은 금액을
 * 정산금(net)에서 회수하고 {@code settlement_adjustments} 에 감사용 음수 레코드를 남긴다.
 * 타입별 회수 대상 판정·회수액 산정은 상위 컨슈머
 * ({@code PgReconciliationApprovedSettlementAdjustConsumer})가 수행하고, 이 UseCase 는 이미 산정된
 * 양수 clawback 을 정산에 적용하는 책임만 진다.
 */
public interface ApplyReconciliationAdjustmentUseCase {

    /**
     * 승인된 대사 clawback 을 해당 결제의 정산에 반영한다.
     *
     * <p>멱등: 같은 discrepancyId 로 이미 조정 레코드가 있으면 아무 것도 하지 않는다.
     * DONE 정산은 불변이라 실제 회수 대신 감사 레코드만 남기고 정상 반환한다(수기 회수로 이관).
     * 정산이 존재하지 않으면 예외를 전파해 컨슈머가 재시도 후 DLT 로 보내게 한다(fail-loud).
     *
     * @param paymentId      결제 ID (대사 차이가 가리키는 내부 결제)
     * @param discrepancyId  PG 대사 차이 ID (멱등 키 + 감사 링크)
     * @param clawbackAmount 회수 금액 (양수)
     */
    void applyClawback(Long paymentId, Long discrepancyId, BigDecimal clawbackAmount);
}
