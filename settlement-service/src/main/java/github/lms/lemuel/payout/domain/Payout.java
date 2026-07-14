package github.lms.lemuel.payout.domain;

import github.lms.lemuel.payout.domain.exception.InvalidPayoutStateException;
import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 출금(Payout) 도메인 — 정산금을 셀러 계좌로 실제 송금하는 트랜잭션.
 *
 * <p>핵심 불변식:
 * <ul>
 *   <li>{@code COMPLETED} 는 {@code firmBankingTransactionId} 가 반드시 존재 (사후 추적)</li>
 *   <li>{@code retry()} 는 {@code FAILED} 에서만 가능 — REQUESTED/SENDING 재요청 차단</li>
 *   <li>{@code amount} 는 양수 (도메인 + DB 제약 이중 방어)</li>
 * </ul>
 */
public class Payout {

    private Long id;
    private final Long settlementId;     // 1 payout = 1 settlement (단순화). 수동 송금은 null
    private final Long sellerId;
    private final BigDecimal amount;
    private final SellerBankAccount account;

    private PayoutStatus status;
    private String firmBankingTransactionId;
    private String failureReason;
    private int retryCount;
    private String operatorId;

    private final LocalDateTime requestedAt;
    private LocalDateTime sentAt;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static Payout requestFromSettlement(Long settlementId, Long sellerId,
                                                 BigDecimal amount, SellerBankAccount account) {
        if (sellerId == null) {
            throw new PayoutInvariantViolationException("sellerId 는 필수입니다");
        }
        if (amount == null) {
            throw new PayoutInvariantViolationException("amount 는 필수입니다");
        }
        if (account == null) {
            throw new PayoutInvariantViolationException("account 는 필수입니다");
        }
        if (amount.signum() <= 0) {
            throw new PayoutInvariantViolationException("amount 는 양수여야 합니다");
        }
        LocalDateTime now = LocalDateTime.now();
        return new Payout(null, settlementId, sellerId, amount, account,
                PayoutStatus.REQUESTED, null, null, 0, null,
                now, null, null, null, now, now);
    }

    public static Payout rehydrate(Long id, Long settlementId, Long sellerId, BigDecimal amount,
                                    SellerBankAccount account, PayoutStatus status,
                                    String firmBankingTransactionId, String failureReason,
                                    int retryCount, String operatorId,
                                    LocalDateTime requestedAt, LocalDateTime sentAt,
                                    LocalDateTime completedAt, LocalDateTime failedAt,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Payout(id, settlementId, sellerId, amount, account, status,
                firmBankingTransactionId, failureReason, retryCount, operatorId,
                requestedAt, sentAt, completedAt, failedAt, createdAt, updatedAt);
    }

    private Payout(Long id, Long settlementId, Long sellerId, BigDecimal amount,
                   SellerBankAccount account, PayoutStatus status,
                   String firmBankingTransactionId, String failureReason,
                   int retryCount, String operatorId,
                   LocalDateTime requestedAt, LocalDateTime sentAt,
                   LocalDateTime completedAt, LocalDateTime failedAt,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.account = account;
        this.status = status;
        this.firmBankingTransactionId = firmBankingTransactionId;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        this.operatorId = operatorId;
        this.requestedAt = requestedAt;
        this.sentAt = sentAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 펌뱅킹 호출 시작. REQUESTED → SENDING.
     */
    public void startSending() {
        if (status != PayoutStatus.REQUESTED) {
            throw new InvalidPayoutStateException(status, PayoutStatus.SENDING);
        }
        this.status = PayoutStatus.SENDING;
        this.sentAt = LocalDateTime.now();
        touch();
    }

    /**
     * 펌뱅킹 응답 — 송금 완료. SENDING → COMPLETED.
     * @param firmBankingTransactionId 펌뱅킹 측 거래 ID (필수, 사후 추적용)
     */
    public void markCompleted(String firmBankingTransactionId) {
        if (status != PayoutStatus.SENDING) {
            throw new InvalidPayoutStateException(status, PayoutStatus.COMPLETED);
        }
        if (firmBankingTransactionId == null || firmBankingTransactionId.isBlank()) {
            throw new PayoutInvariantViolationException("firmBankingTransactionId 필수 — 사후 추적·환수 근거");
        }
        this.firmBankingTransactionId = firmBankingTransactionId;
        this.status = PayoutStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        touch();
    }

    /**
     * 펌뱅킹 응답 — 송금 실패. SENDING → FAILED.
     */
    public void markFailed(String reason) {
        if (status != PayoutStatus.SENDING) {
            throw new InvalidPayoutStateException(status, PayoutStatus.FAILED);
        }
        if (reason == null || reason.isBlank()) {
            throw new PayoutInvariantViolationException("실패 사유 필수");
        }
        this.failureReason = reason;
        this.status = PayoutStatus.FAILED;
        this.failedAt = LocalDateTime.now();
        touch();
    }

    /**
     * 운영자 재시도 — FAILED → REQUESTED. retryCount++.
     */
    public void retry(String operatorId) {
        if (status != PayoutStatus.FAILED) {
            throw new InvalidPayoutStateException(status, PayoutStatus.REQUESTED);
        }
        this.status = PayoutStatus.REQUESTED;
        this.retryCount++;
        this.operatorId = operatorId;
        // failureReason / failedAt 은 보존 — 사후 추적
        touch();
    }

    /**
     * 운영자 영구 취소 — FAILED 인 경우만. COMPLETED 는 환수 별도 처리.
     */
    public void cancel(String operatorId, String reason) {
        if (status != PayoutStatus.FAILED && status != PayoutStatus.REQUESTED) {
            throw new InvalidPayoutStateException(status, PayoutStatus.CANCELED);
        }
        if (reason == null || reason.isBlank()) {
            throw new PayoutInvariantViolationException("취소 사유 필수 (감사 추적)");
        }
        this.status = PayoutStatus.CANCELED;
        this.failureReason = "[CANCELED by " + operatorId + "] " + reason;
        this.operatorId = operatorId;
        touch();
    }

    public boolean isFinal() {
        return status == PayoutStatus.COMPLETED || status == PayoutStatus.CANCELED;
    }

    public boolean isRetryable() {
        return status == PayoutStatus.FAILED;
    }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id 1회만 부여");
        this.id = id;
    }

    private void touch() { this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public SellerBankAccount getAccount() { return account; }
    public PayoutStatus getStatus() { return status; }
    public String getFirmBankingTransactionId() { return firmBankingTransactionId; }
    public String getFailureReason() { return failureReason; }
    public int getRetryCount() { return retryCount; }
    public String getOperatorId() { return operatorId; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getFailedAt() { return failedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
