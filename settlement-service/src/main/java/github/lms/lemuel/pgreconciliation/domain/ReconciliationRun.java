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
    private String closedBy;
    private LocalDateTime closedAt;
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
        return rehydrate(id, pgProvider, targetDate, fileName, fileSha256, status,
                startedAt, finishedAt, totalPgRows, totalInternalRows,
                matchedCount, discrepancyCount, autoCorrectedCount, operatorId, note,
                discrepancies, null, null);
    }

    /** 마감 정보까지 포함한 복원 — 영속 계층이 CLOSED run 을 되살릴 때 쓴다. */
    public static ReconciliationRun rehydrate(Long id, String pgProvider, LocalDate targetDate,
                                               String fileName, String fileSha256,
                                               ReconciliationRunStatus status,
                                               LocalDateTime startedAt, LocalDateTime finishedAt,
                                               int totalPgRows, int totalInternalRows,
                                               int matchedCount, int discrepancyCount,
                                               int autoCorrectedCount, String operatorId, String note,
                                               List<ReconciliationDiscrepancy> discrepancies,
                                               String closedBy, LocalDateTime closedAt) {
        ReconciliationRun run = new ReconciliationRun(id, pgProvider, targetDate, fileName, fileSha256, status,
                startedAt, finishedAt, totalPgRows, totalInternalRows,
                matchedCount, discrepancyCount, autoCorrectedCount, operatorId, note,
                discrepancies != null ? discrepancies : new ArrayList<>());
        run.closedBy = closedBy;
        run.closedAt = closedAt;
        return run;
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

    /**
     * 대사를 마감해 해당 기간을 잠근다 — {@code COMPLETED → CLOSED}.
     *
     * <p><b>왜 필요한가</b>: 같은 파일 재업로드는 SHA-256 으로 이미 멱등 차단되지만, 같은
     * (PG, 날짜)에 <b>다른 파일</b>을 올리면 새 run 이 열려 이미 정산·지급이 끝난 기간에 새 불일치와
     * 새 clawback 이 생길 수 있다. 마감은 그 경로를 닫는다.
     *
     * <p><b>미결을 남긴 마감은 마감이 아니다</b>: PENDING 불일치가 하나라도 있으면 거부한다.
     * 미결을 안고 잠그면 "검토 완료"라는 기록만 남고 차이는 영구 미해결로 묻힌다. 자동 보정
     * (AUTO_CORRECTED)된 건은 이미 해소된 것이라 마감을 막지 않는다.
     *
     * <p>CLOSED 는 종착 상태다 — 재마감도, {@link #complete} 로의 되돌림도 없다. 되열어야 할
     * 사정이 생기면 이미 집행된 clawback 과의 이중 조정 위험이 있으므로 별도 승인 경로로
     * 설계해야 한다(현재 미구현).
     */
    public void close(String operatorId, String closeNote) {
        if (this.status != ReconciliationRunStatus.COMPLETED) {
            throw new InvalidReconciliationStateException(status, ReconciliationRunStatus.CLOSED);
        }
        long pending = discrepancies.stream()
                .filter(d -> d.getStatus() == DiscrepancyStatus.PENDING)
                .count();
        if (pending > 0) {
            throw new InvalidReconciliationStateException(
                    "미결 불일치 " + pending + "건이 남아 마감할 수 없습니다 — 승인/거절로 먼저 해소하세요");
        }
        this.status = ReconciliationRunStatus.CLOSED;
        this.closedBy = operatorId;
        this.closedAt = LocalDateTime.now();
        if (closeNote != null && !closeNote.isBlank()) {
            this.note = closeNote;
        }
    }

    /** 마감되었는가 — 서비스가 같은 (PG, 날짜) 새 대사 차단 판정에 쓴다. */
    public boolean isClosed() {
        return status == ReconciliationRunStatus.CLOSED;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
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
