package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * 법인 카드계정 청구서(명세서) 애그리거트(순수 POJO — 프레임워크 의존 0).
 *
 * <p>카드계정({@link CardAccount})당 청구주기(YearMonth)별로 1개 생성된다.
 * 매입(capture) 건이 쌓이면 totalAmount 가 증가하고, 주기 마감 시 OPEN → CLOSED 로 전환된다.
 * 만기일(dueDate) 경과 + 미납이면 DELINQUENT 로 전이되어 연계 카드계정도 DELINQUENT 가 된다.
 *
 * <h3>상태머신</h3>
 * <pre>
 * OPEN → CLOSED → PARTIALLY_PAID → PAID
 *              ↘ DELINQUENT → PAID (연체 후 전액 납부)
 * </pre>
 */
public class CardStatement {

    private Long id;
    private final Long cardAccountId;
    private final YearMonth billingYearMonth;
    private StatementStatus status;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private LocalDate dueDate;
    private Instant closedAt;
    private long version;

    private CardStatement(Builder b) {
        this.id = b.id;
        this.cardAccountId = Objects.requireNonNull(b.cardAccountId, "cardAccountId");
        this.billingYearMonth = Objects.requireNonNull(b.billingYearMonth, "billingYearMonth");
        this.status = Objects.requireNonNull(b.status, "status");
        this.totalAmount = requireNonNegative(b.totalAmount != null ? b.totalAmount : BigDecimal.ZERO);
        this.paidAmount = requireNonNegative(b.paidAmount != null ? b.paidAmount : BigDecimal.ZERO);
        this.dueDate = b.dueDate;
        this.closedAt = b.closedAt;
        this.version = b.version;
    }

    /** 청구주기 OPEN 명세서 신규 생성. */
    public static CardStatement openFor(Long cardAccountId, YearMonth billingYearMonth,
                                        LocalDate dueDate) {
        return builder()
                .cardAccountId(cardAccountId)
                .billingYearMonth(billingYearMonth)
                .status(StatementStatus.OPEN)
                .totalAmount(BigDecimal.ZERO)
                .paidAmount(BigDecimal.ZERO)
                .dueDate(dueDate)
                .build();
    }

    /**
     * 매입 금액 추가 — OPEN 상태에서만 가능.
     *
     * <p>매입(capture) 이벤트 소비 또는 매입 확정 시 호출한다.
     */
    public void addCharge(BigDecimal amount) {
        requireNonNegative(amount);
        if (status != StatementStatus.OPEN) {
            throw new InvalidCardTransitionException(
                    "OPEN 상태의 명세서만 청구 금액을 추가할 수 있습니다. 현재=" + status);
        }
        this.totalAmount = this.totalAmount.add(amount);
    }

    /**
     * 청구 주기 마감 — OPEN → CLOSED.
     *
     * <p>배치 스케줄러({@code StatementBillingScheduler})가 청구 주기 종료 시 호출한다.
     */
    public void close() {
        transitionTo(StatementStatus.CLOSED);
        this.closedAt = Instant.now();
    }

    /**
     * 납부 적용 — CLOSED·PARTIALLY_PAID·DELINQUENT 에서 가능.
     *
     * <p>금액이 paidAmount 에 누적된다. 전액이 납부되면 PAID 로 전환,
     * 그렇지 않으면 PARTIALLY_PAID(또는 DELINQUENT 유지) 로 전환된다.
     *
     * @param amount 납부 금액(양수)
     * @return 납부 후 상태
     */
    public StatementStatus applyPayment(BigDecimal amount) {
        requirePositive(amount);
        if (status != StatementStatus.CLOSED
                && status != StatementStatus.PARTIALLY_PAID
                && status != StatementStatus.DELINQUENT) {
            throw new InvalidCardTransitionException(
                    "납부는 CLOSED·PARTIALLY_PAID·DELINQUENT 상태에서만 가능합니다. 현재=" + status);
        }
        this.paidAmount = this.paidAmount.add(amount);
        if (isFullyPaid()) {
            transitionTo(StatementStatus.PAID);
        } else if (status == StatementStatus.CLOSED) {
            transitionTo(StatementStatus.PARTIALLY_PAID);
        }
        // PARTIALLY_PAID 또는 DELINQUENT 에서 일부 납부는 상태 변경 없음
        return this.status;
    }

    /**
     * 연체 전이 — CLOSED·PARTIALLY_PAID → DELINQUENT.
     *
     * <p>만기일 경과 + 미납 시 배치({@code DelinquencyBatchScheduler})가 호출한다.
     * 연계 카드계정도 함께 DELINQUENT 로 전이해야 한다 — 호출자 책임.
     */
    public void markDelinquent() {
        transitionTo(StatementStatus.DELINQUENT);
    }

    /** 만기일 경과 + 미납 여부 — 배치가 연체 판단에 사용한다. */
    public boolean isOverdueAndUnpaid(LocalDate today) {
        return dueDate != null
                && today.isAfter(dueDate)
                && !isFullyPaid()
                && (status == StatementStatus.CLOSED || status == StatementStatus.PARTIALLY_PAID);
    }

    /** 전액 납부 완료 여부. totalAmount==0 이면 전액 납부로 간주하지 않는다(빈 명세서). */
    public boolean isFullyPaid() {
        return totalAmount.compareTo(BigDecimal.ZERO) > 0
                && paidAmount.compareTo(totalAmount) >= 0;
    }

    /** 미납 잔액. */
    public BigDecimal unpaidAmount() {
        BigDecimal unpaid = totalAmount.subtract(paidAmount);
        return unpaid.signum() < 0 ? BigDecimal.ZERO : unpaid;
    }

    // ── 상태 전이 가드 ──

    private void transitionTo(StatementStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidCardTransitionException(
                    "명세서 상태 전이 불가: " + status + " → " + target);
        }
        this.status = target;
    }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: " + value);
        }
        return value;
    }

    private static void requirePositive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("납부 금액은 양수여야 합니다: " + value);
        }
    }

    // ── getter ──

    public Long getId() { return id; }
    public Long getCardAccountId() { return cardAccountId; }
    public YearMonth getBillingYearMonth() { return billingYearMonth; }
    public StatementStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public LocalDate getDueDate() { return dueDate; }
    public Instant getClosedAt() { return closedAt; }
    public long getVersion() { return version; }

    public static Builder builder() {
        return new Builder();
    }

    /** 영속성 어댑터가 DB 에서 재구성하는 빌더. */
    public static class Builder {
        private Long id;
        private Long cardAccountId;
        private YearMonth billingYearMonth;
        private StatementStatus status;
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private LocalDate dueDate;
        private Instant closedAt;
        private long version;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder cardAccountId(Long v) { this.cardAccountId = v; return this; }
        public Builder billingYearMonth(YearMonth v) { this.billingYearMonth = v; return this; }
        public Builder status(StatementStatus v) { this.status = v; return this; }
        public Builder totalAmount(BigDecimal v) { this.totalAmount = v; return this; }
        public Builder paidAmount(BigDecimal v) { this.paidAmount = v; return this; }
        public Builder dueDate(LocalDate v) { this.dueDate = v; return this; }
        public Builder closedAt(Instant v) { this.closedAt = v; return this; }
        public Builder version(long v) { this.version = v; return this; }

        public CardStatement build() {
            return new CardStatement(this);
        }
    }
}
