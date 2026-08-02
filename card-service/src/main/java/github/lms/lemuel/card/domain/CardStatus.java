package github.lms.lemuel.card.domain;

import java.util.Set;

/**
 * 임직원 카드(Card) 상태머신.
 *
 * <p>ISSUED ⇄ SUSPENDED, 양쪽 모두 CANCELED 로 전이 가능(터미널). CardAccountStatus 와 동형이지만
 * 별도 enum 이다 — 카드계정과 카드는 서로 다른 애그리거트로, 상태가 독립적으로 변한다
 * (계정이 SUSPENDED 여도 개별 카드는 이미 CANCELED 였을 수 있다).
 */
public enum CardStatus {

    ISSUED,
    SUSPENDED,
    CANCELED;

    private static final java.util.Map<CardStatus, Set<CardStatus>> ALLOWED =
            java.util.Map.of(
                    ISSUED, Set.of(SUSPENDED, CANCELED),
                    SUSPENDED, Set.of(ISSUED, CANCELED),
                    CANCELED, Set.of());

    public boolean canTransitionTo(CardStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
