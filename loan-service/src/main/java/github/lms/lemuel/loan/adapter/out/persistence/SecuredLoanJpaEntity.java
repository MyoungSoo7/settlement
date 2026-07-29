package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.BorrowerType;
import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 담보/개인신용 대출 영속 엔티티.
 *
 * <p>차주({@link Borrower} VO)는 <b>평탄화해</b> 보관한다 — 신청 시점 스냅샷이라 별도 테이블로 정규화하면
 * 차주 정보가 사후 변경될 때 이미 실행된 대출의 심사 근거가 흔들린다. 담보는 생명주기가 대출과 다르므로
 * 별도 테이블(FK)로 둔다.
 */
@Entity
@Table(name = "secured_loans")
public class SecuredLoanJpaEntity {

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
    @Column(name = "product_type", nullable = false, length = 20)
    private LoanProductType productType;

    @Column(name = "collateral_id")
    private Long collateralId;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "annual_rate_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal annualRatePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_method", nullable = false, length = 20)
    private RepaymentMethod repaymentMethod;

    @Column(name = "credit_score")
    private Integer creditScore;

    @Column(name = "credit_grade", length = 1)
    private String creditGrade;

    @Column(name = "outstanding", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstanding;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SecuredLoanStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected SecuredLoanJpaEntity() { }

    private SecuredLoanJpaEntity(Long id, Borrower borrower, LoanProductType productType, Long collateralId,
                                 BigDecimal principal, int termMonths, BigDecimal annualRatePercent,
                                 RepaymentMethod repaymentMethod, Integer creditScore, String creditGrade,
                                 BigDecimal outstanding, SecuredLoanStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.borrowerType = borrower.type();
        this.borrowerUserId = borrower.userId();
        this.borrowerName = borrower.name();
        this.borrowerRegistrationNo = borrower.registrationNo();
        this.productType = productType;
        this.collateralId = collateralId;
        this.principal = principal;
        this.termMonths = termMonths;
        this.annualRatePercent = annualRatePercent;
        this.repaymentMethod = repaymentMethod;
        this.creditScore = creditScore;
        this.creditGrade = creditGrade;
        this.outstanding = outstanding;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static SecuredLoanJpaEntity from(SecuredLoan loan) {
        Long collateralId = loan.getCollateral() == null ? null : loan.getCollateral().getId();
        return new SecuredLoanJpaEntity(loan.getId(), loan.getBorrower(), loan.getProductType(), collateralId,
                loan.getPrincipal(), loan.getTermMonths(), loan.getAnnualRatePercent(),
                loan.getRepaymentMethod(), loan.getCreditScore(), loan.getCreditGrade(),
                loan.getOutstanding(), loan.getStatus(), loan.getCreatedAt());
    }

    /**
     * 도메인으로 환원한다. 담보는 별도 테이블이라 어댑터가 조회해 주입한다
     * (신용형은 {@code null}).
     */
    public SecuredLoan toDomain(Collateral collateral) {
        Borrower borrower = new Borrower(borrowerType, borrowerUserId, borrowerName, borrowerRegistrationNo);
        return SecuredLoan.reconstitute(id, borrower, productType, collateral, principal, termMonths,
                annualRatePercent, repaymentMethod, creditScore, creditGrade, outstanding, status, createdAt);
    }

    public Long getId() { return id; }
    public Long getCollateralId() { return collateralId; }
}
