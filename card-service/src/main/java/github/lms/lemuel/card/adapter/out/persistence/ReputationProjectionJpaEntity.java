package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * reputation_projection 테이블 매핑 (V4). PK 는 seller_id(String) — company.reputation_changed
 * 이벤트의 sellerIds 배열 원소를 String.valueOf() 로 변환한 값이다
 * ({@link github.lms.lemuel.card.application.port.in.IngestReputationUseCase} 클래스 주석 참조).
 */
@Entity
@Table(name = "reputation_projection")
public class ReputationProjectionJpaEntity {

    @Id
    @Column(name = "seller_id", length = 64)
    private String sellerId;

    @Column(nullable = false, length = 2)
    private String grade;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    protected ReputationProjectionJpaEntity() {
    }

    public ReputationProjectionJpaEntity(String sellerId, String grade) {
        this.sellerId = sellerId;
        this.grade = grade;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getSellerId() {
        return sellerId;
    }

    public String getGrade() {
        return grade;
    }

    void setGrade(String grade) {
        this.grade = grade;
    }
}
