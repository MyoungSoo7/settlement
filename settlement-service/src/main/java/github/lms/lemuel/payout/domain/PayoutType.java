package github.lms.lemuel.payout.domain;

/**
 * 지급 유형 — 한 정산이 만들어 내는 서로 다른 송금의 성격을 구분한다.
 *
 * <p>정산 1건은 지급 유형별로 최대 1건의 {@link Payout} 을 만든다((settlement_id, payout_type) 멱등).
 * 즉시지급분과 보류(holdback) 해제분은 발생 시점·금액 산정 근거가 달라 별개 송금으로 분리한다.
 * <ul>
 *   <li>{@code IMMEDIATE} — 정산 확정 시점의 즉시 지급액(net − 미해제 holdback).</li>
 *   <li>{@code HOLDBACK_RELEASE} — 보류 해제 시점의 잔여 보류액(환불·차지백·PG 대사로 소비되지 않은 분).</li>
 * </ul>
 */
public enum PayoutType {
    /** 정산 확정 즉시 지급분. */
    IMMEDIATE,
    /** 보류 해제 시 잔여 보류액 지급분. */
    HOLDBACK_RELEASE
}
