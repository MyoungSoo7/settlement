package github.lms.lemuel.ledger.application.port.out;

/**
 * 원장 역분개 누락 백필 아웃바운드 포트.
 *
 * <p>탐지 SQL 은 INV-5 {@code missingReverseAdjustmentIds} 와 동일 기준 — grace window 없이
 * 전체 기간을 대상으로 하며 {@code afterId} 커서로 페이지네이션한다.
 * 새 탐지 로직을 중복 작성하지 않는다.
 *
 * <p>구현 어댑터({@code LedgerReverseBackfillPersistenceAdapter})는
 * {@code enqueueReversePage} 를 {@code @Transactional} 로 선언해 페이지 단위 커밋을
 * 보장한다(부분 성공 보존).
 */
public interface LedgerReverseBackfillPort {

    /** 역분개(CHARGEBACK / PG_RECONCILIATION)가 없는 조정 총 건 수. */
    long countMissingReverseAdjustments();

    /**
     * {@code id > afterId} 인 역분개 누락 조정을 최대 {@code pageSize} 건 조회해
     * {@code ledger_outbox} 에 역분개 작업을 적재하고 커밋한다. 각 호출은 독립 트랜잭션이다.
     *
     * @return 처리 건수·마지막 조정 id·출처별 건수를 담은 PageResult
     *         (count == 0 이면 더 이상 처리할 항목 없음 → 루프 종료 신호)
     */
    PageResult enqueueReversePage(long afterId, int pageSize);

    /** 한 페이지 처리 결과. */
    record PageResult(int count, long lastId, int chargebackEnqueued, int reconEnqueued) {

        /** 빈 페이지 (루프 종료 신호). */
        public static PageResult empty(long afterId) {
            return new PageResult(0, afterId, 0, 0);
        }
    }
}
