package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.dto.SettlementSummary;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.LoadSettlementForLedgerPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 *
 * <p>차1·대1 페어링과 그 전제 불변식(정산 상태·구성적 금액 균형)은 도메인 팩토리
 * {@link LedgerEntry#balancedPairForSettlement}가 구성적으로 강제한다. 본 writer 는 멱등 skip·
 * 정산 로드·저장과 트랜잭션 경계만 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleLedgerEntryWriter {

    private final LoadSettlementForLedgerPort loadSettlementPort;
    private final LoadLedgerEntryPort loadLedgerPort;
    private final SaveLedgerEntryPort saveLedgerPort;
    private final LedgerPeriodGuard periodGuard;

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

        // 기간 원장 잠금 — 대상 settlement_date 가 마감(CLOSED) 기간이면 신규 원분개를 거부한다.
        periodGuard.assertOpenForNewEntry(s.settlementDate());

        // 차1·대1 균형 쌍 생성 + not-DONE 차단 + 금액 불변식은 도메인 팩토리가 구성적으로 강제한다.
        List<LedgerEntry> pair = LedgerEntry.balancedPairForSettlement(
                settlementId, s.status(), s.paymentAmount(), s.commission(), s.netAmount(), s.settlementDate());

        List<LedgerEntry> created = new ArrayList<>(pair.size());
        for (LedgerEntry entry : pair) {
            created.add(saveLedgerPort.save(entry));
        }

        log.info("Ledger entries created: settlementId={}, rows={}", settlementId, created.size());
        return created;
    }
}
