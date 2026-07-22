package github.lms.lemuel.recovery.application.port.in;

import github.lms.lemuel.recovery.domain.SellerRecovery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 회수 조정(차지백·PG 대사) 저장 직후 호출되는 지급후 채권 발생 유스케이스 (seed-p0-6).
 *
 * <p>스스로 판정한다: 즉시지급 Payout 이 COMPLETED(송금 완료)가 아니면 아무것도 하지 않는다 —
 * 그 경우 회수는 기존 경로(net 축소·역분개)가 감당한다. 송금 완료 정산이면 미해제 holdback 에서
 * 우선 흡수하고, 잔여만 채권으로 연다. 조정 1건당 채권 1건(멱등).
 */
public interface RecordPostPayoutRecoveryUseCase {

    /**
     * @param recoveredAmount 회수 총액(양수 — 조정 레코드의 절대값)
     * @return 새로 열린 채권. 대상 아님(미송금·전액 흡수·이미 처리·셀러 미해석)이면 빈 값.
     */
    Optional<SellerRecovery> recordIfPostPayout(Long settlementId, Long adjustmentId,
                                                BigDecimal recoveredAmount, LocalDate adjustmentDate);
}
