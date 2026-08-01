package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 한도 심사 결과 — 승인/탈락 어느 쪽이든 {@link LimitSnapshot} 근거를 항상 동반한다.
 * 근거 없는 승인·거절을 남기지 않기 위해서다(브리프 리졸루션 #2, {@code snapshotAlwaysPreserved} 로 고정).
 */
public record ScreeningResult(boolean approved, BigDecimal masterLimit, LimitSnapshot snapshot,
                               String rejectReason) {

    /** 심사 승인. rejectReason 은 없다(null). */
    public static ScreeningResult approved(BigDecimal limit, LimitSnapshot snapshot) {
        return new ScreeningResult(true, limit, snapshot, null);
    }

    /** 심사 탈락. masterLimit 은 근거를 남기지 않기 위해 0으로 고정한다. */
    public static ScreeningResult rejected(LimitSnapshot snapshot, String reason) {
        return new ScreeningResult(false, BigDecimal.ZERO, snapshot, reason);
    }
}
