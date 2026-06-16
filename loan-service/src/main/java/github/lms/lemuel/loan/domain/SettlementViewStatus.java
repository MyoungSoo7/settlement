package github.lms.lemuel.loan.domain;

/**
 * 로컬 정산 뷰 상태.
 *
 * <pre>
 * PENDING   정산 생성됨 — 미지급 정산예정금(담보)
 * CONFIRMED 정산 확정됨 — 상환 차감이 일어난 시점
 * </pre>
 *
 * <p>1차는 PAID 를 두지 않는다 (loan 은 payout 완료를 모르며 상환은 CONFIRMED 시점에 확정된다).
 */
public enum SettlementViewStatus {
    PENDING,
    CONFIRMED
}
