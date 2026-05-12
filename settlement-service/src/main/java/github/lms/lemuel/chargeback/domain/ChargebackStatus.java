package github.lms.lemuel.chargeback.domain;

/**
 * Chargeback 상태 머신.
 *
 * <pre>
 *   OPEN  ──→  ACCEPTED   (셀러 책임 인정 — settlement_adjustments 음수 row 생성)
 *      └──→  REJECTED    (셀러가 증빙 제출, 운영자가 승인 — 분쟁 종결, 정산 영향 없음)
 * </pre>
 *
 * <p>ACCEPTED / REJECTED 둘 다 종료 상태 — 재오픈은 동일 PG 분쟁 ID 로 새 row 를 만드는 식으로만 가능.
 */
public enum ChargebackStatus {
    OPEN,
    ACCEPTED,
    REJECTED;

    public boolean canTransitionTo(ChargebackStatus target) {
        return this == OPEN && (target == ACCEPTED || target == REJECTED);
    }

    public boolean isFinal() {
        return this == ACCEPTED || this == REJECTED;
    }
}
