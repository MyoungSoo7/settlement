package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.application.service.LedgerPeriodGuard;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.tax.application.TaxPostingResult;
import github.lms.lemuel.tax.application.port.in.PostSettlementTaxUseCase;
import github.lms.lemuel.tax.domain.TaxCalculation;
import github.lms.lemuel.tax.domain.TaxJournal;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 정산 확정 세무 전표 전기 서비스 (Phase 3).
 *
 * <p>세무 계산({@link TaxCalculation})을 차1·대1 균형 분개({@link TaxJournal})로 전개해 settlement 자체원장에
 * 전기한다. 멱등은 {@code (settlementId, SETTLEMENT_TAX)} 존재 검사 + 원장 UNIQUE 로 이중 방어한다. 대상
 * 정산일이 마감(CLOSED) 기간이면 {@link LedgerPeriodGuard} 가 신규 원분개를 거부한다.
 *
 * <p>{@code REQUIRES_NEW} — 정산 원장 전기(SingleLedgerEntryWriter)와 동일하게 건별 커밋을 격리한다.
 * 미등록 셀러·미확정 정산은 예외가 아니라 결과값으로 보류를 표현한다.
 */
@Service
public class PostSettlementTaxService implements PostSettlementTaxUseCase {

    private static final Logger log = LoggerFactory.getLogger(PostSettlementTaxService.class);

    private final TaxContextResolver resolver;
    private final LoadLedgerEntryPort loadLedgerPort;
    private final SaveLedgerEntryPort saveLedgerPort;
    private final LedgerPeriodGuard periodGuard;

    public PostSettlementTaxService(TaxContextResolver resolver,
                                    LoadLedgerEntryPort loadLedgerPort,
                                    SaveLedgerEntryPort saveLedgerPort,
                                    LedgerPeriodGuard periodGuard) {
        this.resolver = resolver;
        this.loadLedgerPort = loadLedgerPort;
        this.saveLedgerPort = saveLedgerPort;
        this.periodGuard = periodGuard;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaxPostingResult postForSettlement(Long settlementId, Long sellerId) {
        if (settlementId == null || sellerId == null) {
            throw new TaxInvariantViolationException("settlementId·sellerId 는 필수입니다");
        }

        TaxContextResolver.Resolved resolved = resolver.resolve(settlementId, sellerId);
        switch (resolved.status()) {
            case SETTLEMENT_NOT_FOUND ->
                throw new TaxInvariantViolationException("정산을 찾을 수 없습니다: settlementId=" + settlementId);
            case NO_PROFILE -> {
                log.info("[TaxPosting] 세무 프로필 미등록 — 보류. settlementId={}, sellerId={}", settlementId, sellerId);
                return TaxPostingResult.pendingNoProfile();
            }
            case NOT_DONE -> {
                log.debug("[TaxPosting] 정산 미확정 — 보류. settlementId={}", settlementId);
                return TaxPostingResult.skippedNotDone();
            }
            case OK -> {
                return postEntries(settlementId, resolved);
            }
            default -> throw new TaxInvariantViolationException("알 수 없는 해석 상태: " + resolved.status());
        }
    }

    private TaxPostingResult postEntries(Long settlementId, TaxContextResolver.Resolved resolved) {
        // 멱등 — 이미 세무 전표가 있으면 skip.
        if (loadLedgerPort.existsByReference(settlementId, ReferenceType.SETTLEMENT_TAX)) {
            log.debug("[TaxPosting] 이미 전기됨 — skip. settlementId={}", settlementId);
            return TaxPostingResult.alreadyPosted();
        }

        TaxCalculation calc = resolved.calculation();
        var settlementDate = resolved.view().settlementDate();

        // 기간 원장 잠금 — 마감(CLOSED) 기간이면 신규 세무 원분개를 거부한다.
        periodGuard.assertOpenForNewEntry(settlementDate);

        List<LedgerEntry> entries = TaxJournal.postingsFor(settlementId, calc, settlementDate);
        for (LedgerEntry entry : entries) {
            saveLedgerPort.save(entry);
        }

        log.info("[TaxPosting] 세무 전표 전기 완료: settlementId={}, rows={}, vat={}, withholding={}",
                settlementId, entries.size(), calc.vatAmount(), calc.withholdingAmount());
        return TaxPostingResult.posted(entries.size(), calc);
    }
}
