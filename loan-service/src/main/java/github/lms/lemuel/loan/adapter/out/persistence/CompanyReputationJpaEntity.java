package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.CompanyReputation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "company_reputation")
public class CompanyReputationJpaEntity {

    /** 종목코드 (이벤트로 수신 — 생성 전략 없음, 멱등 UPSERT 키). */
    @Id
    @Column(name = "stock_code", length = 6)
    private String stockCode;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "grade", nullable = false, length = 1)
    private String grade;

    @Column(name = "previous_grade", length = 1)
    private String previousGrade;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CompanyReputationJpaEntity() { }

    public CompanyReputationJpaEntity(String stockCode, int score, String grade, String previousGrade,
                                      LocalDate snapshotDate, LocalDateTime updatedAt) {
        this.stockCode = stockCode;
        this.score = score;
        this.grade = grade;
        this.previousGrade = previousGrade;
        this.snapshotDate = snapshotDate;
        this.updatedAt = updatedAt;
    }

    public CompanyReputation toDomain() {
        return new CompanyReputation(stockCode, score, grade, previousGrade, snapshotDate);
    }

    public String getStockCode() { return stockCode; }
    public int getScore() { return score; }
    public String getGrade() { return grade; }
    public String getPreviousGrade() { return previousGrade; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
