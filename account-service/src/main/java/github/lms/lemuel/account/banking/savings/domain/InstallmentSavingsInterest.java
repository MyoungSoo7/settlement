package github.lms.lemuel.account.banking.savings.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 적금 이자 계산기 — 순수 함수만 가진 무상태 클래스(도메인 안, 프레임워크 의존 0).
 *
 * <h3>왜 예금과 다른가 — 회차별 일수 가중</h3>
 * 정기예금은 원금 하나가 만기까지 통째로 예치되므로 "원금 × 이율 × 기간"이면 끝난다. 적금은
 * <b>회차마다 예치 시작일이 다르다</b>. 1회차는 만기까지 거의 전 기간을, 마지막 회차는 한 달 남짓만
 * 예치된다. 그래서 이자는 회차별로 따로 계산해 더한다:
 *
 * <pre>
 *   Σ_i ( paidAmount_i × rate × daysHeld_i / 365 )
 *   daysHeld_i = 해당 회차의 실제 납입일(paidOn) → 만기일(또는 중도해지일) 까지의 일수
 * </pre>
 *
 * <h3>일수 계산 기준(day count basis)</h3>
 * <b>ACT/365 단리</b>. 실제 경과일수를 세고 분모는 항상 365 로 고정한다(윤년에도 366 을 쓰지 않는다).
 * 국내 수신상품 이자 계산의 표준 관행이며, 분모가 계약마다 흔들리지 않아 재현·검증이 쉽다.
 *
 * <h3>연체(overdue)의 이자 반영</h3>
 * 연체는 만기일을 밀지 않는다. 대신 그 회차의 {@code paidOn} 이 늦어진 만큼 {@code daysHeld} 가
 * <b>자동으로 줄어들어</b> 그 회차의 이자만 감소한다 — 별도의 연체 페널티 계수가 없다.
 * 즉 이 계산기는 연체를 특별 취급하지 않는다. 실제 납입일만 보면 연체 효과가 이미 반영돼 있기 때문이다.
 *
 * <h3>반올림 규약</h3>
 * <ul>
 *   <li>중간 나눗셈({@code /365})은 <b>반드시 scale 을 명시</b>한다 — scale 10, HALF_UP.
 *       무지정 {@code divide} 는 무한소수에서 {@code ArithmeticException} 으로 터진다.</li>
 *   <li>원 단위 반올림(scale 0, HALF_UP)은 <b>합계에 딱 한 번</b>만 적용한다.
 *       회차마다 반올림하면 회차 수만큼 반올림 오차가 누적돼 예금주에게 불리·유리하게 표류한다.</li>
 * </ul>
 */
public final class InstallmentSavingsInterest {

    /** ACT/365 — 분모 고정. */
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    /** 중간 계산 정밀도. 원 단위(0) 로 접기 전까지의 유효자리 — 무지정 divide 금지 규약의 실체. */
    private static final int INTERMEDIATE_SCALE = 10;

    private InstallmentSavingsInterest() {
    }

    /**
     * 회차 목록에 대한 확정이자(원 단위).
     *
     * @param installments 납입된 회차들 (null·빈 목록이면 0)
     * @param rate         적용 이율 — 만기해지면 약정이율, 중도해지면 중도해지이율
     * @param endDate      이자 계산 종료일 — 만기일 또는 중도해지일
     * @return 원 단위(scale 0) HALF_UP 반올림된 총 이자. 음수는 나올 수 없다.
     */
    public static BigDecimal calculate(List<SavingsInstallment> installments,
                                       BigDecimal rate, LocalDate endDate) {
        if (installments == null || installments.isEmpty()
                || rate == null || rate.signum() <= 0 || endDate == null) {
            return BigDecimal.ZERO;   // 이미 scale 0 — 반환 규약(원 단위)과 일치
        }
        BigDecimal total = BigDecimal.ZERO;
        for (SavingsInstallment installment : installments) {
            long days = daysHeld(installment.getPaidOn(), endDate);
            if (days == 0L) {
                continue;   // 종료일 당일·이후 납입 — 예치 기간이 없으므로 이자도 없다
            }
            // rate × days / 365 를 먼저 접는다(scale 10). 금액을 곱하기 전에 나눠야
            // 회차 금액 크기와 무관하게 같은 유효자리로 계산돼 회차 간 비교가 재현된다.
            BigDecimal dayFactor = rate
                    .multiply(BigDecimal.valueOf(days))
                    .divide(DAYS_PER_YEAR, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
            total = total.add(installment.getAmount().multiply(dayFactor));
        }
        // 반올림은 여기서 딱 한 번 — 회차별 반올림 누적 오차 방지.
        return total.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 한 회차의 예치일수 — 납입일 → 종료일. 종료일 이후 납입(있을 수 없지만 방어)은 0 으로 눌러
     * 음수 이자가 생기지 않게 한다.
     */
    public static long daysHeld(LocalDate paidOn, LocalDate endDate) {
        if (paidOn == null || endDate == null) {
            return 0L;
        }
        long days = ChronoUnit.DAYS.between(paidOn, endDate);
        return Math.max(days, 0L);
    }
}
