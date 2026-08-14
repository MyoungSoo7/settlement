package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.CommissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * commission_schedules 테이블 매핑 (V1).
 *
 * <p><b>paid_at 매핑</b>: DB 는 TIMESTAMPTZ, 도메인은 지급 "일자"만 관심( {@code LocalDate} ).
 * KST 자정 기준으로 왕복 변환한다 — 지급 배치의 날짜 경계는 한국 영업일이다.
 */
@Entity
@Table(name = "commission_schedules", schema = "opslab")
public class CommissionScheduleJpaEntity {

    /** 지급 일자 ↔ TIMESTAMPTZ 변환 기준 시간대 — 스케줄러 cron zone 과 동일 출처. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "commission_id", nullable = false)
    private UUID commissionId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "fc_id", nullable = false, length = 64)
    private String fcId;

    @Column(name = "recipient_type", length = 20)
    private String recipientType;

    @Column(name = "parent_commission_id")
    private UUID parentCommissionId;

    @Column(name = "installment_no", nullable = false)
    private int installmentNo;

    @Column(name = "installment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal installmentAmount;

    @Column(name = "first_year_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal firstYearTotal;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "paid_amount", precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CommissionStatus status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CommissionScheduleJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 신규 회차 INSERT 용 — 팩토리가 만든 도메인 스냅샷을 행으로 변환. */
    public static CommissionScheduleJpaEntity fromDomain(CommissionSchedule s) {
        CommissionScheduleJpaEntity e = new CommissionScheduleJpaEntity();
        e.commissionId = UUID.fromString(s.getCommissionId());
        e.policyId = UUID.fromString(s.getPolicyId());
        e.fcId = s.getFcId();
        e.recipientType = s.getRecipientType();
        e.parentCommissionId = s.getParentCommissionId() != null
                ? UUID.fromString(s.getParentCommissionId()) : null;
        e.installmentNo = s.getInstallmentNo();
        e.installmentAmount = s.getInstallmentAmount();
        e.firstYearTotal = s.getFirstYearTotal();
        e.dueDate = s.getDueDate();
        e.paidAt = toInstant(s.getPaidAt());
        e.paidAmount = s.getPaidAmount();
        e.status = s.getStatus();
        return e;
    }

    /** DB 행 → 도메인 재구성. 전이 로직은 도메인이 강제한다. */
    public CommissionSchedule toDomain() {
        return CommissionSchedule.builder()
                .id(id)
                .commissionId(commissionId.toString())
                .policyId(policyId.toString())
                .fcId(fcId)
                .recipientType(recipientType)
                .parentCommissionId(parentCommissionId != null ? parentCommissionId.toString() : null)
                .installmentNo(installmentNo)
                .installmentAmount(installmentAmount)
                .firstYearTotal(firstYearTotal)
                .dueDate(dueDate)
                .paidAt(toLocalDate(paidAt))
                .paidAmount(paidAmount)
                .status(status)
                .build();
    }

    /**
     * 도메인 전이 결과 반영 — 전이로 변할 수 있는 3개 필드만.
     * 식별자·금액·회차 컬럼은 불변이므로 덮어쓰지 않는다.
     */
    public void applyTransitionResult(CommissionSchedule s) {
        this.status = s.getStatus();
        this.paidAt = toInstant(s.getPaidAt());
        this.paidAmount = s.getPaidAmount();
    }

    private static Instant toInstant(LocalDate date) {
        return date != null ? date.atStartOfDay(KST).toInstant() : null;
    }

    private static LocalDate toLocalDate(Instant instant) {
        return instant != null ? instant.atZone(KST).toLocalDate() : null;
    }

    public Long getId() {
        return id;
    }

    public UUID getCommissionId() {
        return commissionId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public String getFcId() {
        return fcId;
    }

    public CommissionStatus getStatus() {
        return status;
    }
}
