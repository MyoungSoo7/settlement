package github.lms.lemuel.pgreconciliation.domain;

import github.lms.lemuel.pgreconciliation.domain.exception.InvalidReconciliationStateException;
import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 1회 PG 정산파일 대사 실행의 집합 루트(aggregate root).
 *
 * <p>운영자가 PG 파일 1개를 업로드하면 1개의 {@code ReconciliationRun} 이 생성되고
 * 그 안에 발견된 모든 {@link ReconciliationDiscrepancy} 가 자식으로 매달린다.
 *
 * <p>같은 PG · 같은 날짜에 대해 여러 번 재실행 가능 (운영자 승인 워크플로 검증용).
 * 각 실행은 별도 row 로 누적되어 감사 추적이 가능하다.
 */
public class ReconciliationRun {

    private Long id;
    private final String pgProvider;
    private final LocalDate targetDate;
    private final String fileName;
    /** 업로드 파일 내용 SHA-256(hex) — 같은 파일 재업로드 멱등 판정 키. 레거시 run 은 null. */
    private final String fileSha256;
    private ReconciliationRunStatus status;
    private final LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private int totalPgRows;
    private int totalInternalRows;
    private int matchedCount;
    private int discrepancyCount;
    private int autoCorrectedCount;
    private final String operatorId;
    private String note;
    private final List<ReconciliationDiscrepancy> discrepancies;

    public static ReconciliationRun start(String pgProvider, LocalDate targetDate,
                                          String fileName, String operatorId) {
        return start(pgProvider, targetDate, fileName, operatorId, null);
    }

    /** 파일 해시 포함 생성 — 같은 파일 재업로드 멱등 판정에 쓰인다. */
    public static ReconciliationRun start(String pgProvider, LocalDate targetDate,
                                          String fileName, String operatorId, String fileSha256) {
        if (pgProvider == null) throw new PgReconciliationInvariantViolationException("pgProvider 는 필수입니다");
        if (targetDate == null) throw new PgReconciliationInvariantViolationException("targetDate 는 필수입니다");
        if (fileName == null) throw new PgReconciliationInvariantViolationException("fileName 는 필수입니다");
        return new ReconciliationRun(null, pgProvider, targetDate, fileName, fileSha256,
                ReconciliationRunStatus.RUNNING, LocalDateTime.now(), null,
                0, 0, 0, 0, 0, operatorId, null, new ArrayList<>());
    }

    public static ReconciliationRun rehydrate(Long id, String pgProvider, LocalDate targetDate,
                                               String fileName, ReconciliationRunStatus status,
                                               LocalDateTime startedAt, LocalDateTime finishedAt,
                                               int totalPgRows, int totalInternalRows,
                                               int matchedCount, int discrepancyCount,
                                               int autoCorrectedCount, String operatorId, String note,
                                               List<ReconciliationDiscrepancy> discrepancies) {
        return rehydrate(id, pgProvider, targetDate, fileName, null, status,
                startedAt, finishedAt, totalPgRows, totalInternalRows,
                matchedCount, discrepancyCount, autoCorrectedCount, operatorId, note, discrepancies);
    }

    public static ReconciliationRun rehydrate(Long id, String pgProvider, LocalDate targetDate,
                                               String fileName, String fileSha256,
                                               ReconciliationRunStatus status,
                                               LocalDateTime startedAt, LocalDateTime finishedAt,
                                               int totalPgRows, int totalInternalRows,
                                               int matchedCount, int discrepancyCount,
                                               int autoCorrectedCount, String operatorId, String note,
                                               List<ReconciliationDiscrepancy> discrepancies) {
        return new ReconciliationRun(id, pgProvider, targetDate, fileName, fileSha256, status,
                startedAt, finishedAt, totalPgRows, totalInternalRows,
                matchedCount, discrepancyCount, autoCorrectedCount, operatorId, note,
                discrepancies != null ? discrepancies : new ArrayList<>());
    }

    private ReconciliationRun(Long id, String pgProvider, LocalDate targetDate, String fileName,
                              String fileSha256, ReconciliationRunStatus status, LocalDateTime startedAt,
                              LocalDateTime finishedAt, int totalPgRows, int totalInternalRows,
                              int matchedCount, int discrepancyCount, int autoCorrectedCount,
                              String operatorId, String note,
                              List<ReconciliationDiscrepancy> discrepancies) {
        this.id = id;
        this.pgProvider = pgProvider;
        this.targetDate = targetDate;
        this.fileName = fileName;
        this.fileSha256 = fileSha256;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.totalPgRows = totalPgRows;
        this.totalInternalRows = totalInternalRows;
        this.matchedCount = matchedCount;
        this.discrepancyCount = discrepancyCount;
        this.autoCorrectedCount = autoCorrectedCount;
        this.operatorId = operatorId;
        this.note = note;
        this.discrepancies = discrepancies;
    }

    /**
     * 비교 결과를 일괄 누적하고 RUNNING → COMPLETED 로 마감한다.
     */
    public void complete(int totalPgRows, int totalInternalRows,
                         int matchedCount, List<ReconciliationDiscrepancy> found) {
        if (this.status != ReconciliationRunStatus.RUNNING) {
            throw new InvalidReconciliationStateException(status, ReconciliationRunStatus.COMPLETED);
        }
        this.totalPgRows = totalPgRows;
        this.totalInternalRows = totalInternalRows;
        this.matchedCount = matchedCount;
        this.discrepancies.addAll(found);
        this.discrepancyCount = (int) found.stream()
                .filter(d -> d.getStatus() != DiscrepancyStatus.AUTO_CORRECTED)
                .count();
        this.autoCorrectedCount = (int) found.stream()
                .filter(d -> d.getStatus() == DiscrepancyStatus.AUTO_CORRECTED)
                .count();
        this.status = ReconciliationRunStatus.COMPLETED;
        this.finishedAt = LocalDateTime.now();
    }

    public void fail(String reason) {
        this.status = ReconciliationRunStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
        this.note = reason;
    }

    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여 가능");
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public String getPgProvider() { return pgProvider; }
    public LocalDate getTargetDate() { return targetDate; }
    public String getFileName() { return fileName; }
    public String getFileSha256() { return fileSha256; }
    public ReconciliationRunStatus getStatus() { return status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public int getTotalPgRows() { return totalPgRows; }
    public int getTotalInternalRows() { return totalInternalRows; }
    public int getMatchedCount() { return matchedCount; }
    public int getDiscrepancyCount() { return discrepancyCount; }
    public int getAutoCorrectedCount() { return autoCorrectedCount; }
    public String getOperatorId() { return operatorId; }
    public String getNote() { return note; }
    public List<ReconciliationDiscrepancy> getDiscrepancies() { return List.copyOf(discrepancies); }
}
