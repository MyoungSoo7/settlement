package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import github.lms.lemuel.pgreconciliation.domain.ReconciliationRunStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "pg_reconciliation_runs")
public class PgReconciliationRunJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pg_provider", nullable = false, length = 20)
    private String pgProvider;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationRunStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "total_pg_rows", nullable = false)
    private int totalPgRows;

    @Column(name = "total_internal_rows", nullable = false)
    private int totalInternalRows;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "discrepancy_count", nullable = false)
    private int discrepancyCount;

    @Column(name = "auto_corrected_count", nullable = false)
    private int autoCorrectedCount;

    @Column(name = "operator_id", length = 100)
    private String operatorId;

    @Column(columnDefinition = "text")
    private String note;

    protected PgReconciliationRunJpaEntity() { }

    public PgReconciliationRunJpaEntity(Long id, String pgProvider, LocalDate targetDate, String fileName,
                                        ReconciliationRunStatus status, LocalDateTime startedAt,
                                        LocalDateTime finishedAt, int totalPgRows, int totalInternalRows,
                                        int matchedCount, int discrepancyCount, int autoCorrectedCount,
                                        String operatorId, String note) {
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
}
