package github.lms.lemuel.account.banking.timedeposit.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 정기예금 확정이자 계산기 — 순수 함수 모음(상태 없음, 부수효과 없음).
 *
 * <p>이자 산식을 애그리거트에서 떼어낸 이유는 두 가지다. ① 만기해지·중도해지가 <b>같은 산식에 다른
 * 이율</b>만 꽂는 관계라 산식이 한 곳에 있어야 두 경로가 영원히 같은 결과를 낸다. ② 반올림 규칙은
 * 회계 계약이라 단위 테스트로 못 박아야 하는데, 애그리거트 상태 조립 없이 값만으로 검증할 수 있어야 한다.
 *
 * <h2>계약</h2>
 * <ul>
 *   <li><b>일수 기준 ACT/365</b> — 분모는 언제나 상수 365다(윤년 366 로 바꾸지 않는다).
 *       경과일수는 {@code openedOn}(포함) ~ {@code closedOn}(제외) 의 실제 일수다.</li>
 *   <li><b>{@code double} 금지</b> — 전 구간 {@link BigDecimal}. 이진 부동소수 오차는 원 단위로
 *       내려오면 그대로 1원 드리프트가 되고, 그 드리프트는 GL 수신부채가 0 으로 안 닫히는 사고가 된다.</li>
 *   <li><b>중간 나눗셈은 반드시 반올림을 명시</b>한다({@link #INTERMEDIATE_SCALE} 자리 HALF_UP 또는
 *       {@link #INTERMEDIATE_CONTEXT}). 스케일 없는 {@code divide} 는 1/3 같은 무한소수에서
 *       {@code ArithmeticException} 을 던지므로 이율 하나 바꿨다고 런타임이 죽는다.</li>
 *   <li><b>최종 이자는 원 단위(소수 0자리) HALF_UP</b> — 원화 수신 상품은 원 미만을 지급하지 않으며,
 *       GL {@code numeric(19,2)} 에도 무손실로 들어간다.</li>
 * </ul>
 */
public final class TimeDepositInterest {

    /** ACT/365 — 분모 고정. */
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    private static final BigDecimal MONTHS_IN_YEAR = new BigDecimal("12");

    /** 중간 계산 유효자릿수 — 원 단위 반올림 전까지 오차를 흡수할 만큼 넉넉하게. */
    static final int INTERMEDIATE_SCALE = 10;

    /** 거듭제곱(월복리 성장률)용 — pow 를 정밀도 없이 쓰면 지수만큼 자릿수가 폭발한다. */
    private static final MathContext INTERMEDIATE_CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

    /** 최종 이자 스케일 — 원 단위. */
    private static final int WON_SCALE = 0;

    private TimeDepositInterest() {
        // 유틸리티 — 인스턴스화 금지
    }

    /**
     * 개설일부터 해지일까지의 확정이자를 원 단위로 계산한다.
     *
     * <p>만기해지는 {@code annualRate} 를, 중도해지는 {@code earlyTerminationRate} 를 넘기면 된다 —
     * 이 함수는 어느 쪽인지 알지 못하며 알 필요도 없다(같은 산식·다른 이율).
     *
     * @param rate 적용 이율(연리). 만기해지=약정이율, 중도해지=중도해지이율
     * @return 원 단위(소수 0자리) 확정이자. 경과일수 0 이하 또는 이율 0 이면 {@code 0}
     */
    public static BigDecimal accrued(BigDecimal principal, BigDecimal rate, Compounding compounding,
                                     LocalDate openedOn, LocalDate closedOn) {
        long days = ChronoUnit.DAYS.between(openedOn, closedOn);
        if (days <= 0 || rate.signum() == 0) {
            // 당일 해지·무이자 상품 — 이자 전표 자체를 만들지 않는다(양수 금액만 허용하는 GL 팩토리 보호)
            return BigDecimal.ZERO.setScale(WON_SCALE);
        }
        BigDecimal raw = switch (compounding) {
            case SIMPLE -> simpleInterest(principal, rate, days);
            case MONTHLY_COMPOUND -> monthlyCompoundInterest(principal, rate, openedOn, closedOn);
        };
        return raw.setScale(WON_SCALE, RoundingMode.HALF_UP);
    }

    /** 단리: {@code 원금 × 이율 × 경과일수 / 365}. */
    private static BigDecimal simpleInterest(BigDecimal base, BigDecimal rate, long days) {
        return base.multiply(rate)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_IN_YEAR, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 월복리: 경과한 완전한 개월 수만큼 월이율로 복리 적립하고, 남는 자투리 일수는 같은 이율의 단리로 덧붙인다.
     *
     * <p>자투리 단리의 원금은 최초 원금이 아니라 <b>복리 적립 후 잔액</b>이다 — 그래야 "복리 회차가
     * 끝난 시점부터 다시 이자가 붙는다"는 상품 설명과 일치한다. 자투리 구간의 시작일은
     * {@code openedOn.plusMonths(경과개월)} 이라 월말 보정(1/31 → 2/28)도 {@link LocalDate} 규칙을 그대로 따른다.
     */
    private static BigDecimal monthlyCompoundInterest(BigDecimal principal, BigDecimal rate,
                                                      LocalDate openedOn, LocalDate closedOn) {
        long wholeMonths = ChronoUnit.MONTHS.between(openedOn, closedOn);
        BigDecimal monthlyRate = rate.divide(MONTHS_IN_YEAR, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        // toIntExact 는 비현실적인 날짜(수억 개월)에서 조용히 음수 지수로 접히는 대신 즉시 터진다 —
        // pow 는 int 지수만 받으므로 여기서 좁히는 것을 숨기지 않는다.
        BigDecimal compounded = principal
                .multiply(BigDecimal.ONE.add(monthlyRate).pow(Math.toIntExact(wholeMonths), INTERMEDIATE_CONTEXT))
                .setScale(INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal compoundPart = compounded.subtract(principal);

        long stubDays = ChronoUnit.DAYS.between(openedOn.plusMonths(wholeMonths), closedOn);
        if (stubDays <= 0) {
            return compoundPart;
        }
        return compoundPart.add(simpleInterest(compounded, rate, stubDays));
    }
}
