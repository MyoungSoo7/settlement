package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.BackfillMissingReverseUseCase;
import github.lms.lemuel.ledger.application.port.out.LedgerReverseBackfillPort;
import github.lms.lemuel.ledger.domain.LedgerReverseBackfillReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 원장 역분개 누락 백필 서비스 — 페이지 루프 오케스트레이션만 담당.
 *
 * <p>탐지는 INV-5 missingReverse 기준({@link LedgerReverseBackfillPort})을 재사용한다.
 * 실제 역분개 아웃박스 적재와 페이지별 트랜잭션 커밋은 포트(어댑터)에 위임한다
 * — {@link ReencryptPayoutPiiService} 와 동일한 루프 패턴.
 *
 * <p><b>멱등 보장</b>:
 * <ul>
 *   <li>중복 아웃박스 적재 → {@code ReverseEntryService.existsByReference} 로 스킵</li>
 *   <li>동시 실행 경합 → {@code uq_ledger_reference_accounts} UNIQUE 제약으로 이중 분개 차단</li>
 * </ul>
 *
 * <p>서비스 메서드 자체는 트랜잭션을 열지 않는다: 페이지 단위 커밋(부분 성공 보존)을 위해
 * 포트 어댑터가 각 페이지를 독립 {@code @Transactional} 메서드로 처리하기 때문이다.
 */
@Service
public class BackfillMissingReverseService implements BackfillMissingReverseUseCase {

    private static final int MAX_PAGE_SIZE = 1000;

    private final LedgerReverseBackfillPort backfillPort;
    private final int defaultPageSize;

    public BackfillMissingReverseService(
            LedgerReverseBackfillPort backfillPort,
            @Value("${app.ledger.reverse-backfill.page-size:200}") int defaultPageSize) {
        this.backfillPort = backfillPort;
        this.defaultPageSize = defaultPageSize;
    }

    @Override
    public LedgerReverseBackfillReport backfillMissingReverse(Integer pageSizeOverride) {
        int pageSize = clampPageSize(pageSizeOverride);
        long initialMissing = backfillPort.countMissingReverseAdjustments();
        // 안전 상한: 초기 누락 건수 기반 최대 페이지 수 + 여유 2페이지.
        // 아웃박스 적재 후 폴러가 처리하기 전까지 count 는 감소하지 않으므로,
        // 커서(afterId) 기반으로 전체 범위를 정확히 1회 순회하면 상한에 먼저 도달한다.
        long maxPages = (initialMissing / pageSize) + 2;

        long enqueuedChargeback = 0;
        long enqueuedReconciliation = 0;
        int pagesCommitted = 0;
        long afterId = 0;

        while (pagesCommitted < maxPages) {
            LedgerReverseBackfillPort.PageResult result = backfillPort.enqueueReversePage(afterId, pageSize);
            if (result.count() == 0) {
                break;
            }
            enqueuedChargeback += result.chargebackEnqueued();
            enqueuedReconciliation += result.reconEnqueued();
            afterId = result.lastId();
            pagesCommitted++;
        }

        // 폴러가 아직 처리하지 않아 잔여가 남을 수 있다 — 폴러 완료 후 재확인 권장.
        long remaining = backfillPort.countMissingReverseAdjustments();
        return LedgerReverseBackfillReport.of(
                pageSize, enqueuedChargeback, enqueuedReconciliation, remaining, pagesCommitted);
    }

    @Override
    public LedgerReverseBackfillReport statusMissingReverse() {
        return LedgerReverseBackfillReport.status(backfillPort.countMissingReverseAdjustments());
    }

    private int clampPageSize(Integer override) {
        if (override == null || override <= 0) {
            return defaultPageSize;
        }
        return Math.min(override, MAX_PAGE_SIZE);
    }
}
