package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.AssetFinanceType;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.BorrowerType;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseSchedule;
import github.lms.lemuel.loan.domain.LeaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 리스·할부 계약 영속 엔티티.
 *
 * <p><b>회차표를 저장하지 않는다.</b> 스케줄은 산정 입력값(취득원가·선수금·보증금·잔존가치·기간·이율)의
 * 결정적 순수 함수({@link LeaseSchedule#of})라, 입력을 보존하면 같은 표가 언제든 재현된다. 표를 따로
 * 저장하면 <b>입력과 표라는 두 개의 진실원</b>이 생겨 어긋날 여지가 남고, 계약마다 회차 수십~수백 행이
 * 늘어난다. 대신 산정 입력값은 계약 시점 스냅샷으로 <b>사후 변경하지 않는다</b>(조건 변경은 재계약).
 *
 * <p>차주는 {@code secured_loans} 와 같은 이유로 평탄화 보관한다 — 신청 시점 스냅샷이라 정규화하면
 * 차주 정보 변경이 이미 체결된 계약의 근거를 흔든다.
 */
@Entity
@Table(name = "lease_contracts")
public class LeaseContractJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "borrower_type", nullable = false, length = 20)
    private BorrowerType borrowerType;

    @Column(name = "borrower_user_id", nullable = false)
    private Long borrowerUserId;

    @Column(name = "borrower_name", nullable = false)
    private String borrowerName;

    @Column(name = "borrower_registration_no", length = 10)
    private String borrowerRegistrationNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "finance_type", nullable = false, length = 20)
    private AssetFinanceType financeType;

    @Column(name = "asset_description", nullable = false)
    private String assetDescription;

    @Column(name = "acquisition_cost", nullable = false, precision = 19, scale = 2)
    private BigDecimal acquisitionCost;

    @Column(name = "down_payment", nullable = false, precision = 19, scale = 2)
    private BigDecimal downPayment;

    @Column(name = "deposit", nullable = false, precision = 19, scale = 2)
    private BigDecimal deposit;

    @Column(name = "residual_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal residualValue;

    @Column(name = "term_months", nullable = false)
    private Integer termMonths;

    @Column(name = "annual_rate_percent", nullable = false, precision = 9, scale = 4)
    private BigDecimal annualRatePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeaseStatus status;

    @Column(name = "paid_installments", nullable = false)
    private Integer paidInstallments;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LeaseContractJpaEntity() {
    }

    private LeaseContractJpaEntity(Long id, LeaseContract contract, OffsetDateTime createdAt) {
        LeaseSchedule schedule = contract.getSchedule();
        Borrower borrower = contract.getBorrower();
        this.id = id;
        this.borrowerType = borrower.type();
        this.borrowerUserId = borrower.userId();
        this.borrowerName = borrower.name();
        this.borrowerRegistrationNo = borrower.registrationNo();
        this.financeType = contract.getType();
        this.assetDescription = contract.getAssetDescription();
        this.acquisitionCost = schedule.acquisitionCost();
        this.downPayment = schedule.downPayment();
        this.deposit = schedule.deposit();
        this.residualValue = schedule.residualValue();
        this.termMonths = schedule.termMonths();
        this.annualRatePercent = schedule.annualRatePercent();
        this.status = contract.getStatus();
        this.paidInstallments = contract.getPaidInstallments();
        this.appliedAt = contract.getAppliedAt();
        this.activatedAt = contract.getActivatedAt();
        this.closedAt = contract.getClosedAt();
        this.createdAt = createdAt;
    }

    static LeaseContractJpaEntity from(LeaseContract contract, OffsetDateTime now) {
        return new LeaseContractJpaEntity(contract.getId(), contract, now);
    }

    /** 영속 → 도메인. 회차표는 저장된 입력값으로 <b>재산정</b>한다. */
    LeaseContract toDomain() {
        LeaseSchedule schedule = LeaseSchedule.of(financeType, acquisitionCost, downPayment, deposit,
                residualValue, termMonths, annualRatePercent);
        Borrower borrower = borrowerType == BorrowerType.CORPORATE
                ? Borrower.corporate(borrowerUserId, borrowerName, borrowerRegistrationNo)
                : Borrower.individual(borrowerUserId, borrowerName);
        return LeaseContract.reconstitute(id, borrower, financeType, assetDescription, schedule, status,
                paidInstallments, appliedAt, activatedAt, closedAt);
    }

    Long getId() {
        return id;
    }
}
