package github.lms.lemuel.card.domain;

import java.util.Set;

/**
 * 법인 카드계정 상태머신.
 *
 * <p>SCREENING → ACTIVE ⇄ SUSPENDED → CLOSED. 심사 탈락은 REJECTED(터미널).
 * REJECTED·CLOSED 에서 나가는 전이는 없다 — 재신청은 새 카드계정이다.
 */
public enum CardAccountStatus {

    SCREENING,
    ACTIVE,
    SUSPENDED,
    CLOSED,
    REJECTED;

    private static final java.util.Map<CardAccountStatus, Set<CardAccountStatus>> ALLOWED =
            java.util.Map.of(
                    SCREENING, Set.of(ACTIVE, REJECTED),
                    ACTIVE, Set.of(SUSPENDED, CLOSED),
                    SUSPENDED, Set.of(ACTIVE, CLOSED),
                    CLOSED, Set.of(),
                    REJECTED, Set.of());

    public boolean canTransitionTo(CardAccountStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
