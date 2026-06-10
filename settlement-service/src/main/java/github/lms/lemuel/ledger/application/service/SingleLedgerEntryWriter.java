package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 단일 정산 → 원장 분개 작성 트랜잭션 경계.
 *
 * <p>{@code REQUIRES_NEW} 로 건별 커밋을 격리한다. 일괄 처리({@link CreateLedgerEntryService}
 * #createFromSettlements)가 이 빈을 프록시 경유로 호출하므로, 한 정산의 실패가 독립 트랜잭션으로
 * 롤백되어 같은 배치의 다른 정산 분개 커밋을 되돌리지 않는다. (이전에는 같은 클래스 내
 * self-invocation 이라 프록시를 거치지 않아 {@code @Transactional} 이 무력화되고 모든 정산이
 * 한 트랜잭션을 공유 → 한 건 커밋 실패가 전체를 롤백시킬 수 있었다.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleLedgerEntryWriter {

    private final LoadSettlementForLedgerPort loadSettlementPort;
    private final LoadLedgerEntryPort loadLedgerPort;
    private final SaveLedgerEntryPort saveLedgerPort;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<LedgerEntry> write(Long settlementId) {
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

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
