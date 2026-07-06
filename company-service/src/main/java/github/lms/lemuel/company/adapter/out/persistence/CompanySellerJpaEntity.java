package github.lms.lemuel.company.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** user.registered 로 수신한 셀러(회원) 등록 정보 — 기업 링크 대상 목록. */
@Entity
@Table(name = "company_sellers")
public class CompanySellerJpaEntity {

    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CompanySellerJpaEntity() { }

    public CompanySellerJpaEntity(Long sellerId, String email, Instant updatedAt) {
        this.sellerId = sellerId;
        this.email = email;
        this.updatedAt = updatedAt;
    }

    public Long getSellerId() { return sellerId; }
    public String getEmail() { return email; }
    public Instant getUpdatedAt() { return updatedAt; }
}
