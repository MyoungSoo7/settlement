package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoadLedgerEntryPort {

    Optional<LedgerEntry> findById(Long id);

    /** 멱등 체크 — 동일 (referenceId, referenceType) 분개가 이미 작성되었는지. */
    boolean existsByReference(Long referenceId, ReferenceType referenceType);

    /** 한 비즈니스 거래에 속한 모든 분개 row 조회. */
    List<LedgerEntry> findByReference(Long referenceId, ReferenceType referenceType);

    /** 기간별 분개 조회 — 보고/조회 API 용. */
    List<LedgerEntry> findBySettlementDateBetween(LocalDate from, LocalDate to);
}
