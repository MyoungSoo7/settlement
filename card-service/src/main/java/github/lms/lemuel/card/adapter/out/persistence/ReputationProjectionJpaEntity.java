package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * company-service 평판 이벤트의 셀러별 등급 프로젝션 행.
 * PK = seller_id (이벤트 sellerIds 팬아웃 — 멱등 UPSERT 키).
 */
@Entity
@Table(name = "reputation_projection")
public class ReputationProjectionJpaEntity {

    @Id
    @Column(name = "seller_id", length = 64)
    private String sellerId;

    @Column(name = "grade", nullable = false, length = 2)
    private String grade;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReputationProjectionJpaEntity() { }

    public ReputationProjectionJpaEntity(String sellerId, String grade, Instant updatedAt) {
        this.sellerId = sellerId;
        this.grade = grade;
        this.updatedAt = updatedAt;
    }

    public String getSellerId() { return sellerId; }
    public String getGrade() { return grade; }
    public Instant getUpdatedAt() { return updatedAt; }
}
