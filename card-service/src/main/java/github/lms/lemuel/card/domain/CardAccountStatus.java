package github.lms.lemuel.card.domain;

import java.util.Set;

/**
 * 법인 카드계정 상태머신.
 *
 * <pre>
 * SCREENING → ACTIVE ⇄ SUSPENDED → CLOSED
 *           ↘ REJECTED (터미널)
 * ACTIVE → DELINQUENT → ACTIVE (전액 상환 시 복구)
 * DELINQUENT → CLOSED
 * </pre>
 *
 * <p>REJECTED·CLOSED 에서 나가는 전이는 없다 — 재신청은 새 카드계정이다.
 * DELINQUENT: 청구서 만기 경과 + 미상환 시 자동 전이. 연체 상태에서는 승인이 CARD_SUSPENDED 로 거절된다.
 */
public enum CardAccountStatus {

    SCREENING,
    ACTIVE,
    SUSPENDED,
    DELINQUENT,
    CLOSED,
    REJECTED;

    private static final java.util.Map<CardAccountStatus, Set<CardAccountStatus>> ALLOWED =
            java.util.Map.of(
                    SCREENING,   Set.of(ACTIVE, REJECTED),
                    ACTIVE,      Set.of(SUSPENDED, CLOSED, DELINQUENT),
                    SUSPENDED,   Set.of(ACTIVE, CLOSED),
                    DELINQUENT,  Set.of(ACTIVE, CLOSED),
                    CLOSED,      Set.of(),
                    REJECTED,    Set.of());

    public boolean canTransitionTo(CardAccountStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
