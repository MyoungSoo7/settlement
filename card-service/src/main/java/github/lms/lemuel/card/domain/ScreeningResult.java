package github.lms.lemuel.card.domain;

import java.math.BigDecimal;

/**
 * 한도 심사 결과 — 승인/탈락 어느 쪽이든 {@link LimitSnapshot} 근거를 항상 동반한다.
 * 근거 없는 승인·거절을 남기지 않기 위해서다(브리프 리졸루션 #2, {@code snapshotAlwaysPreserved} 로 고정).
 *
 * <p>canonical 생성자가 public 이라 정적 팩토리를 거치지 않고도 인스턴스를 만들 수 있다.
 * 컴팩트 생성자로 불변식을 강제하지 않으면 "승인인데 거절 사유가 있다" 같은 모순된 인스턴스를
 * 소비측(Task 9 유스케이스 등)이 조용히 만들어낼 수 있다 — 그러면 소비측이 어느 필드를 믿어야
 * 할지 알 수 없어져 사고로 이어진다. 그래서 여기서 컴파일러가 아니라 런타임으로나마 막는다.
 */
public record ScreeningResult(boolean approved, BigDecimal masterLimit, LimitSnapshot snapshot,
                               String rejectReason) {

    public ScreeningResult {
        // 근거 없는 판정을 남기지 않는다 — 승인이든 탈락이든 산정 근거(LimitSnapshot)는 항상 있어야 한다.
        if (snapshot == null) {
            throw new IllegalArgumentException("한도 산정 근거(snapshot)는 필수입니다 — 근거 없는 판정을 남기지 않는다");
        }
        // masterLimit 은 금액이라 null·음수를 허용하면 소비측이 그대로 카드 한도에 반영해 사고로 이어진다.
        if (masterLimit == null || masterLimit.signum() < 0) {
            throw new IllegalArgumentException("masterLimit 은 null 이거나 음수일 수 없습니다: " + masterLimit);
        }
        if (approved) {
            // 승인과 거절 사유가 동시에 존재하면 소비측이 어느 쪽을 믿어야 할지 알 수 없다.
            if (rejectReason != null) {
                throw new IllegalArgumentException("승인 결과는 거절 사유를 가질 수 없습니다: " + rejectReason);
            }
        } else {
            if (rejectReason == null || rejectReason.isBlank()) {
                throw new IllegalArgumentException("탈락 결과는 거절 사유가 필수입니다");
            }
            // compareTo 사용 — equals 는 scale 이 다르면(예: "0" vs "0.00") false 라 금액 비교에 쓰면 버그다.
            if (masterLimit.compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException("탈락 결과의 masterLimit 은 0 이어야 합니다: " + masterLimit);
            }
        }
    }

    /** 심사 승인. rejectReason 은 없다(null). */
    public static ScreeningResult approved(BigDecimal limit, LimitSnapshot snapshot) {
        return new ScreeningResult(true, limit, snapshot, null);
    }

    /** 심사 탈락. masterLimit 은 근거를 남기지 않기 위해 0으로 고정한다. */
    public static ScreeningResult rejected(LimitSnapshot snapshot, String reason) {
        return new ScreeningResult(false, BigDecimal.ZERO, snapshot, reason);
    }
}
