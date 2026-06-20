package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.CreateLedgerEntryUseCase;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * <p>분개 합계 검증 + 멱등은 {@link SingleLedgerEntryWriter} 가 건별 {@code REQUIRES_NEW}
 * 트랜잭션으로 수행한다. 본 서비스는 단건/일괄 라우팅만 담당하며, 일괄 처리는 writer 를
 * 프록시 경유로 호출해 각 정산이 독립 트랜잭션으로 커밋되도록 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateLedgerEntryService implements CreateLedgerEntryUseCase {

    private final SingleLedgerEntryWriter writer;

    @Override
    public List<LedgerEntry> createFromSettlement(Long settlementId) {
        return writer.write(settlementId);
    }

    @Override
    public List<LedgerEntry> createFromSettlements(List<Long> settlementIds) {
        if (settlementIds == null || settlementIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<LedgerEntry> all = new ArrayList<>();
        for (Long id : settlementIds) {
            try {
                // writer 는 REQUIRES_NEW — 각 정산이 독립 커밋되므로 한 건 실패가 다른 건을
                // 롤백시키지 않는다. 실패분은 후속 보강 배치/관리자 화면에서 재시도.
                all.addAll(writer.write(id));
            } catch (RuntimeException e) {
                log.error("Ledger entry creation failed for settlement {}: {}", id, e.getMessage(), e);
            }
        }
        return all;
    }
}
