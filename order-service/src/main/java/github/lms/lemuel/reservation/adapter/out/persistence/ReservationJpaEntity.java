package github.lms.lemuel.reservation.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Reservation JPA Entity (인프라 레이어, 도메인과 분리)
 * DB 스키마: opslab.reservations (V20260610090100)
 */
@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "technician_id")
    private Long technicianId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "site_address", nullable = false, length = 300)
    private String siteAddress;

    @Column(name = "site_password", length = 50)
    private String sitePassword;

    @Column(name = "site_manager_name", nullable = false, length = 100)
    private String siteManagerName;

    @Column(name = "site_manager_phone", nullable = false, length = 30)
    private String siteManagerPhone;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "wood_species", length = 100)
    private String woodSpecies;

    @Column(length = 100)
    private String brand;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "product_size", length = 50)
    private String productSize;

    @Column(name = "construction_area", nullable = false, precision = 10, scale = 2)
    private BigDecimal constructionArea;

    @Column(name = "field_measured", nullable = false)
    private Boolean fieldMeasured;

    @Column(nullable = false)
    private Boolean expansion;

    @Column(name = "expansion_area", nullable = false, precision = 10, scale = 2)
    private BigDecimal expansionArea;

    @Column(name = "new_floor", nullable = false)
    private Boolean newFloor;

    @Column(nullable = false)
    private Boolean baseboard;

    @Column(name = "protection_work", nullable = false)
    private Boolean protectionWork;

    @Column(name = "protection_area", nullable = false, precision = 10, scale = 2)
    private BigDecimal protectionArea;

    @Column(name = "protection_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal protectionFee;

    @Column(name = "additional_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal additionalFee;

    @Column(length = 1000)
    private String note;

    @Column(name = "canceled_reason", length = 500)
    private String canceledReason;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "REQUESTED";
        if (expansionArea == null) expansionArea = BigDecimal.ZERO;
        if (protectionArea == null) protectionArea = BigDecimal.ZERO;
        if (protectionFee == null) protectionFee = BigDecimal.ZERO;
        if (additionalFee == null) additionalFee = BigDecimal.ZERO;
        if (fieldMeasured == null) fieldMeasured = false;
        if (expansion == null) expansion = false;
        if (newFloor == null) newFloor = false;
        if (baseboard == null) baseboard = false;
        if (protectionWork == null) protectionWork = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
