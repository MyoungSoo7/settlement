package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
import github.lms.lemuel.ledger.application.port.in.CreateLedgerEntryUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.LoadSettlementForLedgerPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.ReferenceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settlement DONE 시 원장 분개를 작성한다.
 *
 * <p>한 정산 1건은 두 개의 LedgerEntry row 로 분해된다:
 * <pre>
 * row1: Dr ACCOUNTS_PAYABLE / Cr REVENUE              = netAmount
 *       → 셀러에 대한 미지급금 인식 + 매출 인식
 * row2: Dr COMMISSION_EXPENSE / Cr COMMISSION_REVENUE = commission
 *       → 셀러 부담 수수료 비용 인식 + 플랫폼 수수료 수익 인식
 * </pre>
 *
 * <p>분개 합계 검증: {@code Dr 총합 = Cr 총합 = paymentAmount}.
 * <p>멱등: 동일 {@code (settlementId, SETTLEMENT)} 로 이미 작성된 entry 가 있으면 skip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateLedgerEntryService implements CreateLedgerEntryUseCase {

    private final LoadSettlementForLedgerPort loadSettlementPort;
    private final LoadLedgerEntryPort loadLedgerPort;
    private final SaveLedgerEntryPort saveLedgerPort;

    @Override
    @Transactional
    public List<LedgerEntry> createFromSettlement(Long settlementId) {
        if (settlementId == null) {
            throw new IllegalArgumentException("settlementId 필수");
        }

        // 멱등 — 이미 분개 작성된 settlement 라면 skip.
        if (loadLedgerPort.existsByReference(settlementId, ReferenceType.SETTLEMENT)) {
            log.debug("Ledger entry already exists for settlement {}; skipping", settlementId);
            return Collections.emptyList();
        }

        SettlementSummary s = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Settlement not found for ledger creation: id=" + settlementId));

        // DONE 이 아닌 정산에 대한 분개는 거부 — Phase 2 는 확정분만 처리.
        if (!"DONE".equals(s.status())) {
            throw new IllegalStateException(
                    "Settlement " + settlementId + " is not DONE (status=" + s.status() + ")");
        }

        BigDecimal net = nullSafe(s.netAmount());
        BigDecimal commission = nullSafe(s.commission());
        BigDecimal payment = nullSafe(s.paymentAmount());

        // 합계 검증 — 도메인 invariant.
        BigDecimal sum = net.add(commission);
        if (payment.signum() > 0 && sum.compareTo(payment) != 0) {
            throw new IllegalStateException(
                    "Settlement " + settlementId + " amount mismatch: payment=" + payment
                            + " net+commission=" + sum);
        }

        List<LedgerEntry> created = new ArrayList<>(2);

        // row 1 — 매출/미지급금
        if (net.signum() > 0) {
            LedgerEntry entry = LedgerEntry.of(
                    settlementId, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CONFIRMED,
                    AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE,
                    net, s.settlementDate(),
                    "정산 확정 — 셀러 미지급 / 매출 인식");
            entry.post();
            created.add(saveLedgerPort.save(entry));
        }

        // row 2 — 수수료
        if (commission.signum() > 0) {
            LedgerEntry entry = LedgerEntry.of(
                    settlementId, ReferenceType.SETTLEMENT,
                    LedgerEntryType.SETTLEMENT_CONFIRMED,
                    AccountType.COMMISSION_EXPENSE, AccountType.COMMISSION_REVENUE,
                    commission, s.settlementDate(),
                    "정산 확정 — 수수료 인식");
            entry.post();
            created.add(saveLedgerPort.save(entry));
        }

        log.info("Ledger entries created: settlementId={}, rows={}", settlementId, created.size());
        return created;
    }

    @Override
    @Transactional
    public List<LedgerEntry> createFromSettlements(List<Long> settlementIds) {
        if (settlementIds == null || settlementIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<LedgerEntry> all = new ArrayList<>();
        for (Long id : settlementIds) {
            try {
                all.addAll(createFromSettlement(id));
            } catch (RuntimeException e) {
                // 한 건 실패가 다른 건을 막지 않도록 — 후속 보강 배치/관리자 화면에서 재시도.
                log.error("Ledger entry creation failed for settlement {}: {}", id, e.getMessage(), e);
            }
        }
        return all;
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
