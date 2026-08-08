package github.lms.lemuel.closing.domain;

import github.lms.lemuel.closing.domain.exception.ClosingInvariantViolationException;
import github.lms.lemuel.closing.domain.exception.InvalidClosingRunStateException;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * 정보계 월마감 run — 셀러 월 정산 마트 적재 1회 실행의 감사 단위(기간당 최신 1행).
 *
 * <p>{@code RUNNING} 으로 시작해 집계·적재가 끝나면 {@link #complete} 로 합계 스냅샷
 * ({@link ClosingTotals} + 건수·미매핑·미확정 카운트)을 못박고 {@code COMPLETED} 가 된다.
 * 실패하면 {@link #fail} 로 사유를 남긴다. 두 상태 모두 종결 — 재마감은 새 run 이
 * 기간 단위 upsert 로 대체한다(원장 마감된 기간은 서비스가 재마감을 거부).
 *
 * <p>마감 대상은 <b>완결된 과거 월</b>뿐이다 — 당월·미래월은 아직 정산이 유입 중이라
 * 마트가 확정 실적이 될 수 없으므로 생성 시점에 거부한다.
 *
 * <p>시각은 전부 UTC {@link OffsetDateTime}(N1 데이터 표준). 불변 원칙: 식별 필드
 * (period·triggeredBy·startedAt·createdAt)는 {@code final} 봉인, 상태 전이 메서드만 가변 필드를
 * 1회 갱신한다. public setter 없음, 영속 복원은 {@link #rehydrate} 전용.
 */
public class MonthlyClosingRun {

    // ── 불변 식별 필드 ────────────────────────────────────────────────
    private final YearMonth period;
    private final String triggeredBy;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime createdAt;

    // ── 가변 필드 (PK 1회 부여·종결 전이 시에만 변경) ──────────────────
    private Long id;
    private ClosingRunStatus status;
    private OffsetDateTime finishedAt;
    private int sellerCount;
    private long settlementCount;
    private long unmappedCount;
    private long pendingCount;
    private ClosingTotals totals;
    private String failureReason;

    private MonthlyClosingRun(Long id, YearMonth period, ClosingRunStatus status, String triggeredBy,
                              OffsetDateTime startedAt, OffsetDateTime finishedAt,
                              int sellerCount, long settlementCount, long unmappedCount, long pendingCount,
                              ClosingTotals totals, String failureReason, OffsetDateTime createdAt) {
        this.id = id;
        this.period = period;
        this.status = status;
        this.triggeredBy = triggeredBy;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.sellerCount = sellerCount;
        this.settlementCount = settlementCount;
        this.unmappedCount = unmappedCount;
        this.pendingCount = pendingCount;
        this.totals = totals;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
    }

    /**
     * 신규 마감 run 시작 — RUNNING, 집계 스냅샷 없음.
     *
     * @param period       마감 대상 월
     * @param triggeredBy  실행 주체(운영자 또는 {@code scheduler}) — 감사 추적
     * @param currentMonth 현재 월(시각 소스는 호출자 책임) — 당월·미래월 마감 차단 기준
     */
    public static MonthlyClosingRun start(YearMonth period, String triggeredBy, YearMonth currentMonth) {
        if (period == null) {
            throw new ClosingInvariantViolationException("period 필수");
        }
        if (triggeredBy == null || triggeredBy.isBlank()) {
            throw new ClosingInvariantViolationException("triggeredBy 필수");
        }
        if (currentMonth == null) {
            throw new ClosingInvariantViolationException("currentMonth 필수");
        }
        if (!period.isBefore(currentMonth)) {
            throw new ClosingInvariantViolationException(
                    "완결된 과거 월만 마감할 수 있습니다: period=" + period + ", current=" + currentMonth);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new MonthlyClosingRun(null, period, ClosingRunStatus.RUNNING, triggeredBy,
                now, null, 0, 0, 0, 0, null, null, now);
    }

    /** 영속 레코드 복원 전용(어댑터 toDomain). 저장된 상태를 그대로 재구성한다(검증 재실행 없음). */
    public static MonthlyClosingRun rehydrate(Long id, YearMonth period, ClosingRunStatus status,
                                              String triggeredBy, OffsetDateTime startedAt,
                                              OffsetDateTime finishedAt, int sellerCount,
                                              long settlementCount, long unmappedCount, long pendingCount,
                                              ClosingTotals totals, String failureReason,
                                              OffsetDateTime createdAt) {
        return new MonthlyClosingRun(id, period, status, triggeredBy, startedAt, finishedAt,
                sellerCount, settlementCount, unmappedCount, pendingCount, totals, failureReason, createdAt);
    }

    /**
     * 마감 완료 — RUNNING → COMPLETED 전이 + 집계 스냅샷 못박기.
     *
     * @param sellerCount     마트에 적재된 셀러 수
     * @param settlementCount 집계된 DONE 정산 건수(매핑 성공분)
     * @param unmappedCount   셀러 매핑 실패로 마트에서 빠진 DONE 정산 건수(프로젝션 lag 감시)
     * @param pendingCount    아직 미확정(REQUESTED/PROCESSING) 정산 건수 — 마감 후 유입 감시
     * @param totals          합계 스냅샷
     */
    public void complete(int sellerCount, long settlementCount, long unmappedCount, long pendingCount,
                         ClosingTotals totals) {
        if (!status.canTransitionTo(ClosingRunStatus.COMPLETED)) {
            throw new InvalidClosingRunStateException(status, ClosingRunStatus.COMPLETED);
        }
        if (totals == null) {
            throw new ClosingInvariantViolationException("totals 필수");
        }
        if (sellerCount < 0 || settlementCount < 0 || unmappedCount < 0 || pendingCount < 0) {
            throw new ClosingInvariantViolationException("마감 건수는 음수일 수 없습니다");
        }
        this.status = ClosingRunStatus.COMPLETED;
        this.sellerCount = sellerCount;
        this.settlementCount = settlementCount;
        this.unmappedCount = unmappedCount;
        this.pendingCount = pendingCount;
        this.totals = totals;
        this.finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 마감 실패 — RUNNING → FAILED 전이 + 사유 기록. 재시도는 새 run 으로만. */
    public void fail(String reason) {
        if (!status.canTransitionTo(ClosingRunStatus.FAILED)) {
            throw new InvalidClosingRunStateException(status, ClosingRunStatus.FAILED);
        }
        if (reason == null || reason.isBlank()) {
            throw new ClosingInvariantViolationException("실패 사유 필수");
        }
        this.status = ClosingRunStatus.FAILED;
        this.failureReason = reason;
        this.finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 영속 후 DB PK 를 1회만 주입(write-once). */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    public boolean isCompleted() {
        return status == ClosingRunStatus.COMPLETED;
    }

    // ========== Getters ==========

    public Long getId() {
        return id;
    }

    public YearMonth getPeriod() {
        return period;
    }

    /** 영속·표현 경계용 "YYYY-MM" 문자열. */
    public String getPeriodYm() {
        return period.toString();
    }

    public ClosingRunStatus getStatus() {
        return status;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public int getSellerCount() {
        return sellerCount;
    }

    public long getSettlementCount() {
        return settlementCount;
    }

    public long getUnmappedCount() {
        return unmappedCount;
    }

    public long getPendingCount() {
        return pendingCount;
    }

    public ClosingTotals getTotals() {
        return totals;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
