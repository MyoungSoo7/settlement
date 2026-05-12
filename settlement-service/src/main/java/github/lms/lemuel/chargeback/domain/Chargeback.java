package github.lms.lemuel.chargeback.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 카드사 분쟁(Chargeback) 도메인 — 환불(Refund)과 별개의 회계 원장.
 *
 * <p>차이점:
 * <ul>
 *   <li>Refund: 고객이 셀러에게 환불 요청 → 셀러 응답 → 환불 처리</li>
 *   <li>Chargeback: 고객이 카드사에 신고 → 카드사가 PG 에 강제 차감 → PG 가 운영사 통지</li>
 * </ul>
 *
 * <p>핵심 불변식:
 * <ol>
 *   <li>{@code amount} 양수 (도메인 + DB CHECK 이중 방어)</li>
 *   <li>상태 전이 강제: {@code OPEN → ACCEPTED | REJECTED} 만 허용. 종료 상태에서 재결정 불가</li>
 *   <li>ACCEPTED 시 {@code decidedBy} 필수 — 누가 셀러 환수를 결정했는지 감사</li>
 *   <li>PG_WEBHOOK 출처는 {@code pgChargebackId} 필수 — 멱등 보장</li>
 * </ol>
 *
 * <p>ACCEPTED 결정 후의 회계 효과는 application service 가 책임 — 이 도메인은 결정만 표현.
 * 즉 SettlementAdjustment 생성·이미 Payout COMPLETED 인 경우의 환수는 외부에서 처리한다.
 */
public class Chargeback {

    private Long id;
    private final Long paymentId;
    private Long settlementId;          // 정산 생성 전 분쟁 가능 → mutable
    private final BigDecimal amount;
    private final ChargebackReason reasonCode;
    private final String reasonDetail;
    private final ChargebackSource source;
    private final String pgChargebackId;

    private ChargebackStatus status;
    private String decidedBy;
    private String decisionNote;

    private final LocalDateTime raisedAt;
    private LocalDateTime decidedAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** PG webhook 통지 또는 운영자 수동 등록으로 새 분쟁 오픈. */
    public static Chargeback open(Long paymentId, Long settlementId, BigDecimal amount,
                                   ChargebackReason reasonCode, String reasonDetail,
                                   ChargebackSource source, String pgChargebackId) {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(source, "source");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount 는 양수여야 합니다");
        }
        if (source == ChargebackSource.PG_WEBHOOK
                && (pgChargebackId == null || pgChargebackId.isBlank())) {
            throw new IllegalArgumentException("PG_WEBHOOK 출처는 pgChargebackId 필수 (멱등 키)");
        }
        LocalDateTime now = LocalDateTime.now();
        return new Chargeback(null, paymentId, settlementId, amount, reasonCode, reasonDetail,
                source, pgChargebackId,
                ChargebackStatus.OPEN, null, null,
                now, null, now, now);
    }

    public static Chargeback rehydrate(Long id, Long paymentId, Long settlementId, BigDecimal amount,
                                        ChargebackReason reasonCode, String reasonDetail,
                                        ChargebackSource source, String pgChargebackId,
                                        ChargebackStatus status, String decidedBy, String decisionNote,
                                        LocalDateTime raisedAt, LocalDateTime decidedAt,
                                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Chargeback(id, paymentId, settlementId, amount, reasonCode, reasonDetail,
                source, pgChargebackId, status, decidedBy, decisionNote,
                raisedAt, decidedAt, createdAt, updatedAt);
    }

    private Chargeback(Long id, Long paymentId, Long settlementId, BigDecimal amount,
                       ChargebackReason reasonCode, String reasonDetail,
                       ChargebackSource source, String pgChargebackId,
                       ChargebackStatus status, String decidedBy, String decisionNote,
                       LocalDateTime raisedAt, LocalDateTime decidedAt,
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.settlementId = settlementId;
        this.amount = amount;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.source = source;
        this.pgChargebackId = pgChargebackId;
        this.status = status;
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.raisedAt = raisedAt;
        this.decidedAt = decidedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 셀러 책임 인정 — 정산금에서 환수.
     * 이 호출 자체는 도메인 상태만 바꾸며, SettlementAdjustment 생성은 application service 가 한다.
     */
    public void accept(String decidedBy, String note) {
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy 필수 (감사 추적)");
        }
        transitionTo(ChargebackStatus.ACCEPTED);
        this.decidedBy = decidedBy;
        this.decisionNote = note;
        this.decidedAt = LocalDateTime.now();
        touch();
    }

    /**
     * 셀러가 증빙 제출 → 분쟁 기각. 정산 영향 없음.
     */
    public void reject(String decidedBy, String note) {
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy 필수 (감사 추적)");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("기각 사유 필수 (운영 검토 근거)");
        }
        transitionTo(ChargebackStatus.REJECTED);
        this.decidedBy = decidedBy;
        this.decisionNote = note;
        this.decidedAt = LocalDateTime.now();
        touch();
    }

    /**
     * 분쟁 발생 후 정산이 생성되면 settlementId 를 백필.
     * 종료 상태에서는 변경 금지.
     */
    public void linkSettlement(Long settlementId) {
        if (settlementId == null || settlementId <= 0) {
            throw new IllegalArgumentException("settlementId 는 양수");
        }
        if (this.status.isFinal()) {
            throw new IllegalStateException("종료 상태에서는 settlementId 변경 불가: " + this.status);
        }
        this.settlementId = settlementId;
        touch();
    }

    private void transitionTo(ChargebackStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Chargeback 상태 전이 불가: " + this.status + " → " + target);
        }
        this.status = target;
    }

    public boolean isOpen() { return status == ChargebackStatus.OPEN; }
    public boolean isAccepted() { return status == ChargebackStatus.ACCEPTED; }
    public boolean isRejected() { return status == ChargebackStatus.REJECTED; }

    public void assignId(Long id) {
        if (this.id != null) throw new IllegalStateException("id 1 회만 부여");
        this.id = id;
    }

    private void touch() { this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public Long getSettlementId() { return settlementId; }
    public BigDecimal getAmount() { return amount; }
    public ChargebackReason getReasonCode() { return reasonCode; }
    public String getReasonDetail() { return reasonDetail; }
    public ChargebackSource getSource() { return source; }
    public String getPgChargebackId() { return pgChargebackId; }
    public ChargebackStatus getStatus() { return status; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionNote() { return decisionNote; }
    public LocalDateTime getRaisedAt() { return raisedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
