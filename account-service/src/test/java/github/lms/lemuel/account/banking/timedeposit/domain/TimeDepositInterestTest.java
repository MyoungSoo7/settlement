package github.lms.lemuel.account.banking.timedeposit.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확정이자 산식 계약 검증 — ACT/365, BigDecimal 전 구간, 원 단위 HALF_UP.
 *
 * <p>이 테스트가 지키는 것은 "코드가 도는가"가 아니라 <b>숫자가 정확히 이 값인가</b>다.
 * 수신 상품에서 반올림 규칙이 흔들리면 GL 수신부채가 0 으로 닫히지 않고, 그 1원은 대사로 못 잡는다.
 */
class TimeDepositInterestTest {

    private static final LocalDate OPENED = LocalDate.of(2026, 1, 1);

    @Test
    void 단리는_원금과_이율과_예치일수에_비례한다() {
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("10000000"), new BigDecimal("0.035"),
                Compounding.SIMPLE, OPENED, LocalDate.of(2027, 1, 1));

        // 정확히 365일 예치 → 연이율 그대로: 10,000,000 × 3.5% = 350,000
        assertThat(interest).isEqualByComparingTo("350000");
    }

    @Test
    void 단리는_경과일수만큼만_ACT365_기준으로_계산된다() {
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("1000000"), new BigDecimal("0.021"),
                Compounding.SIMPLE, OPENED, OPENED.plusDays(10));

        // 1,000,000 × 2.1% × 10/365 = 575.342465… → 575
        assertThat(interest).isEqualByComparingTo("575");
    }

    @Test
    void 최종이자는_원단위_HALF_UP_으로_반올림된다() {
        // 365 × 50% × 3/365 = 정확히 1.5 → HALF_UP 이면 2 (내림이면 1)
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("365"), new BigDecimal("0.5"),
                Compounding.SIMPLE, OPENED, OPENED.plusDays(3));

        assertThat(interest).isEqualByComparingTo("2");
    }

    @Test
    void 최종이자는_소수점을_남기지_않는다() {
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("1000000"), new BigDecimal("0.021"),
                Compounding.SIMPLE, OPENED, OPENED.plusDays(10));

        assertThat(interest.scale()).isZero();
    }

    @Test
    void 월복리는_완전한_개월수만큼_복리로_적립된다() {
        // 월이율 0.3% 로 3회 복리: 12,000,000 × 1.003³ − 12,000,000 = 108,324.324 → 108,324
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("12000000"), new BigDecimal("0.036"),
                Compounding.MONTHLY_COMPOUND, OPENED, LocalDate.of(2026, 4, 1));

        assertThat(interest).isEqualByComparingTo("108324");
    }

    @Test
    void 월복리는_같은_기간_단리보다_이자가_크다() {
        LocalDate closedOn = LocalDate.of(2026, 4, 1);
        BigDecimal principal = new BigDecimal("12000000");
        BigDecimal rate = new BigDecimal("0.036");

        BigDecimal compound = TimeDepositInterest.accrued(principal, rate, Compounding.MONTHLY_COMPOUND, OPENED, closedOn);
        BigDecimal simple = TimeDepositInterest.accrued(principal, rate, Compounding.SIMPLE, OPENED, closedOn);

        assertThat(simple).isEqualByComparingTo("106521");   // 90일 단리
        assertThat(compound).isGreaterThan(simple);          // 재투입된 이자만큼 더 붙는다
    }

    @Test
    void 월복리의_자투리_일수는_같은_이율의_단리로_덧붙는다() {
        // 3개월 복리(2026-04-01 까지) + 자투리 10일 단리 — 자투리의 원금은 복리 적립 후 잔액이다
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("12000000"), new BigDecimal("0.036"),
                Compounding.MONTHLY_COMPOUND, OPENED, LocalDate.of(2026, 4, 11));

        assertThat(interest).isEqualByComparingTo("120267");
    }

    @Test
    void 월복리는_한달을_못채우면_복리회차가_생기지_않아_단리와_같다() {
        LocalDate closedOn = OPENED.plusDays(20);
        BigDecimal principal = new BigDecimal("12000000");
        BigDecimal rate = new BigDecimal("0.036");

        assertThat(TimeDepositInterest.accrued(principal, rate, Compounding.MONTHLY_COMPOUND, OPENED, closedOn))
                .isEqualByComparingTo(
                        TimeDepositInterest.accrued(principal, rate, Compounding.SIMPLE, OPENED, closedOn));
    }

    @Test
    void 나누어떨어지지_않는_월이율에서도_예외없이_계산된다() {
        // 0.07 / 12 = 0.00583333… (무한소수) — 반올림을 명시하지 않은 divide 라면 ArithmeticException 으로 죽는다
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("1000000"), new BigDecimal("0.07"),
                Compounding.MONTHLY_COMPOUND, OPENED, LocalDate.of(2026, 7, 1));

        assertThat(interest).isEqualByComparingTo("35514");
    }

    @Test
    void 이율이_0이면_이자는_0이다() {
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("10000000"), BigDecimal.ZERO,
                Compounding.MONTHLY_COMPOUND, OPENED, LocalDate.of(2027, 1, 1));

        assertThat(interest).isEqualByComparingTo("0");
    }

    @Test
    void 당일_해지면_경과일수가_0이라_이자가_0이다() {
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("10000000"), new BigDecimal("0.035"),
                Compounding.SIMPLE, OPENED, OPENED);

        assertThat(interest).isEqualByComparingTo("0");
    }

    @Test
    void 해지일이_개설일보다_이르면_음수이자_대신_0을_돌려준다() {
        // 애그리거트가 먼저 거절하지만, 계산기 단독으로도 음수 금액 전표를 만들어내지 않아야 한다
        BigDecimal interest = TimeDepositInterest.accrued(
                new BigDecimal("10000000"), new BigDecimal("0.035"),
                Compounding.SIMPLE, OPENED, OPENED.minusDays(5));

        assertThat(interest).isEqualByComparingTo("0");
    }
}
