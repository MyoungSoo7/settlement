package github.lms.lemuel.closing.domain;

/**
 * 정보계 월마감 run 상태 — {@code RUNNING → COMPLETED | FAILED}.
 *
 * <p>COMPLETED/FAILED 는 종결 상태다. 실패한 마감의 재시도는 상태 재개가 아니라
 * <b>새 run 생성</b>(기간당 최신 run 이 upsert)으로만 한다.
 */
public enum ClosingRunStatus {
    RUNNING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ClosingRunStatus target) {
        return this == RUNNING && (target == COMPLETED || target == FAILED);
    }
}
