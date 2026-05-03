package github.lms.lemuel.pgreconciliation.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(pgProvider, "pgProvider");
        Objects.requireNonNull(targetDate, "targetDate");
        Objects.requireNonNull(fileName, "fileName");
        return new ReconciliationRun(null, pgProvider, targetDate, fileName,
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
        return new ReconciliationRun(id, pgProvider, targetDate, fileName, status,
                startedAt, finishedAt, totalPgRows, totalInternalRows,
                matchedCount, discrepancyCount, autoCorrectedCount, operatorId, note,
                discrepancies != null ? discrepancies : new ArrayList<>());
    }

    private ReconciliationRun(Long id, String pgProvider, LocalDate targetDate, String fileName,
                              ReconciliationRunStatus status, LocalDateTime startedAt,
                              LocalDateTime finishedAt, int totalPgRows, int totalInternalRows,
                              int matchedCount, int discrepancyCount, int autoCorrectedCount,
                              String operatorId, String note,
                              List<ReconciliationDiscrepancy> discrepancies) {
        this.id = id;
        this.pgProvider = pgProvider;
        this.targetDate = targetDate;
        this.fileName = fileName;
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
            throw new IllegalStateException("RUNNING 상태에서만 완료 가능합니다: " + status);
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
