package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerEntry;

import java.util.List;

/**
 * 정산 확정(DONE) 시 원장 분개를 작성하는 인바운드 포트.
 *
 * <p>한 정산 1건은 (ACCOUNTS_PAYABLE/REVENUE) + (COMMISSION_EXPENSE/COMMISSION_REVENUE)
 * 두 row 의 LedgerEntry 로 분해되어 작성된다.
 *
 * <p>멱등성: 같은 {@code settlementId} 로 두 번 호출되면 이미 작성된 entry 가
 * 있을 때 새로 만들지 않고 빈 리스트를 반환한다.
 */
public interface CreateLedgerEntryUseCase {

    /** 단일 정산 → 분개 row 들 (이미 존재하면 빈 리스트). */
    List<LedgerEntry> createFromSettlement(Long settlementId);

    /** 여러 정산 일괄 처리. 개별 실패는 로그만 남기고 진행. */
    List<LedgerEntry> createFromSettlements(List<Long> settlementIds);
}
