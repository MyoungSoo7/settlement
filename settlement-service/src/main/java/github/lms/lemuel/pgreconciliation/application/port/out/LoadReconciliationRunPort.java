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
}
