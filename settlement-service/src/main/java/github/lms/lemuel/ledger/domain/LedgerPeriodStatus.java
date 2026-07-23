package github.lms.lemuel.ledger.domain;

/**
 * 원장 회계 기간(월)의 라이프사이클 상태.
 *
 * <pre>
 * OPEN ──close()──→ CLOSED
 * </pre>
 *
 * <p>CLOSED 는 종결 상태다. 마감된 기간(월)에는 신규 원분개를 전기할 수 없으며(도메인 거부),
 * 마감 기간을 대상으로 한 역분개는 재개봉 대신 다음 OPEN 기간으로 재지정해 전기한다(ADR 확정 정책).
 * 마감을 되돌리는 재개봉(reopen) 전이는 존재하지 않는다 — 정정은 다음 OPEN 기간의 신규 분개로만.
 */
public enum LedgerPeriodStatus {
    OPEN,
    CLOSED;

    public boolean canTransitionTo(LedgerPeriodStatus next) {
        return switch (this) {
            case OPEN -> next == CLOSED;
            case CLOSED -> false;
        };
    }

    public boolean isFinal() {
        return this == CLOSED;
    }
}
