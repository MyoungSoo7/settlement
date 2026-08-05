package github.lms.lemuel.settlement.domain.rerun;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 배치 재실행 결과 — 단계별 결과를 그대로 보존한다.
 *
 * <p><b>부분 실패를 삼키지 않는다</b>: 한 단계가 실패해도 나머지 단계는 계속 실행되고, 각 단계의
 * 성공/실패가 리포트에 남는다. 운영자는 이 리포트를 보고 <b>실패한 단계만</b> 다시 돌린다 —
 * 전체를 다시 돌릴 필요가 없어야 재실행이 실제로 쓰인다.
 *
 * @param targetDate 재실행 대상 일자
 * @param steps      단계별 실행 결과 (실행 순서 보존, 불변)
 */
public record SettlementRerunReport(LocalDate targetDate, List<StepResult> steps) {

    /** 생성 시점에 방어적 복사 — 호출측 리스트 변경이 리포트에 새어들지 않는다. */
    public SettlementRerunReport {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public enum StepStatus { SUCCEEDED, FAILED }

    /**
     * 한 단계의 실행 결과.
     *
     * @param affected 처리 건수 — 실패 단계는 항상 0 이다(실패를 성과로 집계하지 않는다)
     * @param detail   성공 요약 또는 실패 사유
     */
    public record StepResult(SettlementRerunScope scope, StepStatus status, long affected, String detail) {

        public static StepResult succeeded(SettlementRerunScope scope, long affected, String detail) {
            return new StepResult(scope, StepStatus.SUCCEEDED, Math.max(affected, 0), detail);
        }

        public static StepResult failed(SettlementRerunScope scope, String detail) {
            return new StepResult(scope, StepStatus.FAILED, 0, detail);
        }

        public boolean isFailed() {
            return status == StepStatus.FAILED;
        }
    }

    /** 모든 단계가 성공했는가 — false 면 {@link #failedSteps()} 를 보고 해당 단계만 재실행한다. */
    public boolean complete() {
        return steps.stream().noneMatch(StepResult::isFailed);
    }

    /** 성공 단계의 처리 건수 합계. */
    public long totalAffected() {
        return steps.stream().mapToLong(StepResult::affected).sum();
    }

    /** 실패한 단계 목록 — 운영자의 다음 재실행 대상. */
    public List<SettlementRerunScope> failedSteps() {
        return steps.stream().filter(StepResult::isFailed).map(StepResult::scope).toList();
    }
}
