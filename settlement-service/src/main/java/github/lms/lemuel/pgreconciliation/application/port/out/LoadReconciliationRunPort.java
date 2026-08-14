package github.lms.lemuel.pgreconciliation.application.port.out;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;

import java.util.List;
import java.util.Optional;

public interface LoadReconciliationRunPort {

    Optional<ReconciliationRun> findById(Long id);

    List<ReconciliationRun> findRecent(int limit);

    Optional<ReconciliationDiscrepancy> findDiscrepancyById(Long id);

    /** 같은 파일 내용(SHA-256)으로 이미 COMPLETED 된 run — 재업로드 멱등 판정. FAILED 는 재시도 허용. */
    Optional<ReconciliationRun> findCompletedByFileSha256(String fileSha256);

    /**
     * 같은 (PG, 날짜)로 이미 마감(CLOSED)된 run — 새 대사 차단 판정.
     *
     * <p>파일 해시 멱등은 <b>같은 파일</b>만 막는다. 다른 파일이 같은 기간으로 들어오면 새 run 이
     * 열려 확정된 기간에 새 조정이 생기므로, 마감 여부를 별도로 확인해야 한다.
     */
    Optional<ReconciliationRun> findClosedByProviderAndDate(String pgProvider, java.time.LocalDate targetDate);
}
