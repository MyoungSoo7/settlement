package github.lms.lemuel.settlement.domain;

import github.lms.lemuel.common.money.Money;
import github.lms.lemuel.settlement.domain.exception.InvalidSettlementStateException;
import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 Aggregate Root.
 *
 * <p>정산 1건의 생명주기(상태 머신)·금액 계산·보류(holdback)·환불 반영에 대한 불변식을 캡슐화한다.
 * 외부(애플리케이션/어댑터)는 이 루트를 통해서만 정산 상태를 변경해야 하며, 종료 상태(DONE)는
 * 불변이라 금액 변경 대신 {@link SettlementAdjustment} 로만 보정한다.
 *
 * <p>public setter 는 두지 않는다. 상태 전이는 가드된 도메인 메서드(startProcessing/complete/fail 등)로만,
 * 영속 레코드 복원은 {@link #rehydrate} 팩토리로만, DB 부여 PK 주입은 {@link #assignId} 로만 수행한다.
 *
 * <p>모든 통화 금액 계산은 {@link Money} 값 객체를 통과시켜 반올림(scale 2, HALF_UP)·부호 규칙을
 * 한곳에 모은다. public getter 는 영속성 경계 호환을 위해 {@link BigDecimal} 표현을 유지한다.
 */
public class Settlement {

    /**
     * 레거시 기본 수수료율 (3%). 차등 수수료 전환 전 생성된 정산은 이 값으로 계산.
     * 신규 경로는 {@link SellerTier} 별 rate 를 받아 사용한다.
     */
    public static final BigDecimal COMMISSION_RATE = new BigDecimal("0.03");

    private Long id;
    private final Long paymentId;
    private final Long orderId;
    private final BigDecimal paymentAmount;     // 원 결제 금액
    private BigDecimal refundedAmount;    // 환불 금액
    private BigDecimal commission;        // 수수료
    private final BigDecimal commissionRate;    // 적용된 수수료율 (이력 보존, 정산 시점 스냅샷)
    private BigDecimal netAmount;         // 실 지급액
    private SettlementStatus status;
    private final LocalDate settlementDate;
    private String failureReason;         // 실패 사유
    private LocalDateTime confirmedAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;                 // 낙관적 락 버전 (JPA @Version)

    /** 정산금 중 보류된 금액. 즉시 지급액 = netAmount - holdbackAmount. */
    private BigDecimal holdbackAmount = BigDecimal.ZERO;
    /** 정산 시점 적용된 보류율 (이력 보존). */
    private BigDecimal holdbackRate = BigDecimal.ZERO;
    /** 보류 해제 예정일. 이 날짜 이후 배치가 자동 release. */
    private LocalDate holdbackReleaseDate;
    /** 보류 해제 여부. */
    private boolean holdbackReleased = false;
    private LocalDateTime holdbackReleasedAt;

    /**
     * 정본 생성자 — 신규/복원 팩토리({@link #createFromPayment}·{@link #rehydrate})만 통과한다
     * (Payout/Chargeback 과 동형: 생성자 비공개, 팩토리 공개). 불변 식별·스냅샷 필드
     * (paymentId·orderId·paymentAmount·commissionRate·settlementDate·createdAt)를 여기서 못박아
     * 이후 어떤 경로로도 재할당할 수 없게 한다.
     *
     * <p>null 폴백 정규화(refundedAmount·status·commissionRate·holdback·createdAt/updatedAt)를
     * 생성 시점 단일 지점으로 모은다 — getter 는 정규화된 값을 그대로 반환한다.
     * {@code commissionRate == null}(차등 수수료 전환 전 레거시 행)은 {@link #COMMISSION_RATE}(기본율)로
     * 정규화해 기존 getter 폴백 의미를 보존한다.
     */
    private Settlement(Long id, Long paymentId, Long orderId, BigDecimal paymentAmount,
                       BigDecimal refundedAmount, BigDecimal commission, BigDecimal commissionRate,
                       BigDecimal netAmount, SettlementStatus status, LocalDate settlementDate,
                       String failureReason, LocalDateTime confirmedAt, LocalDateTime createdAt,
                       LocalDateTime updatedAt, Long version, BigDecimal holdbackAmount,
                       BigDecimal holdbackRate, LocalDate holdbackReleaseDate, boolean holdbackReleased,
                       LocalDateTime holdbackReleasedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.paymentAmount = paymentAmount;
        this.refundedAmount = refundedAmount != null ? refundedAmount : BigDecimal.ZERO;
        this.commission = commission;
        this.commissionRate = commissionRate != null ? commissionRate : COMMISSION_RATE;
        this.netAmount = netAmount;
        this.status = status != null ? status : SettlementStatus.REQUESTED;
        this.settlementDate = settlementDate;
        this.failureReason = failureReason;
        this.confirmedAt = confirmedAt;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.updatedAt = updatedAt != null ? updatedAt : LocalDateTime.now();
        this.version = version;
        this.holdbackAmount = holdbackAmount != null ? holdbackAmount : BigDecimal.ZERO;
        this.holdbackRate = holdbackRate != null ? holdbackRate : BigDecimal.ZERO;
        this.holdbackReleaseDate = holdbackReleaseDate;
        this.holdbackReleased = holdbackReleased;
        this.holdbackReleasedAt = holdbackReleasedAt;
    }

    public static Settlement createFromPayment(Long paymentId, Long orderId,
                                               BigDecimal paymentAmount, LocalDate settlementDate) {
        return createFromPayment(paymentId, orderId, paymentAmount, settlementDate, COMMISSION_RATE);
    }

    /**
     * 차등 수수료 지원 팩토리.
     * @param commissionRate 적용할 수수료율 (예: {@link SellerTier#rate()}). null 이면 {@link #COMMISSION_RATE}.
     */
    public static Settlement createFromPayment(Long paymentId, Long orderId,
                                               BigDecimal paymentAmount, LocalDate settlementDate,
                                               BigDecimal commissionRate) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = new Settlement(null, paymentId, orderId, paymentAmount,
                BigDecimal.ZERO, null, commissionRate, null, SettlementStatus.REQUESTED, settlementDate,
                null, null, now, now, null, BigDecimal.ZERO, BigDecimal.ZERO, null, false, null);

        settlement.validatePaymentId();
        settlement.validateAmount();
        settlement.validateSettlementDate();

        settlement.calculateCommissionAndNetAmount();

        return settlement;
    }

    /**
     * 영속 레코드 복원 전용(MapStruct 매퍼의 toDomain 에서만 호출). 저장된 필드를 그대로 재구성한다.
     *
     * <p>{@code commissionRate} 는 정산 시점 스냅샷(V32 이력 보존 원칙)이라 write-once 여야 한다 —
     * {@code final} 필드 + private 생성 경로 + setter 부재로 재부여 자체가 컴파일 단에서 불가능하다.
     */
    public static Settlement rehydrate(Long id, Long paymentId, Long orderId,
                                       BigDecimal paymentAmount, BigDecimal refundedAmount,
                                       BigDecimal commission, BigDecimal commissionRate,
                                       BigDecimal netAmount, SettlementStatus status,
                                       LocalDate settlementDate, String failureReason,
                                       LocalDateTime confirmedAt, LocalDateTime createdAt,
                                       LocalDateTime updatedAt, Long version,
                                       BigDecimal holdbackAmount, BigDecimal holdbackRate,
                                       LocalDate holdbackReleaseDate, boolean holdbackReleased,
                                       LocalDateTime holdbackReleasedAt) {
        return new Settlement(id, paymentId, orderId, paymentAmount, refundedAmount, commission,
                commissionRate, netAmount, status, settlementDate, failureReason, confirmedAt,
                createdAt, updatedAt, version, holdbackAmount, holdbackRate, holdbackReleaseDate,
                holdbackReleased, holdbackReleasedAt);
    }

    private void calculateCommissionAndNetAmount() {
        // commissionRate 는 생성자에서 non-null 로 정규화된다.
        Money payment = Money.of(paymentAmount);
        Money commissionMoney = payment.times(commissionRate);
        this.commission = commissionMoney.toBigDecimal();
        this.netAmount = payment.minus(commissionMoney).toBigDecimal();
    }

    private void validatePaymentId() {
        if (paymentId == null || paymentId <= 0) {
            throw new SettlementInvariantViolationException("Payment ID must be a positive number");
        }
    }

    private void validateAmount() {
        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SettlementInvariantViolationException("Amount must be greater than zero");
        }
    }

    private void validateSettlementDate() {
        if (settlementDate == null) {
            throw new SettlementInvariantViolationException("Settlement date is required");
        }
    }

    /**
     * 영속 후 DB 가 부여한 PK 를 1회만 주입(write-once). setter 우회를 막기 위해 재부여를 차단한다.
     */
    public void assignId(Long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 는 1회만 부여할 수 있습니다");
        }
        this.id = id;
    }

    // ========== 상태 머신 메서드 ==========

    /**
     * 정산 처리 시작 — 허용 전이는 {@link SettlementStatus#canTransitionTo} 단일 출처에 위임한다(cancel 과 동형).
     * REQUESTED → PROCESSING
     */
    public void startProcessing() {
        if (!this.status.canTransitionTo(SettlementStatus.PROCESSING)) {
            throw new InvalidSettlementStateException(this.status, SettlementStatus.PROCESSING);
        }
        this.status = SettlementStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 정산 완료 — 허용 전이는 {@link SettlementStatus#canTransitionTo} 단일 출처에 위임한다(cancel 과 동형).
     * PROCESSING → DONE
     */
    public void complete() {
        if (!this.status.canTransitionTo(SettlementStatus.DONE)) {
            throw new InvalidSettlementStateException(this.status, SettlementStatus.DONE);
        }
        this.status = SettlementStatus.DONE;
        this.confirmedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 정산 실패 — 허용 전이는 {@link SettlementStatus#canTransitionTo} 단일 출처에 위임한다(cancel 과 동형).
     * PROCESSING → FAILED
     */
    public void fail(String reason) {
        if (!this.status.canTransitionTo(SettlementStatus.FAILED)) {
            throw new InvalidSettlementStateException(this.status, SettlementStatus.FAILED);
        }
        this.status = SettlementStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 재시도 (실패한 정산을 다시 요청 상태로) — 허용 전이는 {@link SettlementStatus#canTransitionTo} 단일 출처에
     * 위임한다(cancel 과 동형).
     * FAILED → REQUESTED
     */
    public void retry() {
        if (!this.status.canTransitionTo(SettlementStatus.REQUESTED)) {
            throw new InvalidSettlementStateException(this.status, SettlementStatus.REQUESTED);
        }
        this.status = SettlementStatus.REQUESTED;
        this.failureReason = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 정산 확정 — 정식 상태 머신을 우회하지 않고 REQUESTED → PROCESSING → DONE 를 한 번에 수행한다.
     * 레거시 호출부 호환을 위해 제공되며, 신규 코드는 startProcessing()/complete() 를 직접 호출할 것.
     */
    public void confirm() {
        if (this.status == SettlementStatus.REQUESTED) {
            startProcessing();
        }
        if (this.status != SettlementStatus.PROCESSING) {
            throw new InvalidSettlementStateException(this.status, "Cannot confirm — expected REQUESTED or PROCESSING");
        }
        complete();
    }

    /**
     * 정산 취소 — 허용 전이는 {@link SettlementStatus#canTransitionTo} 단일 출처를 위임한다.
     * 종료 상태(DONE·CANCELED)는 전이표가 차단하므로 별도 인라인 가드를 두지 않는다.
     */
    public void cancel() {
        if (!this.status.canTransitionTo(SettlementStatus.CANCELED)) {
            throw new InvalidSettlementStateException(this.status, SettlementStatus.CANCELED);
        }
        this.status = SettlementStatus.CANCELED;
        this.updatedAt = LocalDateTime.now();
    }

    // ========== 환불 처리 ==========

    /**
     * 환불 반영 (정산 금액 조정)
     * @param refundAmount 환불 금액
     */
    public void adjustForRefund(BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SettlementInvariantViolationException("Refund amount must be greater than zero");
        }

        // DONE 상태는 이미 판매자 지급 완료된 정산 → 금액 직접 변경 금지.
        // 환불은 SettlementAdjustment 별도 레코드로만 기록해야 원장 정합성 유지됨.
        if (this.status == SettlementStatus.DONE) {
            throw new InvalidSettlementStateException(this.status,
                "DONE settlement is immutable. Use SettlementAdjustment to record the refund offset.");
        }

        if (this.refundedAmount == null) {
            this.refundedAmount = BigDecimal.ZERO;
        }

        BigDecimal newRefunded = this.refundedAmount.add(refundAmount);
        if (newRefunded.compareTo(this.paymentAmount) > 0) {
            throw new SettlementInvariantViolationException(
                "Cumulative refund " + newRefunded + " exceeds payment amount " + this.paymentAmount);
        }
        this.refundedAmount = newRefunded;

        // 순 정산 금액 재계산: (결제금액 - 환불금액 - 수수료)
        Money net = Money.of(this.paymentAmount)
                .minus(Money.of(this.refundedAmount))
                .minus(Money.of(this.commission));
        this.netAmount = net.toBigDecimal();

        // 환불로 인해 정산 금액이 0 이하가 되면 취소 처리
        if (net.isZeroOrNegative()) {
            this.status = SettlementStatus.CANCELED;
        }

        this.updatedAt = LocalDateTime.now();
    }

    /**
     * PG 대사 승인에 따른 역정산(clawback) 반영 — 셀러가 과다 정산받은 금액을 정산금에서 회수한다.
     *
     * <p>{@link #adjustForRefund}와 달리 {@code refundedAmount} running total 은 절대 건드리지 않는다.
     * 대사 clawback 을 환불 누적치에 섞으면 실제 환불과 이중 계상되거나, 이후 환불 delta 복원 로직
     * ({@code cumulative − settled})이 오작동한다. 따라서 net 만 clawback 만큼 축소한다.
     *
     * <p>DONE 정산은 이미 지급 완료되어 불변 — {@link #adjustForRefund}와 동일하게 예외를 던지고,
     * 호출자(서비스)가 {@link SettlementAdjustment} 감사 레코드만 남겨 수기 회수로 이관한다.
     *
     * <p><b>Scope 경계</b>: 원장 역분개는 후속 과제다. 기존 {@code enqueueReverse}는 refundId 키 기반이라
     * 대사에 맞지 않고, chargeback 경로와 동일하게 이 단계에서는 원장을 건드리지 않는다.
     *
     * @param clawbackAmount 회수 금액 (양수)
     */
    public void applyReconciliationClawback(BigDecimal clawbackAmount) {
        if (clawbackAmount == null || clawbackAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new SettlementInvariantViolationException("Reconciliation clawback amount must be greater than zero");
        }

        // DONE 상태는 지급 완료된 정산 → 금액 직접 변경 금지 (adjustForRefund 와 동일한 불변식).
        if (this.status == SettlementStatus.DONE) {
            throw new InvalidSettlementStateException(this.status,
                "DONE settlement is immutable. Use SettlementAdjustment to record the reconciliation clawback offset.");
        }

        // net 만 clawback 만큼 축소 — refundedAmount 는 건드리지 않는다.
        Money net = Money.of(this.netAmount).minus(Money.of(clawbackAmount));
        this.netAmount = net.toBigDecimal();

        // clawback 으로 정산 금액이 0 이하가 되면 취소 처리 (adjustForRefund 규칙 미러링).
        if (net.isZeroOrNegative()) {
            this.status = SettlementStatus.CANCELED;
        }

        this.updatedAt = LocalDateTime.now();
    }

    // ========== 상태 확인 메서드 ==========

    public boolean isConfirmed() {
        return this.status == SettlementStatus.DONE;
    }

    public boolean isPending() {
        return this.status == SettlementStatus.REQUESTED;
    }

    public boolean canRetry() {
        return this.status == SettlementStatus.FAILED;
    }

    public boolean isProcessing() {
        return this.status == SettlementStatus.PROCESSING;
    }

    public boolean isDone() {
        return this.status == SettlementStatus.DONE;
    }

    // ========== Getters ==========

    public Long getId() { return id; }

    public Long getPaymentId() { return paymentId; }

    public Long getOrderId() { return orderId; }

    public BigDecimal getPaymentAmount() { return paymentAmount; }

    public BigDecimal getRefundedAmount() { return refundedAmount; }

    public BigDecimal getCommission() { return commission; }

    public BigDecimal getCommissionRate() { return commissionRate; }

    public BigDecimal getNetAmount() { return netAmount; }

    public SettlementStatus getStatus() { return status; }

    public LocalDate getSettlementDate() { return settlementDate; }

    public String getFailureReason() { return failureReason; }

    public LocalDateTime getConfirmedAt() { return confirmedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public Long getVersion() { return version; }

    // ========== 정산 보류 (Holdback) ==========

    /**
     * 보류 정책 적용. 정산 생성 직후 호출 (또는 createFromPayment 안에서 자동).
     *
     * @param rate         보류율 (예: 0.30 = 30%)
     * @param releaseDate  보류 해제 예정일 (settlementDate + N 영업일)
     */
    public void applyHoldback(BigDecimal rate, LocalDate releaseDate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new SettlementInvariantViolationException("보류율은 0 ~ 1 사이여야 합니다: " + rate);
        }
        if (this.netAmount == null) {
            throw new InvalidSettlementStateException("netAmount 계산 후에만 holdback 적용 가능");
        }
        this.holdbackRate = rate;
        this.holdbackAmount = Money.of(this.netAmount).times(rate).toBigDecimal();
        this.holdbackReleaseDate = releaseDate;
        this.holdbackReleased = rate.signum() == 0; // 0% 면 보류 없으므로 즉시 released
        if (this.holdbackReleased) {
            this.holdbackReleasedAt = LocalDateTime.now();
        }
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 보류 해제 — release_date 도달 후 배치가 호출. 이미 해제됐거나 환불로 소진된 경우 무시.
     */
    public void releaseHoldback(LocalDate today) {
        if (this.holdbackReleased) return;
        if (this.holdbackReleaseDate == null) return;
        if (today.isBefore(this.holdbackReleaseDate)) {
            throw new InvalidSettlementStateException(
                    "아직 release 시점이 아닙니다. releaseDate=" + holdbackReleaseDate + ", today=" + today);
        }
        this.holdbackReleased = true;
        this.holdbackReleasedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 환불·회수 시 holdback 에서 우선 차감. holdback 잔액으로 충분하면 셀러 net 에는 영향 없음 —
     * 신뢰도 낮은 셀러를 위한 안전장치 효과.
     *
     * <p><b>DONE 정산에서도 허용되는 의도된 예외</b>(형제 조정 메서드들과 달리 DONE 가드 없음):
     * holdback 은 아직 지급되지 않은 유보금이라, 지급후 회수(seed-p0-6)가 이를 흡수하는 것은
     * 확정된 net·즉시지급분을 건드리지 않는다. net 을 바꾸는 조정은 여전히 DONE 에서 거부된다.
     *
     * @return 실제 holdback 에서 차감된 금액 (나머지는 호출자가 일반 환불/채권 흐름으로 처리)
     */
    public BigDecimal consumeHoldbackForRefund(BigDecimal refundAmount) {
        if (refundAmount == null || refundAmount.signum() <= 0) {
            throw new SettlementInvariantViolationException("refundAmount 양수 필수");
        }
        if (this.holdbackAmount.signum() <= 0 || this.holdbackReleased) {
            return BigDecimal.ZERO;
        }
        Money consumed = Money.of(this.holdbackAmount).min(Money.of(refundAmount));
        this.holdbackAmount = Money.of(this.holdbackAmount).minus(consumed).toBigDecimal();
        if (this.holdbackAmount.signum() == 0) {
            this.holdbackReleased = true;
            this.holdbackReleasedAt = LocalDateTime.now();
        }
        this.updatedAt = LocalDateTime.now();
        return consumed.toBigDecimal();
    }

    /**
     * 셀러에게 즉시 지급 가능한 금액 (보류분 제외).
     */
    public BigDecimal getImmediatePayoutAmount() {
        if (this.netAmount == null) return BigDecimal.ZERO;
        if (this.holdbackReleased) return this.netAmount;
        return Money.of(this.netAmount).minus(Money.of(this.holdbackAmount)).max(Money.ZERO).toBigDecimal();
    }

    public boolean isHoldbackReleasable(LocalDate today) {
        return !holdbackReleased
                && holdbackAmount.signum() > 0
                && holdbackReleaseDate != null
                && !today.isBefore(holdbackReleaseDate);
    }

    public BigDecimal getHoldbackAmount() { return holdbackAmount; }
    public BigDecimal getHoldbackRate() { return holdbackRate; }
    public LocalDate getHoldbackReleaseDate() { return holdbackReleaseDate; }
    public boolean isHoldbackReleased() { return holdbackReleased; }
    public LocalDateTime getHoldbackReleasedAt() { return holdbackReleasedAt; }
}
