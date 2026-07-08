package github.lms.lemuel.company.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 셀러↔기업(종목코드) 명시 링크. 한 셀러는 한 기업에 링크(sellerId PK). */
@Entity
@Table(name = "company_seller_links")
public class CompanySellerLinkJpaEntity {

    @Id
    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected CompanySellerLinkJpaEntity() { }

    public CompanySellerLinkJpaEntity(Long sellerId, String stockCode, Instant linkedAt) {
        this.sellerId = sellerId;
        this.stockCode = stockCode;
        this.linkedAt = linkedAt;
    }

    public Long getSellerId() { return sellerId; }
    public String getStockCode() { return stockCode; }
    public Instant getLinkedAt() { return linkedAt; }
}
