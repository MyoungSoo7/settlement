package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.DisclosureDelivery;
import github.lms.lemuel.insurance.domain.SalesChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * disclosure_deliveries 테이블 매핑 (V7) — append-only 완전판매 증빙.
 *
 * <p>@Version 없음 — UPDATE 자체가 트리거로 차단되는 INSERT 전용 테이블이다.
 */
@Entity
@Table(name = "disclosure_deliveries", schema = "opslab")
public class DisclosureDeliveryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "application_id")
    private UUID applicationId;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "sales_channel", nullable = false, length = 20)
    private SalesChannel salesChannel;

    @Column(name = "delivered_by", nullable = false, length = 64)
    private String deliveredBy;

    @Column(name = "partner_bank_code", length = 32)
    private String partnerBankCode;

    @Column(name = "contractor_name", nullable = false, length = 100)
    private String contractorName;

    @Column(name = "document_sha256", nullable = false, length = 64)
    private String documentSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "product_snapshot", nullable = false)
    private String productSnapshot;

    @Column(name = "delivered_at", insertable = false, updatable = false)
    private Instant deliveredAt;

    protected DisclosureDeliveryJpaEntity() {
    }

    public static DisclosureDeliveryJpaEntity fromDomain(DisclosureDelivery d, String productSnapshotJson) {
        DisclosureDeliveryJpaEntity e = new DisclosureDeliveryJpaEntity();
        e.deliveryId = UUID.fromString(d.getDeliveryId());
        e.applicationId = d.getApplicationId() != null ? UUID.fromString(d.getApplicationId()) : null;
        e.productCode = d.getProductCode();
        e.salesChannel = d.getSalesChannel();
        e.deliveredBy = d.getDeliveredBy();
        e.partnerBankCode = d.getPartnerBankCode();
        e.contractorName = d.getContractorName();
        e.documentSha256 = d.getDocumentSha256();
        e.productSnapshot = productSnapshotJson;
        return e;
    }

    public DisclosureDelivery toDomain() {
        return DisclosureDelivery.rehydrate(
                id,
                deliveryId.toString(),
                applicationId != null ? applicationId.toString() : null,
                productCode,
                salesChannel,
                deliveredBy,
                partnerBankCode,
                contractorName,
                documentSha256);
    }
}
