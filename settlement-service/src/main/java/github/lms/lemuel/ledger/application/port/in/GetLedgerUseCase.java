package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerEntry;

import java.time.LocalDate;
import java.util.List;

public interface GetLedgerUseCase {

    /** 정산 1건에 속한 모든 분개 row. */
    List<LedgerEntry> getBySettlementId(Long settlementId);

    /** 환불 1건에 속한 역분개 row. */
    List<LedgerEntry> getByRefundId(Long refundId);

    /** 기간별 분개 — 보고/감사 용. */
    List<LedgerEntry> getBySettlementDateBetween(LocalDate from, LocalDate to);
}
