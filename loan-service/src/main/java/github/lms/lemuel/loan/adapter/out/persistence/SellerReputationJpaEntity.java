package github.lms.lemuel.loan.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 셀러(법인)별 평판 등급 프로젝션 — 신용 한도 haircut 산정용 (ADR 0023 Phase 3 후속).
 * company 의 CompanyReputationChanged 이벤트에 동봉된 linked sellerId 로 적재된다.
 */
@Entity
@Table(name = "seller_reputation")
public class SellerReputationJpaEntity {

    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "grade", nullable = false, length = 1)
    private String grade;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SellerReputationJpaEntity() { }

    public SellerReputationJpaEntity(Long sellerId, String stockCode, int score, String grade,
                                     LocalDateTime updatedAt) {
        this.sellerId = sellerId;
        this.stockCode = stockCode;
        this.score = score;
        this.grade = grade;
        this.updatedAt = updatedAt;
    }

    public Long getSellerId() { return sellerId; }
    public String getStockCode() { return stockCode; }
    public int getScore() { return score; }
    public String getGrade() { return grade; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
