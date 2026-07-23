package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.RecoveryEntryUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.ReferenceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 채권 발생·상계의 균형 분개 (seed-p0-6) — 호출 트랜잭션(채권/상계 저장)과 같은 커밋에 묶인다.
 *
 * <p>구성적 균형: LedgerEntry 1 row = (차변 계정, 대변 계정, 금액 1개) — 발생·상계 각각 1 row 로
 * 차/대 균형이 성립한다. 멱등은 (reference_id, reference_type) 존재 검사 +
 * uq_ledger_reference_accounts 2단.
 */
@Slf4j
@Service
public class RecoveryEntryService implements RecoveryEntryUseCase {

    private final LoadLedgerEntryPort loadLedgerEntryPort;
    private final SaveLedgerEntryPort saveLedgerEntryPort;
    private final LedgerPeriodGuard periodGuard;

    public RecoveryEntryService(LoadLedgerEntryPort loadLedgerEntryPort,
                                SaveLedgerEntryPort saveLedgerEntryPort,
                                LedgerPeriodGuard periodGuard) {
        this.loadLedgerEntryPort = loadLedgerEntryPort;
        this.saveLedgerEntryPort = saveLedgerEntryPort;
        this.periodGuard = periodGuard;
    }

    @Override
    @Transactional
    public Optional<LedgerEntry> recognizeReceivable(Long recoveryId, Long settlementId,
                                                     BigDecimal amount, LocalDate entryDate) {
        if (loadLedgerEntryPort.existsByReference(recoveryId, ReferenceType.SELLER_RECOVERY)) {
            return Optional.empty();
        }
        // 기간 원장 잠금 — 마감 기간에는 신규 채권 인식 분개를 거부한다.
        periodGuard.assertOpenForNewEntry(entryDate);
        LedgerEntry entry = LedgerEntry.of(recoveryId, ReferenceType.SELLER_RECOVERY,
                LedgerEntryType.RECOVERY_RECOGNIZED,
                AccountType.ACCOUNTS_RECEIVABLE, AccountType.ACCOUNTS_PAYABLE,
                amount, entryDate,
                "지급후 회수 채권 인식 (settlement=" + settlementId + ")");
        entry.post();
        LedgerEntry saved = saveLedgerEntryPort.save(entry);
        log.info("Recovery receivable recognized: recoveryId={}, settlementId={}, amount={}",
                recoveryId, settlementId, amount);
        return Optional.of(saved);
    }

    @Override
    @Transactional
    public Optional<LedgerEntry> offsetReceivable(Long allocationId, Long recoveryId,
                                                  BigDecimal amount, LocalDate entryDate) {
        if (loadLedgerEntryPort.existsByReference(allocationId, ReferenceType.RECOVERY_OFFSET)) {
            return Optional.empty();
        }
        // 기간 원장 잠금 — 마감 기간에는 신규 상계 분개를 거부한다.
        periodGuard.assertOpenForNewEntry(entryDate);
        LedgerEntry entry = LedgerEntry.of(allocationId, ReferenceType.RECOVERY_OFFSET,
                LedgerEntryType.RECOVERY_OFFSET,
                AccountType.ACCOUNTS_PAYABLE, AccountType.ACCOUNTS_RECEIVABLE,
                amount, entryDate,
                "채권 상계 — 후속 정산 지급액 차감 (recovery=" + recoveryId + ")");
        entry.post();
        LedgerEntry saved = saveLedgerEntryPort.save(entry);
        log.info("Recovery receivable offset: allocationId={}, recoveryId={}, amount={}",
                allocationId, recoveryId, amount);
        return Optional.of(saved);
    }
}
