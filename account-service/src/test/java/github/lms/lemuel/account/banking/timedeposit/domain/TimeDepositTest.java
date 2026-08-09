package github.lms.lemuel.account.banking.timedeposit.domain;

import github.lms.lemuel.account.banking.timedeposit.domain.exception.InvalidTimeDepositCloseDateException;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.InvalidTimeDepositTermsException;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositAlreadyClosedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정기예금 애그리거트 불변식·해지 전이 검증.
 *
 * <p>여기서 던지는 예외는 전부 {@code AccountDomainException} 하위여야 한다 —
 * 생 {@code IllegalArgumentException} 은 공통 핸들러의 에러코드 계약 밖으로 새는 값이다.
 */
class TimeDepositTest {

    private static final String DEPOSITOR = "42";
    private static final LocalDate OPENED = LocalDate.of(2026, 1, 1);
    private static final BigDecimal PRINCIPAL = new BigDecimal("10000000");
    private static final BigDecimal ANNUAL_RATE = new BigDecimal("0.04");
    private static final BigDecimal EARLY_RATE = new BigDecimal("0.005");

    private static TimeDeposit active() {
        return TimeDeposit.open(DEPOSITOR, "정기예금 12개월", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED);
    }

    // ── 개설 ────────────────────────────────────────────────────────────────

    @Test
    void 개설하면_만기일이_개설일에_예치기간을_더한_날로_확정된다() {
        TimeDeposit deposit = active();

        assertThat(deposit.getMaturityDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(deposit.getOpenedOn()).isEqualTo(OPENED);
        assertThat(deposit.getTermMonths()).isEqualTo(12);
        assertThat(deposit.getStatus()).isEqualTo(TimeDepositStatus.ACTIVE);
        assertThat(deposit.getId()).isNull();
        assertThat(deposit.getProductName()).isEqualTo("정기예금 12개월");
        assertThat(deposit.getCompounding()).isEqualTo(Compounding.SIMPLE);
        assertThat(deposit.getAnnualRate()).isEqualByComparingTo(ANNUAL_RATE);
        assertThat(deposit.getEarlyTerminationRate()).isEqualByComparingTo(EARLY_RATE);
    }

    @Test
    void 개설_직후에는_이자와_지급액이_확정되지_않는다() {
        TimeDeposit deposit = active();

        // 이자는 해지 시점에 단 한 번 확정된다 — 개설 시 0 으로 채워 두지도 않는다
        assertThat(deposit.getSettledInterest()).isNull();
        assertThat(deposit.getPayoutAmount()).isNull();
        assertThat(deposit.getClosedOn()).isNull();
        assertThat(deposit.isClosed()).isFalse();
    }

    @Test
    void 월말_개설은_만기일도_월말로_보정된다() {
        TimeDeposit deposit = TimeDeposit.open(DEPOSITOR, "정기예금 1개월", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 1, LocalDate.of(2026, 1, 31));

        assertThat(deposit.getMaturityDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void 원금이_0이하면_개설이_거부된다() {
        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", BigDecimal.ZERO, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", new BigDecimal("-1"), ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", null, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);
    }

    @Test
    void 원금의_소수_자릿수가_2를_넘으면_거부된다() {
        // GL numeric(19,2) 로 내려가며 조용히 잘리면 수신부채가 어긋난다 — 반올림 대신 거절
        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", new BigDecimal("100.001"), ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);
    }

    @Test
    void 의미없는_후행0은_원금으로_허용된다() {
        TimeDeposit deposit = TimeDeposit.open(DEPOSITOR, "예금", new BigDecimal("1000.000"), ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED);

        assertThat(deposit.getPrincipal()).isEqualByComparingTo("1000");
    }

    @Test
    void 약정이율이_0이상_1미만_밖이면_거부된다() {
        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", PRINCIPAL, BigDecimal.ONE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", PRINCIPAL, new BigDecimal("-0.01"), EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", PRINCIPAL, null, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);
    }

    @Test
    void 중도해지이율이_0이상_1미만_밖이면_거부된다() {
        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", PRINCIPAL, ANNUAL_RATE, new BigDecimal("1.5"),
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);
    }

    @Test
    void 이율_0은_허용된다() {
        TimeDeposit deposit = TimeDeposit.open(DEPOSITOR, "무이자 예금", PRINCIPAL, BigDecimal.ZERO, BigDecimal.ZERO,
                Compounding.SIMPLE, 12, OPENED);

        assertThat(deposit.getAnnualRate()).isEqualByComparingTo("0");
    }

    @Test
    void 예치기간이_0이하면_거부된다() {
        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 0, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);
    }

    @Test
    void 예금주와_상품명과_계산방식과_개설일은_필수다() {
        assertThatThrownBy(() -> TimeDeposit.open("  ", "예금", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(null, "예금", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, " ", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                null, 12, OPENED))
                .isInstanceOf(InvalidTimeDepositTermsException.class);

        assertThatThrownBy(() -> TimeDeposit.open(DEPOSITOR, "예금", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, null))
                .isInstanceOf(InvalidTimeDepositTermsException.class);
    }

    // ── 해지 ────────────────────────────────────────────────────────────────

    @Test
    void 만기해지시_약정이율이_적용된다() {
        TimeDeposit closed = active().closeOnMaturity(LocalDate.of(2027, 1, 1));

        // 10,000,000 × 4% × 365/365 = 400,000
        assertThat(closed.getSettledInterest()).isEqualByComparingTo("400000");
        assertThat(closed.getPayoutAmount()).isEqualByComparingTo("10400000");
        assertThat(closed.getStatus()).isEqualTo(TimeDepositStatus.CLOSED);
        assertThat(closed.getClosedOn()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(closed.isClosed()).isTrue();
    }

    @Test
    void 중도해지시_중도해지이율이_적용된다() {
        TimeDeposit closed = active().closeEarly(LocalDate.of(2026, 4, 11));

        // 100일 예치 · 중도해지이율 0.5%: 10,000,000 × 0.5% × 100/365 = 13,698.63… → 13,699
        assertThat(closed.getSettledInterest()).isEqualByComparingTo("13699");
        assertThat(closed.getPayoutAmount()).isEqualByComparingTo("10013699");
        assertThat(closed.getStatus()).isEqualTo(TimeDepositStatus.CLOSED);
    }

    @Test
    void 중도해지_이자는_같은_날_만기해지보다_적다() {
        LocalDate closedOn = LocalDate.of(2026, 4, 11);

        assertThat(active().closeEarly(closedOn).getSettledInterest())
                .isLessThan(active().closeOnMaturity(closedOn).getSettledInterest());
    }

    @Test
    void 지급액은_언제나_원금더하기_확정이자다() {
        TimeDeposit closed = active().closeEarly(LocalDate.of(2026, 6, 15));

        assertThat(closed.getPayoutAmount())
                .isEqualByComparingTo(closed.getPrincipal().add(closed.getSettledInterest()));
    }

    @Test
    void 당일_해지는_이자가_0이고_원금만_지급된다() {
        TimeDeposit closed = active().closeEarly(OPENED);

        assertThat(closed.getSettledInterest()).isEqualByComparingTo("0");
        assertThat(closed.getPayoutAmount()).isEqualByComparingTo(PRINCIPAL);
    }

    @Test
    void 해지해도_해지전_인스턴스는_그대로_ACTIVE_다() {
        TimeDeposit deposit = active();

        deposit.closeOnMaturity(LocalDate.of(2027, 1, 1));

        // 모든 필드가 final — 해지는 새 인스턴스를 만들 뿐 원본을 건드리지 않는다
        assertThat(deposit.getStatus()).isEqualTo(TimeDepositStatus.ACTIVE);
        assertThat(deposit.getSettledInterest()).isNull();
    }

    @Test
    void 두번_해지하면_거부된다() {
        TimeDeposit closed = active().closeOnMaturity(LocalDate.of(2027, 1, 1));

        assertThatThrownBy(() -> closed.closeOnMaturity(LocalDate.of(2027, 1, 2)))
                .isInstanceOf(TimeDepositAlreadyClosedException.class);
        assertThatThrownBy(() -> closed.closeEarly(LocalDate.of(2027, 1, 2)))
                .isInstanceOf(TimeDepositAlreadyClosedException.class);
    }

    @Test
    void 개설일보다_이른_해지일은_거부된다() {
        TimeDeposit deposit = active();

        assertThatThrownBy(() -> deposit.closeEarly(OPENED.minusDays(1)))
                .isInstanceOf(InvalidTimeDepositCloseDateException.class);
        assertThatThrownBy(() -> deposit.closeOnMaturity(null))
                .isInstanceOf(InvalidTimeDepositCloseDateException.class);
    }

    @Test
    void 해지일_예외는_개설일과_해지일을_보존한다() {
        TimeDeposit deposit = active();
        LocalDate tooEarly = OPENED.minusDays(1);

        assertThatThrownBy(() -> deposit.closeEarly(tooEarly))
                .isInstanceOfSatisfying(InvalidTimeDepositCloseDateException.class, e -> {
                    assertThat(e.getOpenedOn()).isEqualTo(OPENED);
                    assertThat(e.getClosedOn()).isEqualTo(tooEarly);
                });
    }

    @Test
    void 이미해지_예외는_계좌id를_보존한다() {
        TimeDeposit closed = reconstituted(TimeDepositStatus.CLOSED);

        assertThatThrownBy(() -> closed.closeEarly(LocalDate.of(2026, 5, 1)))
                .isInstanceOfSatisfying(TimeDepositAlreadyClosedException.class,
                        e -> assertThat(e.getDepositId()).isEqualTo(7L));
    }

    // ── 조회 보조 ────────────────────────────────────────────────────────────

    @Test
    void 만기여부는_만기일_당일부터_참이다() {
        TimeDeposit deposit = active();

        assertThat(deposit.isMatured(LocalDate.of(2026, 12, 31))).isFalse();
        assertThat(deposit.isMatured(LocalDate.of(2027, 1, 1))).isTrue();
        assertThat(deposit.isMatured(LocalDate.of(2027, 1, 2))).isTrue();
    }

    @Test
    void 소유주_판정은_예금주_식별자_문자열이_같을때만_참이다() {
        TimeDeposit deposit = active();

        assertThat(deposit.ownedBy(DEPOSITOR)).isTrue();
        assertThat(deposit.ownedBy("43")).isFalse();
        assertThat(deposit.ownedBy(null)).isFalse();
    }

    @Test
    void 영속상태에서_복원하면_모든_필드가_그대로_돌아온다() {
        TimeDeposit restored = reconstituted(TimeDepositStatus.CLOSED);

        assertThat(restored.getId()).isEqualTo(7L);
        assertThat(restored.getDepositorId()).isEqualTo(DEPOSITOR);
        assertThat(restored.getProductName()).isEqualTo("정기예금 12개월");
        assertThat(restored.getPrincipal()).isEqualByComparingTo(PRINCIPAL);
        assertThat(restored.getMaturityDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(restored.getClosedOn()).isEqualTo(LocalDate.of(2026, 4, 11));
        assertThat(restored.getSettledInterest()).isEqualByComparingTo("13699");
        assertThat(restored.getPayoutAmount()).isEqualByComparingTo("10013699");
        assertThat(restored.isClosed()).isTrue();
    }

    private static TimeDeposit reconstituted(TimeDepositStatus status) {
        return TimeDeposit.reconstitute(7L, DEPOSITOR, "정기예금 12개월", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED, LocalDate.of(2027, 1, 1), status,
                LocalDate.of(2026, 4, 11), new BigDecimal("13699"), new BigDecimal("10013699"));
    }
}
