package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 지출보고서 도메인 모델 (순수 POJO — 프레임워크 의존 0).
 *
 * <h3>생명주기</h3>
 * <pre>
 * DRAFT ──submit──▶ SUBMITTED ──approve──▶ APPROVED
 *                       │
 *                    reject
 *                       │
 *                       ▼
 *                   REJECTED ──submit──▶ SUBMITTED (재제출 가능)
 * </pre>
 *
 * <h3>승인 경로 비결합</h3>
 * <ul>
 *   <li>이 클래스는 {@link AuthorizationHold} 를 직접 참조하지 않는다 —
 *       {@code captureId} / {@code authorizationId} 를 문자열로만 보관.</li>
 *   <li>{@link DeclineReason} 에 {@code PENDING_APPROVAL} 을 추가하지 않는다.</li>
 * </ul>
 *
 * <p>금액은 {@link BigDecimal} 강제. 이벤트에선 {@code toPlainString()} 으로 직렬화(DATA-STANDARD N5).
 */
public class ExpenseReport {

    private Long id;
    private final String reportId;           // 자연키
    private final String captureId;          // 매입 참조 (CardCapture.captureId)
    private final String authorizationId;    // 승인 참조 (문자열 — AuthorizationHold 직접 참조 금지)
    private final Long cardId;
    private final Long cardAccountId;
    private final Long organizationId;
    private final String departmentId;       // 부서 ID (예산 소진율 집계 기준)
    private final Long holderUserId;
    private final BigDecimal amount;
    private final String merchantName;

    // 가변 상태 — 워크플로 전이
    private ExpenseReportStatus status;
    private String expenseCategory;          // 경비 카테고리
    private String receiptUrl;               // 영수증 URL
    private String memo;
    private Long reviewedBy;                 // 승인/반려자 userId
    private String rejectReason;
    private final Instant capturedAt;
    private Instant submittedAt;
    private Instant reviewedAt;

    private ExpenseReport(Builder b) {
        this.id = b.id;
        this.reportId = Objects.requireNonNull(b.reportId, "reportId");
        this.captureId = Objects.requireNonNull(b.captureId, "captureId");
        this.authorizationId = Objects.requireNonNull(b.authorizationId, "authorizationId");
        this.cardId = Objects.requireNonNull(b.cardId, "cardId");
        this.cardAccountId = Objects.requireNonNull(b.cardAccountId, "cardAccountId");
        this.organizationId = Objects.requireNonNull(b.organizationId, "organizationId");
        this.departmentId = b.departmentId;
        this.holderUserId = Objects.requireNonNull(b.holderUserId, "holderUserId");
        this.amount = requirePositive(b.amount);
        this.merchantName = b.merchantName;
        this.status = Objects.requireNonNull(b.status, "status");
        this.expenseCategory = b.expenseCategory;
        this.receiptUrl = b.receiptUrl;
        this.memo = b.memo;
        this.reviewedBy = b.reviewedBy;
        this.rejectReason = b.rejectReason;
        this.capturedAt = Objects.requireNonNull(b.capturedAt, "capturedAt");
        this.submittedAt = b.submittedAt;
        this.reviewedAt = b.reviewedAt;
    }

    /**
     * 매입 확정 이벤트로부터 DRAFT 지출보고서 생성 팩토리.
     */
    public static ExpenseReport createFromCapture(
            String reportId, String captureId, String authorizationId,
            Long cardId, Long cardAccountId, Long organizationId, String departmentId,
            Long holderUserId, BigDecimal amount, String merchantName, Instant capturedAt) {
        return builder()
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
                .status(ExpenseReportStatus.DRAFT)
                .capturedAt(capturedAt)
                .build();
    }

    /**
     * 영수증·카테고리·메모를 첨부하고 SUBMITTED 로 전이한다.
     *
     * <p>{@code DRAFT} 또는 {@code REJECTED} 상태에서만 호출 가능.
     *
     * @throws IllegalStateException 전이 불가 상태
     */
    public void submit(String receiptUrl, String expenseCategory, String memo) {
        if (!status.canSubmit()) {
            throw new IllegalStateException(
                    "제출 전이 불가: 현재 상태=" + status + " (DRAFT 또는 REJECTED 이어야 합니다)");
        }
        this.receiptUrl = receiptUrl;
        this.expenseCategory = expenseCategory;
        this.memo = memo;
        this.status = ExpenseReportStatus.SUBMITTED;
        this.submittedAt = Instant.now();
        // 재제출 시 이전 반려 사유 초기화
        this.rejectReason = null;
        this.reviewedBy = null;
        this.reviewedAt = null;
    }

    /**
     * 관리자 승인 — SUBMITTED → APPROVED.
     *
     * @throws IllegalStateException 전이 불가 상태
     */
    public void approve(Long reviewerId) {
        if (!status.canApprove()) {
            throw new IllegalStateException(
                    "승인 전이 불가: 현재 상태=" + status + " (SUBMITTED 이어야 합니다)");
        }
        this.reviewedBy = Objects.requireNonNull(reviewerId, "reviewerId");
        this.reviewedAt = Instant.now();
        this.status = ExpenseReportStatus.APPROVED;
    }

    /**
     * 관리자 반려 — SUBMITTED → REJECTED. 임직원은 수정 후 재제출 가능.
     *
     * @throws IllegalStateException 전이 불가 상태
     */
    public void reject(Long reviewerId, String reason) {
        if (!status.canReject()) {
            throw new IllegalStateException(
                    "반려 전이 불가: 현재 상태=" + status + " (SUBMITTED 이어야 합니다)");
        }
        this.reviewedBy = Objects.requireNonNull(reviewerId, "reviewerId");
        this.reviewedAt = Instant.now();
        this.rejectReason = reason;
        this.status = ExpenseReportStatus.REJECTED;
    }

    private static BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("지출보고서 금액은 양수여야 합니다: " + value);
        }
        return value;
    }

    // ── getters ──

    public Long getId() { return id; }
    public String getReportId() { return reportId; }
    public String getCaptureId() { return captureId; }
    public String getAuthorizationId() { return authorizationId; }
    public Long getCardId() { return cardId; }
    public Long getCardAccountId() { return cardAccountId; }
    public Long getOrganizationId() { return organizationId; }
    public String getDepartmentId() { return departmentId; }
    public Long getHolderUserId() { return holderUserId; }
    public BigDecimal getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }
    public ExpenseReportStatus getStatus() { return status; }
    public String getExpenseCategory() { return expenseCategory; }
    public String getReceiptUrl() { return receiptUrl; }
    public String getMemo() { return memo; }
    public Long getReviewedBy() { return reviewedBy; }
    public String getRejectReason() { return rejectReason; }
    public Instant getCapturedAt() { return capturedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }

    public static Builder builder() { return new Builder(); }

    /** 영속 계층 재구성 전용 빌더 */
    public static class Builder {
        private Long id;
        private String reportId;
        private String captureId;
        private String authorizationId;
        private Long cardId;
        private Long cardAccountId;
        private Long organizationId;
        private String departmentId;
        private Long holderUserId;
        private BigDecimal amount;
        private String merchantName;
        private ExpenseReportStatus status;
        private String expenseCategory;
        private String receiptUrl;
        private String memo;
        private Long reviewedBy;
        private String rejectReason;
        private Instant capturedAt;
        private Instant submittedAt;
        private Instant reviewedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder reportId(String v) { this.reportId = v; return this; }
        public Builder captureId(String v) { this.captureId = v; return this; }
        public Builder authorizationId(String v) { this.authorizationId = v; return this; }
        public Builder cardId(Long v) { this.cardId = v; return this; }
        public Builder cardAccountId(Long v) { this.cardAccountId = v; return this; }
        public Builder organizationId(Long v) { this.organizationId = v; return this; }
        public Builder departmentId(String v) { this.departmentId = v; return this; }
        public Builder holderUserId(Long v) { this.holderUserId = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder merchantName(String v) { this.merchantName = v; return this; }
        public Builder status(ExpenseReportStatus v) { this.status = v; return this; }
        public Builder expenseCategory(String v) { this.expenseCategory = v; return this; }
        public Builder receiptUrl(String v) { this.receiptUrl = v; return this; }
        public Builder memo(String v) { this.memo = v; return this; }
        public Builder reviewedBy(Long v) { this.reviewedBy = v; return this; }
        public Builder rejectReason(String v) { this.rejectReason = v; return this; }
        public Builder capturedAt(Instant v) { this.capturedAt = v; return this; }
        public Builder submittedAt(Instant v) { this.submittedAt = v; return this; }
        public Builder reviewedAt(Instant v) { this.reviewedAt = v; return this; }
        public ExpenseReport build() { return new ExpenseReport(this); }
    }
}
