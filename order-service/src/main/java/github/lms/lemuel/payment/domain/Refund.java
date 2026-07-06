package github.lms.lemuel.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 환불 도메인 — V4 refunds 테이블의 도메인 모델.
 * idempotency_key 를 이용한 멱등성 보장 및 환불 이력 추적의 단위.
 */
public class Refund {

    public enum Status { REQUESTED, COMPLETED, FAILED }

    /**
     * 자동 재시도 상한. 이 횟수만큼 실패하면 {@code nextRetryAt} 을 비워(NULL) 스케줄러가 더는
     * 재시도하지 않고 관리자 개입 대상(/admin/refunds?status=FAILED)으로 남긴다.
     */
    public static final int MAX_RETRIES = 5;

    /**
     * 실패 횟수별 백오프(분). retryCount 가 커질수록 재시도 간격을 늘려 PG 장애가 길어질 때
     * 무의미한 재호출 폭주를 막는다. 배열 길이를 넘는 실패는 마지막 값(가장 긴 간격)을 재사용.
     */
    private static final long[] BACKOFF_MINUTES = {1, 5, 15, 60, 180};

    private Long id;
    private Long paymentId;
    private BigDecimal amount;
    private Status status;
    private String reason;
    private String idempotencyKey;
    private int retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Refund() {}

    public static Refund request(Long paymentId, BigDecimal amount, String idempotencyKey, String reason) {
        if (paymentId == null) throw new IllegalArgumentException("paymentId required");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey required");
        }
        Refund refund = new Refund();
        refund.paymentId = paymentId;
        refund.amount = amount;
        refund.idempotencyKey = idempotencyKey;
        refund.reason = reason;
        refund.status = Status.REQUESTED;
        refund.retryCount = 0;
        refund.requestedAt = LocalDateTime.now();
        refund.createdAt = LocalDateTime.now();
        refund.updatedAt = LocalDateTime.now();
        return refund;
    }

    /**
     * 환불 성공 확정. 최초 시도(REQUESTED)뿐 아니라 재시도로 살아난 실패건(FAILED)에서도
     * COMPLETED 로 전이할 수 있다. 완료되면 재시도 예약을 해제한다.
     */
    public void markCompleted() {
        if (this.status == Status.COMPLETED) {
            throw new IllegalStateException("Refund already COMPLETED. id=" + id);
        }
        this.status = Status.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.nextRetryAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 환불 실패 기록. 재시도 횟수를 늘리고 다음 재시도 시각(백오프)을 예약한다.
     * 상한({@link #MAX_RETRIES})에 도달하면 예약을 비워 자동 재시도를 멈춘다.
     * 최초 실패(REQUESTED→FAILED)와 재시도 실패(FAILED→FAILED) 모두 허용하며, 이미 COMPLETED 된
     * 환불은 실패로 되돌릴 수 없다.
     */
    public void markFailed(String failureReason) {
        if (this.status == Status.COMPLETED) {
            throw new IllegalStateException("Cannot fail a COMPLETED refund. id=" + id);
        }
        this.status = Status.FAILED;
        this.reason = failureReason;
        this.retryCount += 1;
        LocalDateTime now = LocalDateTime.now();
        if (this.retryCount >= MAX_RETRIES) {
            this.nextRetryAt = null; // 소진 — 자동 재시도 중단, 관리자 개입 대상
        } else {
            long minutes = BACKOFF_MINUTES[Math.min(this.retryCount - 1, BACKOFF_MINUTES.length - 1)];
            this.nextRetryAt = now.plusMinutes(minutes);
        }
        this.updatedAt = now;
    }

    /**
     * 더 이상 적용할 수 없는 실패건을 자동 재시도에서 영구 제외한다(재시도 소진 상태로 고정).
     * 예: 결제가 이미 전액 환불돼 이 환불을 적용하면 초과가 되는 경우. 관리자 개입 대상으로 남는다.
     */
    public void abandon(String reason) {
        if (this.status == Status.COMPLETED) {
            throw new IllegalStateException("Cannot abandon a COMPLETED refund. id=" + id);
        }
        this.status = Status.FAILED;
        this.reason = reason;
        this.retryCount = MAX_RETRIES;
        this.nextRetryAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isCompleted() { return status == Status.COMPLETED; }

    /** 자동 재시도 대상인지(FAILED + 예약 시각 존재). 소진된 실패건은 false. */
    public boolean isRetryable() {
        return status == Status.FAILED && nextRetryAt != null;
    }

    /** 재시도 상한에 도달해 자동 재시도가 중단된 실패건인지(관리자 개입 필요). */
    public boolean isRetryExhausted() {
        return status == Status.FAILED && retryCount >= MAX_RETRIES;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
