package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 법인카드 한도 산정 정책 — 부수효과 없는 순수 도메인 정책.
 *
 * <pre>
 *   F = sellerPayable + holdbackPayable   (확정·미지급 정산금 + 홀드백 유보분)
 *   R = 인정비율 (설정 주입, 기본 0.70)
 *   H = 평판 haircut (A·B 1.00 / C 0.85 / D 0.70 / E 0.00)
 *   masterLimit = floor(F x R x H)
 * </pre>
 *
 * <p>R 이 1 이 아닌 이유: F 는 곧 셀러에게 지급될 돈이라, 카드 이용과 정산 지급이 같은 재원을
 * 두 번 쓸 수 있다. 실제 상계는 3단계(청구 사이클)의 몫이고, 그때까지 R 이 그 위험을 흡수한다.
 * 상계가 구현되면 이 값은 재조정 대상이다.
 */
public class CardLimitPolicy {

    private static final String FORMULA = "floor((sellerPayable + holdbackPayable) x R x H)";

    private final BigDecimal recognitionRatio;
    private final BigDecimal minimumLimit;

    public CardLimitPolicy(BigDecimal recognitionRatio, BigDecimal minimumLimit) {
        if (recognitionRatio == null || recognitionRatio.signum() <= 0
                || recognitionRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("인정비율은 0 초과 1 이하여야 합니다: " + recognitionRatio);
        }
        if (minimumLimit == null || minimumLimit.signum() < 0) {
            throw new IllegalArgumentException("최소한도는 0 이상이어야 합니다: " + minimumLimit);
        }
        this.recognitionRatio = recognitionRatio;
        this.minimumLimit = minimumLimit;
    }

    public ScreeningResult screen(BigDecimal sellerPayable, BigDecimal holdbackPayable,
                                  ReputationGrade grade) {
        // grade.haircut() 을 그대로 호출하면 NPE 로 죽는다 — 도메인 정책이 익명 NPE 보다는
        // 명시적 계약 위반(어떤 인자가 왜 잘못됐는지)으로 실패하는 편이 호출자 디버깅에 낫다.
        Objects.requireNonNull(grade, "평판 등급은 필수입니다");
        BigDecimal payable = nonNegative(sellerPayable);
        BigDecimal holdback = nonNegative(holdbackPayable);
        LimitSnapshot snapshot =
                new LimitSnapshot(payable, holdback, recognitionRatio, grade, FORMULA);

        // 원 단위 절사(FLOOR) — 반올림으로 1원이라도 더 주지 않는다.
        BigDecimal limit = snapshot.funding()
                .multiply(recognitionRatio)
                .multiply(grade.haircut())
                .setScale(0, RoundingMode.FLOOR);

        // E등급은 haircut 이 0 이라 산식만으로도 0 이 나오지만, 거절 사유를 명확히 구분하기 위해
        // 최소한도 미달 검사보다 먼저 평판 탈락을 검사한다("평판" vs "최소" — 감사·CS 응대 시 원인이 다르다).
        if (grade == ReputationGrade.E) {
            return ScreeningResult.rejected(snapshot, "평판 등급 E 는 카드 발급 대상이 아닙니다.");
        }
        if (limit.compareTo(minimumLimit) < 0) {
            return ScreeningResult.rejected(snapshot,
                    "산정 한도 " + limit.toPlainString() + " 원이 최소한도 "
                            + minimumLimit.toPlainString() + " 원에 미달합니다.");
        }
        return ScreeningResult.approved(limit, snapshot);
    }

    /** 회계상 음수 잔액(과지급 등)이 한도를 만들지 않도록 0 으로 바닥을 친다. */
    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }
}
