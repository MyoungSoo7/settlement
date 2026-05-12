package github.lms.lemuel.ledger.domain;

/**
 * 원장 항목의 라이프사이클 상태.
 *
 * <pre>
 * PENDING ──post()────→ POSTED
 *    │                     │
 *    └─reverse()──→ REVERSED ←─reverse()──┘
 * </pre>
 *
 * REVERSED 는 종결 상태. 원 엔트리는 불변(Immutable)이므로
 * 추가 정정은 신규 LedgerEntry 작성으로만 표현한다.
 */
public enum LedgerStatus {
    PENDING,
    POSTED,
    REVERSED;

    public boolean canTransitionTo(LedgerStatus next) {
        return switch (this) {
            case PENDING  -> next == POSTED || next == REVERSED;
            case POSTED   -> next == REVERSED;
            case REVERSED -> false;
        };
    }

    public boolean isFinal() {
        return this == REVERSED;
    }
}
