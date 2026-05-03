package github.lms.lemuel.report.domain;

import java.util.List;

/**
 * 리포트 기간 전체에 대한 대사(reconciliation) 결과 묶음.
 *
 * <p>{@code matched} 는 모든 개별 {@link ReconciliationCheck} 가 통과한 경우에만 true.
 * 어느 하나라도 실패하면 금액이 샜다는 의미이므로 Alertmanager 연계 대상.
 */
public record CashflowReconciliation(
        boolean matched,
        int checksRun,
        List<ReconciliationCheck> checks,
        List<ReconciliationCheck> mismatches
) {
    public static CashflowReconciliation of(List<ReconciliationCheck> checks) {
        List<ReconciliationCheck> safe = checks != null ? List.copyOf(checks) : List.of();
        List<ReconciliationCheck> failed = safe.stream()
                .filter(c -> !c.passed())
                .toList();
        return new CashflowReconciliation(failed.isEmpty(), safe.size(), safe, failed);
    }

    public static CashflowReconciliation empty() {
        return new CashflowReconciliation(true, 0, List.of(), List.of());
    }
}
