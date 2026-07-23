package github.lms.lemuel.ledger.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 원장 작업을 아웃박스에 적재하는 인바운드 포트.
 *
 * <p>정산 확정/환불조정 서비스가 *자신의 트랜잭션 안에서* 호출한다 — 비즈니스 상태 변경과
 * 같은 커밋으로 원장 작업 의도가 기록되어야 크래시에도 유실되지 않는다(트랜잭셔널 아웃박스).
 * 따라서 구현은 별도 트랜잭션을 열지 않고 호출자 트랜잭션에 참여한다.
 */
public interface EnqueueLedgerTaskPort {

    /** 정산 확정 분개 작업을 settlementId 별 1건씩 적재. */
    void enqueueCreate(List<Long> settlementIds);

    /** 환불 역분개 작업 1건 적재. reference = refundId. */
    void enqueueReverse(Long settlementId, Long refundId,
                        BigDecimal refundAmount, LocalDate adjustmentDate);

    /** 차지백 ACCEPTED 역분개 작업 1건 적재. reference = chargebackId. */
    void enqueueReverseChargeback(Long settlementId, Long chargebackId,
                                  BigDecimal amount, LocalDate adjustmentDate);

    /** PG 대사 clawback 역분개 작업 1건 적재. reference = discrepancyId. */
    void enqueueReverseReconciliation(Long settlementId, Long discrepancyId,
                                      BigDecimal amount, LocalDate adjustmentDate);
}
