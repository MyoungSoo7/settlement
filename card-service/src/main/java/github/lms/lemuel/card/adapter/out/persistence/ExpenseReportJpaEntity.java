package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.ExpenseReport;
import github.lms.lemuel.card.domain.ExpenseReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * expense_reports 테이블 매핑 (V9).
 *
 * <p>report_id 가 자연키, capture_id 가 멱등 키다.
 */
@Entity
@Table(name = "expense_reports")
public class ExpenseReportJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false, length = 64)
    private String reportId;

    @Column(name = "capture_id", nullable = false, length = 64)
    private String captureId;

    @Column(name = "authorization_id", nullable = false, length = 64)
    private String authorizationId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "card_account_id", nullable = false)
    private Long cardAccountId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "department_id", length = 64)
    private String departmentId;

    @Column(name = "holder_user_id", nullable = false)
    private Long holderUserId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "merchant_name", length = 200)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExpenseReportStatus status;

    @Column(name = "expense_category", length = 64)
    private String expenseCategory;

    @Column(name = "receipt_url", length = 500)
    private String receiptUrl;

    @Column(name = "memo")
    private String memo;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ExpenseReportJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static ExpenseReportJpaEntity fromDomain(ExpenseReport r) {
        ExpenseReportJpaEntity e = new ExpenseReportJpaEntity();
        e.id = r.getId();
        e.reportId = r.getReportId();
        e.captureId = r.getCaptureId();
        e.authorizationId = r.getAuthorizationId();
        e.cardId = r.getCardId();
        e.cardAccountId = r.getCardAccountId();
        e.organizationId = r.getOrganizationId();
        e.departmentId = r.getDepartmentId();
        e.holderUserId = r.getHolderUserId();
        e.amount = r.getAmount();
        e.merchantName = r.getMerchantName();
        e.status = r.getStatus();
        e.expenseCategory = r.getExpenseCategory();
        e.receiptUrl = r.getReceiptUrl();
        e.memo = r.getMemo();
        e.reviewedBy = r.getReviewedBy();
        e.rejectReason = r.getRejectReason();
        e.capturedAt = r.getCapturedAt();
        e.submittedAt = r.getSubmittedAt();
        e.reviewedAt = r.getReviewedAt();
        e.updatedAt = Instant.now();
        return e;
    }

    public ExpenseReport toDomain() {
        return ExpenseReport.builder()
                .id(id)
                .reportId(reportId)
                .captureId(captureId)
                .authorizationId(authorizationId)
                .cardId(cardId)
                .cardAccountId(cardAccountId)
                .organizationId(organizationId)
                .departmentId(departmentId)
                .holderUserId(holderUserId)
                .amount(amount)
                .merchantName(merchantName)
                .status(status)
                .expenseCategory(expenseCategory)
                .receiptUrl(receiptUrl)
                .memo(memo)
                .reviewedBy(reviewedBy)
                .rejectReason(rejectReason)
                .capturedAt(capturedAt)
                .submittedAt(submittedAt)
                .reviewedAt(reviewedAt)
                .build();
    }
}
