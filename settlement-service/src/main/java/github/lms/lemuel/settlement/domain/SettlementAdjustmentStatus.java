package github.lms.lemuel.settlement.domain;

/**
 * 정산 조정(역정산) 레코드 상태.
 *
 * <p>도메인 팩토리({@link SettlementAdjustment#ofRefund}·{@code ofChargeback}·{@code ofReconciliation})는
 * 항상 {@link #PENDING} 으로 생성하며, {@link #CONFIRMED} 는 영속 레코드 복원({@code rehydrate}) 시에만
 * 나타난다. 조정 레코드는 감사 추적용 append-only 라 도메인 내부 전이는 없다.
 */
public enum SettlementAdjustmentStatus {
    PENDING,
    CONFIRMED;

    /** 영속 문자열 → enum 복원. null/blank 는 {@link #PENDING} 으로 간주(레거시 방어). */
    public static SettlementAdjustmentStatus fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        return valueOf(raw.trim().toUpperCase());
    }
}
