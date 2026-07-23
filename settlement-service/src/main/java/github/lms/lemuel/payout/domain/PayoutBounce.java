package github.lms.lemuel.payout.domain;

import github.lms.lemuel.payout.domain.exception.PayoutInvariantViolationException;

import java.time.LocalDateTime;

/**
 * 송금 반송(bounce) 레코드 — COMPLETED 로 종결된 송금이 은행단에서 계좌 문제로 되돌아온 사후 사건.
 *
 * <p><b>왜 별도 레코드인가</b>: 펌뱅킹 호출 실패(SENDING→FAILED→retry)는 이미 상태머신이 처리한다.
 * 반송은 다르다 — 송금이 COMPLETED(자금 이동 성공)로 <i>종결된 뒤</i> 은행이 자금을 되돌리는 사건이다.
 * COMPLETED 는 종결 상태라 원 payout 을 변경하지 않는다(P0-6 선례: 완료 payout 미변경 + 별도 레코드).
 * 대신 반송 사실을 이 레코드로 남기고, 정정된 계좌로 <i>신규</i> payout 을 재발행해 그 id 를
 * {@link #resolvedPayoutId} 로 링크한다.
 *
 * <p><b>멱등</b>: {@code payout_id} 는 DB UNIQUE — 한 송금에 대한 반송은 정확히 한 번만 기록되고,
 * 재발행 payout 도 정확히 한 건만 만들어진다({@link #resolveWith} set-once). 생성·해소는 팩토리/도메인
 * 메서드 전용 — public setter 없음.
 */
public class PayoutBounce {

    private final Long id;
    private final Long payoutId;
    private final String reason;
    private Long resolvedPayoutId;
    private final String operatorId;
    private final LocalDateTime bouncedAt;
    private final LocalDateTime createdAt;

    private PayoutBounce(Long id, Long payoutId, String reason, Long resolvedPayoutId,
                         String operatorId, LocalDateTime bouncedAt, LocalDateTime createdAt) {
        if (payoutId == null) {
            throw new PayoutInvariantViolationException("payoutId 는 필수입니다");
        }
        if (reason == null || reason.isBlank()) {
            throw new PayoutInvariantViolationException("반송 사유는 필수입니다 (감사 추적)");
        }
        this.id = id;
        this.payoutId = payoutId;
        this.reason = reason;
        this.resolvedPayoutId = resolvedPayoutId;
        this.operatorId = operatorId;
        this.bouncedAt = bouncedAt;
        this.createdAt = createdAt;
    }

    /** 반송 기록 — 아직 재발행 전(resolvedPayoutId=null). */
    public static PayoutBounce record(Long payoutId, String reason, String operatorId) {
        LocalDateTime now = LocalDateTime.now();
        return new PayoutBounce(null, payoutId, reason, null, operatorId, now, now);
    }

    /** 영속 복원 전용 — 저장된 상태를 그대로 보존한다. */
    public static PayoutBounce rehydrate(Long id, Long payoutId, String reason, Long resolvedPayoutId,
                                         String operatorId, LocalDateTime bouncedAt, LocalDateTime createdAt) {
        return new PayoutBounce(id, payoutId, reason, resolvedPayoutId, operatorId, bouncedAt, createdAt);
    }

    /**
     * 재발행 payout 을 연결한다 — set-once. 이미 해소된 반송에 다시 연결하려는 시도는 이중지급의
     * 신호이므로 도메인 예외로 차단한다(멱등은 서비스가 조회로 먼저 거르지만, 도메인도 방어한다).
     */
    public void resolveWith(Long reissuedPayoutId) {
        if (reissuedPayoutId == null) {
            throw new PayoutInvariantViolationException("재발행 payoutId 는 필수입니다");
        }
        if (this.resolvedPayoutId != null) {
            throw new PayoutInvariantViolationException(
                    "이미 재발행된 반송 — 이중 재발행 차단: payoutId=" + payoutId
                            + ", resolved=" + resolvedPayoutId);
        }
        this.resolvedPayoutId = reissuedPayoutId;
    }

    public boolean isResolved() {
        return resolvedPayoutId != null;
    }

    public Long getId() { return id; }
    public Long getPayoutId() { return payoutId; }
    public String getReason() { return reason; }
    public Long getResolvedPayoutId() { return resolvedPayoutId; }
    public String getOperatorId() { return operatorId; }
    public LocalDateTime getBouncedAt() { return bouncedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
