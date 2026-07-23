package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 지급후 회수 채권의 원장 반영 (seed-p0-6) — 발생·상계 각각 균형 분개 1:1.
 *
 * <p>회수 역분개(Dr SALES_REFUND / Cr ACCOUNTS_PAYABLE)는 미지급금을 깎지만, 이미 송금이 끝난
 * 정산엔 깎을 미지급금이 없다 — 채권 인식 분개가 그 반대급부를 미수금(AR)으로 전환하고,
 * 상계 분개가 후속 정산의 미지급금으로 미수금을 회수한다. 발생+상계 왕복은 AR·AP net-zero.
 *
 * <p><b>의도된 잔여 경계</b>: 완전 상계에 이르지 못한 채권(장기 OPEN·MANUAL_REQUIRED)은 AR·AP 가
 * 대칭으로 잔존한다 — 회수 불능 확정 시의 write-off 전표(채권 제각)는 후속 과제로, 수기 이관
 * 운영 절차가 정해질 때 이 유스케이스에 배선한다(STATUS 다음 할 일 참조).
 */
public interface RecoveryEntryUseCase {

    /**
     * 채권 발생 분개: Dr ACCOUNTS_RECEIVABLE / Cr ACCOUNTS_PAYABLE (금액 = 채권 원금).
     * 멱등: (recoveryId, SELLER_RECOVERY) 로 이미 적재됐으면 빈 반환.
     */
    Optional<LedgerEntry> recognizeReceivable(Long recoveryId, Long settlementId,
                                              BigDecimal amount, LocalDate entryDate);

    /**
     * 상계 분개: Dr ACCOUNTS_PAYABLE / Cr ACCOUNTS_RECEIVABLE (금액 = 상계액).
     * 멱등: (allocationId, RECOVERY_OFFSET) 로 이미 적재됐으면 빈 반환.
     */
    Optional<LedgerEntry> offsetReceivable(Long allocationId, Long recoveryId,
                                           BigDecimal amount, LocalDate entryDate);
}
