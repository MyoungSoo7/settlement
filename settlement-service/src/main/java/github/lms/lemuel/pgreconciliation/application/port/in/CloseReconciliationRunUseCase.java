package github.lms.lemuel.pgreconciliation.application.port.in;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;

/**
 * 대사 마감 — 확정된 기간을 잠가 사후 변경을 막는다.
 *
 * <p>마감되면 같은 (PG, 날짜)로 새 대사를 열 수 없다. 미결(PENDING) 불일치가 남아 있으면
 * 마감이 거부되므로, 마감은 "이 기간의 모든 차이가 승인/거절로 결론났다"는 선언이기도 하다.
 */
public interface CloseReconciliationRunUseCase {

    /**
     * @param runId      마감할 대사 실행 ID (COMPLETED 상태여야 한다)
     * @param operatorId 마감 수행자 — 감사 추적에 남는다
     * @param note       마감 사유/메모 (선택)
     * @return 마감된 대사 실행
     */
    ReconciliationRun close(Long runId, String operatorId, String note);
}
