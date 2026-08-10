package github.lms.lemuel.pgreconciliation.domain;

public enum ReconciliationRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    /** 운영자가 마감한 대사 — 종착 상태. 같은 (PG, 날짜)로 새 대사를 열 수 없다. */
    CLOSED
}
