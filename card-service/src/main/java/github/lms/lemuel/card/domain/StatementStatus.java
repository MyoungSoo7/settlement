package github.lms.lemuel.card.domain;

import java.util.Set;

/**
 * 청구서(명세서) 상태머신.
 *
 * <pre>
 * OPEN → CLOSED → PARTIALLY_PAID → PAID
 *              ↘ DELINQUENT (만기 경과 + 미상환)
 * PARTIALLY_PAID → PAID
 *               → DELINQUENT
 * </pre>
 *
 * <p>OPEN: 청구 주기가 진행 중인 명세서. 캡처 건이 쌓인다.
 * CLOSED: 청구 주기가 마감됐으나 아직 납부가 없다.
 * PARTIALLY_PAID: 일부 납부 완료.
 * PAID: 전액 납부 완료. 카드계정 연체라면 자동 복구된다.
 * DELINQUENT: 만기 경과 + 미납. 카드계정도 함께 DELINQUENT 로 전이된다.
 */
public enum StatementStatus {

    OPEN,
    CLOSED,
    PARTIALLY_PAID,
    PAID,
    DELINQUENT;

    private static final java.util.Map<StatementStatus, Set<StatementStatus>> ALLOWED =
            java.util.Map.of(
                    OPEN,            Set.of(CLOSED),
                    CLOSED,          Set.of(PARTIALLY_PAID, PAID, DELINQUENT),
                    PARTIALLY_PAID,  Set.of(PAID, DELINQUENT),
                    PAID,            Set.of(),
                    DELINQUENT,      Set.of(PAID)   // 연체 후 전액 납부 시 PAID 로 전환
            );

    public boolean canTransitionTo(StatementStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
