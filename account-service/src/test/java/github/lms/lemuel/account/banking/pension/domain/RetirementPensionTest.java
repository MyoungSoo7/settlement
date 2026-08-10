package github.lms.lemuel.account.banking.pension.domain;

import github.lms.lemuel.account.banking.pension.domain.exception.BenefitEligibilityNotMetException;
import github.lms.lemuel.account.banking.pension.domain.exception.ContributionSourceNotAllowedException;
import github.lms.lemuel.account.banking.pension.domain.exception.EmployerNameNotAllowedException;
import github.lms.lemuel.account.banking.pension.domain.exception.EmployerNameRequiredException;
import github.lms.lemuel.account.banking.pension.domain.exception.InvalidBirthDateException;
import github.lms.lemuel.account.banking.pension.domain.exception.InvalidInvestmentInstructionException;
import github.lms.lemuel.account.banking.pension.domain.exception.InvalidPensionRateException;
import github.lms.lemuel.account.banking.pension.domain.exception.MidWithdrawalNotPermittedException;
import github.lms.lemuel.account.banking.pension.domain.exception.NonPositivePensionAmountException;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionAmountExceedsAccumulatedException;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionStatusNotAllowedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 퇴직연금 애그리게이트 규칙 테스트.
 *
 * <p>검증의 축은 넷이다 — (1) 제도별 허용 조합, (2) 상태 전이, (3) 금액·seq 규약,
 * (4) <b>클라이언트가 정할 수 없는 값</b>(이자 금액·만 나이·가입기간)이 정말 계약 상태에서만
 * 파생되는지. 특히 (4)는 웹 경계에서 우회되면 요건 검사 전체가 장식이 되므로 경계값까지 못 박는다.
 */
class RetirementPensionTest {

    private static final LocalDate OPENED = LocalDate.of(2020, 1, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
    /** 만 66세(TODAY 기준) — 수급 연령 요건을 이미 넘긴 가입자. */
    private static final LocalDate BIRTH = LocalDate.of(1960, 1, 1);
    private static final BigDecimal RATE = new BigDecimal("0.035");

    private static RetirementPension db() {
        return RetirementPension.open("77", PensionScheme.DB, "레무엘테크", BIRTH, RATE, OPENED, "정기예금형", RATE);
    }

    private static RetirementPension dc() {
        return RetirementPension.open("77", PensionScheme.DC, "레무엘테크", BIRTH, RATE, OPENED, "정기예금형", RATE);
    }

    private static RetirementPension irp() {
        return RetirementPension.open("77", PensionScheme.IRP, null, BIRTH, RATE, OPENED, "정기예금형", RATE);
    }

    // ---------------------------------------------------------------- 가입

    @Test
    void 가입하면_적립중_상태로_적립금0_seq1에서_시작한다() {
        RetirementPension pension = dc();

        assertThat(pension.getId()).isNull();
        assertThat(pension.getSubscriberId()).isEqualTo("77");
        assertThat(pension.getScheme()).isEqualTo(PensionScheme.DC);
        assertThat(pension.getStatus()).isEqualTo(PensionStatus.ACCUMULATING);
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("0");
        assertThat(pension.getNextSeq()).isEqualTo(1L);
        assertThat(pension.getOpenedOn()).isEqualTo(OPENED);
        assertThat(pension.getBirthDate()).isEqualTo(BIRTH);
        assertThat(pension.getLastInterestSettledOn()).isNull();
        assertThat(pension.getBenefitStartedOn()).isNull();
        assertThat(pension.getBenefitType()).isNull();
        assertThat(pension.getTransactions()).isEmpty();
    }

    @Test
    void DB형은_사업장명이_없으면_가입할_수_없다() {
        assertThatThrownBy(() ->
                RetirementPension.open("77", PensionScheme.DB, null, BIRTH, RATE, OPENED, "정기예금형", RATE))
                .isInstanceOf(EmployerNameRequiredException.class);
    }

    @Test
    void DC형은_사업장명이_공백문자뿐이면_없는_것으로_보고_거절한다() {
        assertThatThrownBy(() ->
                RetirementPension.open("77", PensionScheme.DC, "   ", BIRTH, RATE, OPENED, "정기예금형", RATE))
                .isInstanceOf(EmployerNameRequiredException.class);
    }

    @Test
    void IRP형은_사업장명을_주면_가입이_거절된다() {
        assertThatThrownBy(() ->
                RetirementPension.open("77", PensionScheme.IRP, "레무엘테크", BIRTH, RATE, OPENED, "정기예금형", RATE))
                .isInstanceOfSatisfying(EmployerNameNotAllowedException.class,
                        e -> assertThat(e.getScheme()).isEqualTo(PensionScheme.IRP));
    }

    @Test
    void IRP형은_사업장명이_없어야_가입된다() {
        assertThat(irp().getEmployerName()).isNull();
    }

    @Test
    void 사업장명은_앞뒤_공백을_제거해_보관한다() {
        RetirementPension pension = RetirementPension.open(
                "77", PensionScheme.DB, "  레무엘테크  ", BIRTH, RATE, OPENED, "정기예금형", RATE);

        assertThat(pension.getEmployerName()).isEqualTo("레무엘테크");
    }

    @Test
    void 운용이율은_소수점_6자리로_정규화된다() {
        RetirementPension pension = RetirementPension.open(
                "77", PensionScheme.IRP, null, BIRTH, new BigDecimal("0.0350004"), OPENED, "정기예금형", RATE);

        assertThat(pension.getAnnualRate()).isEqualByComparingTo("0.035");
        assertThat(pension.getAnnualRate().scale()).isEqualTo(6);
    }

    @Test
    void 운용이율이_1_이상이면_가입할_수_없다() {
        assertThatThrownBy(() ->
                RetirementPension.open("77", PensionScheme.IRP, null, BIRTH, BigDecimal.ONE, OPENED, "정기예금형", RATE))
                .isInstanceOf(InvalidPensionRateException.class);
    }

    @Test
    void 운용이율이_반올림해서_1이_되면_상한_탈출로_보고_거절한다() {
        assertThatThrownBy(() -> RetirementPension.open(
                "77", PensionScheme.IRP, null, BIRTH, new BigDecimal("0.9999999"), OPENED, "정기예금형", RATE))
                .isInstanceOf(InvalidPensionRateException.class);
    }

    @Test
    void 운용이율이_음수면_가입할_수_없다() {
        assertThatThrownBy(() -> RetirementPension.open(
                "77", PensionScheme.IRP, null, BIRTH, new BigDecimal("-0.001"), OPENED, "정기예금형", RATE))
                .isInstanceOf(InvalidPensionRateException.class);
    }

    // ---------------------------------------------------------------- 생년월일 (수급요건의 근거)

    @Test
    void 생년월일이_없으면_가입할_수_없다() {
        assertThatThrownBy(() ->
                RetirementPension.open("77", PensionScheme.IRP, null, null, RATE, OPENED, "정기예금형", RATE))
                .isInstanceOfSatisfying(InvalidBirthDateException.class,
                        e -> assertThat(e.getBirthDate()).isNull());
    }

    @Test
    void 생년월일이_가입일보다_미래면_가입할_수_없다() {
        LocalDate future = OPENED.plusDays(1);

        assertThatThrownBy(() ->
                RetirementPension.open("77", PensionScheme.IRP, null, future, RATE, OPENED, "정기예금형", RATE))
                .isInstanceOfSatisfying(InvalidBirthDateException.class,
                        e -> assertThat(e.getBirthDate()).isEqualTo(future));
    }

    @Test
    void 가입시점_만_14세면_가입할_수_없다() {
        LocalDate justUnder15 = OPENED.minusYears(15).plusDays(1);

        assertThatThrownBy(() ->
                RetirementPension.open("77", PensionScheme.IRP, null, justUnder15, RATE, OPENED, "정기예금형", RATE))
                .isInstanceOf(InvalidBirthDateException.class);
    }

    @Test
    void 가입시점_만_15세면_가입할_수_있다() {
        LocalDate exactly15 = OPENED.minusYears(15);

        RetirementPension pension =
                RetirementPension.open("77", PensionScheme.IRP, null, exactly15, RATE, OPENED, "정기예금형", RATE);

        assertThat(pension.getBirthDate()).isEqualTo(exactly15);
    }

    @Test
    void 만_나이는_생일이_지나야_한_살_오른다() {
        RetirementPension pension = dc();

        // 1960-01-01 생 — 2026-08-09 기준 만 66세
        assertThat(pension.ageOn(TODAY)).isEqualTo(66);
        assertThat(pension.ageOn(LocalDate.of(2025, 12, 31))).isEqualTo(65);
        assertThat(pension.ageOn(LocalDate.of(2026, 1, 1))).isEqualTo(66);
    }

    @Test
    void 가입기간은_만_단위_절사다() {
        RetirementPension pension = dc();

        // 2020-01-01 개설
        assertThat(pension.subscribedYearsOn(LocalDate.of(2029, 12, 31))).isEqualTo(9);
        assertThat(pension.subscribedYearsOn(LocalDate.of(2030, 1, 1))).isEqualTo(10);
    }

    // ---------------------------------------------------------------- 부담금 납입

    @Test
    void DB형은_사용자_부담금만_받는다() {
        RetirementPension pension = db();

        PensionTransaction tx = pension.contribute(new BigDecimal("1000000"), ContributionSource.EMPLOYER, TODAY);

        assertThat(tx.getType()).isEqualTo(PensionTransactionType.CONTRIBUTION);
        assertThat(tx.getContributionSource()).isEqualTo(ContributionSource.EMPLOYER);
        assertThat(tx.getMidWithdrawalReason()).isNull();
        assertThat(tx.getOccurredOn()).isEqualTo(TODAY);
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    void DB형은_가입자_부담금을_받을_수_없다() {
        assertThatThrownBy(() -> db().contribute(new BigDecimal("1000000"), ContributionSource.EMPLOYEE, TODAY))
                .isInstanceOfSatisfying(ContributionSourceNotAllowedException.class, e -> {
                    assertThat(e.getScheme()).isEqualTo(PensionScheme.DB);
                    assertThat(e.getSource()).isEqualTo(ContributionSource.EMPLOYEE);
                });
    }

    @Test
    void DC형은_사용자_부담금과_가입자_추가납입을_모두_받는다() {
        RetirementPension pension = dc();

        pension.contribute(new BigDecimal("1000000"), ContributionSource.EMPLOYER, TODAY);
        pension.contribute(new BigDecimal("300000"), ContributionSource.EMPLOYEE, TODAY);

        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("1300000");
        assertThat(pension.getTransactions()).extracting(PensionTransaction::getContributionSource)
                .containsExactly(ContributionSource.EMPLOYER, ContributionSource.EMPLOYEE);
    }

    @Test
    void IRP형은_가입자_본인_납입만_받는다() {
        RetirementPension pension = irp();

        pension.contribute(new BigDecimal("500000"), ContributionSource.EMPLOYEE, TODAY);

        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("500000");
    }

    @Test
    void IRP형은_사용자_부담금을_받을_수_없다() {
        assertThatThrownBy(() -> irp().contribute(new BigDecimal("500000"), ContributionSource.EMPLOYER, TODAY))
                .isInstanceOf(ContributionSourceNotAllowedException.class);
    }

    @Test
    void 부담금_seq는_계약단위로_1부터_단조증가한다() {
        RetirementPension pension = dc();

        PensionTransaction first = pension.contribute(new BigDecimal("100000"), ContributionSource.EMPLOYER, OPENED);
        PensionTransaction second = pension.contribute(new BigDecimal("100000"), ContributionSource.EMPLOYER, OPENED);
        PensionTransaction third = pension.settleInterest(OPENED.plusYears(1)).orElseThrow();

        assertThat(first.getSeq()).isEqualTo(1L);
        assertThat(second.getSeq()).isEqualTo(2L);
        assertThat(third.getSeq()).isEqualTo(3L);
        assertThat(pension.getNextSeq()).isEqualTo(4L);
    }

    @Test
    void 금액은_원_단위로_반올림해_적립된다() {
        RetirementPension pension = dc();

        PensionTransaction tx = pension.contribute(new BigDecimal("1000.6"), ContributionSource.EMPLOYER, TODAY);

        assertThat(tx.getAmount()).isEqualByComparingTo("1001");
        assertThat(tx.getAmount().scale()).isZero();
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("1001");
    }

    @Test
    void 반올림하면_0이_되는_금액은_거래로_인정하지_않는다() {
        assertThatThrownBy(() -> dc().contribute(new BigDecimal("0.4"), ContributionSource.EMPLOYER, TODAY))
                .isInstanceOfSatisfying(NonPositivePensionAmountException.class,
                        e -> assertThat(e.getAmount()).isEqualByComparingTo("0.4"));
    }

    @Test
    void 금액이_0이면_납입할_수_없다() {
        assertThatThrownBy(() -> dc().contribute(BigDecimal.ZERO, ContributionSource.EMPLOYER, TODAY))
                .isInstanceOf(NonPositivePensionAmountException.class);
    }

    @Test
    void 금액이_음수면_납입할_수_없다() {
        assertThatThrownBy(() -> dc().contribute(new BigDecimal("-1"), ContributionSource.EMPLOYER, TODAY))
                .isInstanceOf(NonPositivePensionAmountException.class);
    }

    @Test
    void 금액이_null이면_납입할_수_없다() {
        assertThatThrownBy(() -> dc().contribute(null, ContributionSource.EMPLOYER, TODAY))
                .isInstanceOf(NonPositivePensionAmountException.class);
    }

    @Test
    void 수급이_시작된_계약에는_부담금을_납입할_수_없다() {
        RetirementPension pension = receiving();

        assertThatThrownBy(() -> pension.contribute(new BigDecimal("1000"), ContributionSource.EMPLOYER, TODAY))
                .isInstanceOfSatisfying(PensionStatusNotAllowedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(PensionStatus.RECEIVING);
                    assertThat(e.getOperation()).isEqualTo("부담금 납입");
                });
    }

    // ---------------------------------------------------------------- 운용수익 (서버 산출)

    @Test
    void 이자는_적립금과_계약이율과_경과일수로만_산출된다() {
        RetirementPension pension = accumulated("10000000");

        // 10,000,000 × 0.035 × 365/365 = 350,000
        assertThat(pension.accruedInterest(OPENED.plusDays(365))).isEqualByComparingTo("350000");
    }

    @Test
    void 이자는_ACT365_실경과일수_기준이다() {
        RetirementPension pension = accumulated("10000000");

        // 10,000,000 × 0.035 × 1/365 = 958.90… → 959 (원 단위 HALF_UP)
        assertThat(pension.accruedInterest(OPENED.plusDays(1))).isEqualByComparingTo("959");
    }

    @Test
    void 경과일수가_0이면_이자는_0이다() {
        RetirementPension pension = accumulated("10000000");

        assertThat(pension.accruedInterest(OPENED)).isEqualByComparingTo("0");
    }

    @Test
    void 기산일보다_과거_날짜는_이자를_만들지_않는다() {
        RetirementPension pension = accumulated("10000000");

        assertThat(pension.accruedInterest(OPENED.minusDays(30))).isEqualByComparingTo("0");
    }

    @Test
    void 적립금이_0이면_이자도_0이다() {
        RetirementPension pension = dc();

        assertThat(pension.accruedInterest(OPENED.plusDays(365))).isEqualByComparingTo("0");
    }

    @Test
    void 이율이_0인_계약은_이자가_붙지_않는다() {
        RetirementPension pension = RetirementPension.open(
                "77", PensionScheme.IRP, null, BIRTH, BigDecimal.ZERO, OPENED, "무이자형", BigDecimal.ZERO);
        pension.contribute(new BigDecimal("10000000"), ContributionSource.EMPLOYEE, OPENED);

        assertThat(pension.accruedInterest(OPENED.plusDays(365))).isEqualByComparingTo("0");
        assertThat(pension.settleInterest(OPENED.plusDays(365))).isEmpty();
    }

    @Test
    void 운용수익_확정은_산출액을_적립금에_더하고_기산일을_옮긴다() {
        RetirementPension pension = accumulated("10000000");
        LocalDate settledOn = OPENED.plusDays(365);

        Optional<PensionTransaction> tx = pension.settleInterest(settledOn);

        assertThat(tx).isPresent();
        assertThat(tx.orElseThrow().getType()).isEqualTo(PensionTransactionType.INTEREST);
        assertThat(tx.orElseThrow().getAmount()).isEqualByComparingTo("350000");
        assertThat(tx.orElseThrow().getContributionSource()).isNull();
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("10350000");
        assertThat(pension.getLastInterestSettledOn()).isEqualTo(settledOn);
    }

    @Test
    void 두_번째_이자는_직전_확정일부터_기산한다() {
        RetirementPension pension = accumulated("10000000");
        pension.settleInterest(OPENED.plusDays(365));

        // 기산일이 옮겨졌으므로 다시 365일 뒤에도 1년치만 붙는다 (10,350,000 × 0.035 = 362,250)
        Optional<PensionTransaction> second = pension.settleInterest(OPENED.plusDays(730));

        assertThat(second.orElseThrow().getAmount()).isEqualByComparingTo("362250");
        assertThat(pension.getLastInterestSettledOn()).isEqualTo(OPENED.plusDays(730));
    }

    @Test
    void 산출_이자가_0원이면_거래도_기산일_이동도_없다() {
        RetirementPension pension = accumulated("1000");

        // 1,000 × 0.035 × 1/365 = 0.0958… → 0원
        Optional<PensionTransaction> tx = pension.settleInterest(OPENED.plusDays(1));

        assertThat(tx).isEmpty();
        assertThat(pension.getTransactions()).hasSize(1);   // 최초 부담금 1건뿐
        assertThat(pension.getNextSeq()).isEqualTo(2L);
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("1000");
        assertThat(pension.getLastInterestSettledOn()).isNull();
    }

    @Test
    void 수급중에도_잔여_적립금에_운용수익이_붙는다() {
        RetirementPension pension = receiving();

        Optional<PensionTransaction> tx = pension.settleInterest(OPENED.plusDays(365));

        assertThat(tx).isPresent();
        assertThat(pension.getStatus()).isEqualTo(PensionStatus.RECEIVING);
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("1035000");
    }

    @Test
    void 종료된_계약에는_운용수익을_확정할_수_없다() {
        RetirementPension pension = closed();

        assertThatThrownBy(() -> pension.settleInterest(TODAY))
                .isInstanceOfSatisfying(PensionStatusNotAllowedException.class,
                        e -> assertThat(e.getOperation()).isEqualTo("운용수익 확정"));
    }

    // ---------------------------------------------------------------- 운용지시

    @Test
    void 운용지시는_종료_전이면_언제든_변경할_수_있다() {
        RetirementPension pension = receiving();

        pension.changeInvestmentInstruction("원리금보장 국공채형", new BigDecimal("0.028"));

        assertThat(pension.getPrincipalGuaranteedProduct().productName()).isEqualTo("원리금보장 국공채형");
        assertThat(pension.getPrincipalGuaranteedProduct().rate()).isEqualByComparingTo("0.028");
        assertThat(pension.getAnnualRate()).isEqualByComparingTo("0.035");
    }

    @Test
    void 종료된_계약은_운용지시를_변경할_수_없다() {
        RetirementPension pension = closed();

        assertThatThrownBy(() -> pension.changeInvestmentInstruction("원리금보장 국공채형", new BigDecimal("0.028")))
                .isInstanceOfSatisfying(PensionStatusNotAllowedException.class,
                        e -> assertThat(e.getOperation()).isEqualTo("운용지시 변경"));
    }

    @Test
    void 운용지시_상품명이_비어있으면_변경할_수_없다() {
        assertThatThrownBy(() -> dc().changeInvestmentInstruction("  ", new BigDecimal("0.028")))
                .isInstanceOf(InvalidInvestmentInstructionException.class);
    }

    @Test
    void 운용지시_이율도_0이상_1미만이어야_한다() {
        assertThatThrownBy(() -> dc().changeInvestmentInstruction("원리금보장 국공채형", new BigDecimal("1.5")))
                .isInstanceOf(InvalidPensionRateException.class);
    }

    // ---------------------------------------------------------------- 수급 개시 (서버 파생 요건)

    @Test
    void 연금수급은_만55세_가입기간10년이면_개시된다() {
        RetirementPension pension = openedYearsAgo(10, 55);

        pension.startBenefit(BenefitType.ANNUITY, TODAY);

        assertThat(pension.getStatus()).isEqualTo(PensionStatus.RECEIVING);
        assertThat(pension.getBenefitType()).isEqualTo(BenefitType.ANNUITY);
        assertThat(pension.getBenefitStartedOn()).isEqualTo(TODAY);
    }

    @Test
    void 생년월일_기준_만54세는_수급이_거절된다() {
        // 하루만 늦게 태어나도 만 54세 — 요청 바디가 아니라 생년월일이 판정한다
        RetirementPension pension = openedYearsAgoBornOn(20, TODAY.minusYears(55).plusDays(1));

        assertThatThrownBy(() -> pension.startBenefit(BenefitType.LUMP_SUM, TODAY))
                .isInstanceOfSatisfying(BenefitEligibilityNotMetException.class, e -> {
                    assertThat(e.getBenefitType()).isEqualTo(BenefitType.LUMP_SUM);
                    assertThat(e.getAge()).isEqualTo(54);
                });
    }

    @Test
    void 생년월일_기준_만55세는_수급이_개시된다() {
        RetirementPension pension = openedYearsAgoBornOn(20, TODAY.minusYears(55));

        pension.startBenefit(BenefitType.LUMP_SUM, TODAY);

        assertThat(pension.getStatus()).isEqualTo(PensionStatus.RECEIVING);
        assertThat(pension.ageOn(TODAY)).isEqualTo(55);
    }

    @Test
    void 가입_9년11개월이면_연금수급이_거절된다() {
        RetirementPension pension = openedOnDate(TODAY.minusYears(10).plusMonths(1), TODAY.minusYears(60));

        assertThatThrownBy(() -> pension.startBenefit(BenefitType.ANNUITY, TODAY))
                .isInstanceOfSatisfying(BenefitEligibilityNotMetException.class, e -> {
                    assertThat(e.getSubscribedYears()).isEqualTo(9);
                    assertThat(e.getAge()).isEqualTo(60);
                });
    }

    @Test
    void 가입_10년이면_연금수급이_개시된다() {
        RetirementPension pension = openedYearsAgo(10, 60);

        pension.startBenefit(BenefitType.ANNUITY, TODAY);

        assertThat(pension.getStatus()).isEqualTo(PensionStatus.RECEIVING);
        assertThat(pension.subscribedYearsOn(TODAY)).isEqualTo(10);
    }

    @Test
    void 일시금수급은_만55세면_가입기간과_무관하게_개시된다() {
        RetirementPension pension = openedYearsAgo(1, 55);

        pension.startBenefit(BenefitType.LUMP_SUM, TODAY);

        assertThat(pension.getStatus()).isEqualTo(PensionStatus.RECEIVING);
        assertThat(pension.getBenefitType()).isEqualTo(BenefitType.LUMP_SUM);
        assertThat(pension.subscribedYearsOn(TODAY)).isEqualTo(1);
    }

    @Test
    void 가입기간이_길어도_만54세면_일시금도_개시할_수_없다() {
        RetirementPension pension = openedYearsAgo(30, 54);

        assertThatThrownBy(() -> pension.startBenefit(BenefitType.LUMP_SUM, TODAY))
                .isInstanceOf(BenefitEligibilityNotMetException.class);
    }

    @Test
    void 이미_수급중인_계약은_다시_개시할_수_없다() {
        RetirementPension pension = receiving();

        assertThatThrownBy(() -> pension.startBenefit(BenefitType.ANNUITY, TODAY))
                .isInstanceOfSatisfying(PensionStatusNotAllowedException.class,
                        e -> assertThat(e.getOperation()).isEqualTo("수급 개시"));
    }

    // ---------------------------------------------------------------- 급여 지급

    @Test
    void 퇴직급여는_수급중에만_지급된다() {
        assertThatThrownBy(() -> accumulated("1000000").payBenefit(new BigDecimal("100000"), TODAY))
                .isInstanceOfSatisfying(PensionStatusNotAllowedException.class,
                        e -> assertThat(e.getOperation()).isEqualTo("퇴직급여 지급"));
    }

    @Test
    void 퇴직급여_일부_지급은_수급중_상태를_유지한다() {
        RetirementPension pension = receiving();

        PensionTransaction tx = pension.payBenefit(new BigDecimal("400000"), TODAY);

        assertThat(tx.getType()).isEqualTo(PensionTransactionType.BENEFIT);
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("600000");
        assertThat(pension.getStatus()).isEqualTo(PensionStatus.RECEIVING);
    }

    @Test
    void 적립금이_0이_되는_지급회차에_계약이_종료된다() {
        RetirementPension pension = receiving();

        pension.payBenefit(new BigDecimal("600000"), TODAY);
        pension.payBenefit(new BigDecimal("400000"), TODAY);

        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("0");
        assertThat(pension.getStatus()).isEqualTo(PensionStatus.CLOSED);
    }

    @Test
    void 적립금을_초과하는_급여는_지급할_수_없다() {
        RetirementPension pension = receiving();

        assertThatThrownBy(() -> pension.payBenefit(new BigDecimal("1000001"), TODAY))
                .isInstanceOfSatisfying(PensionAmountExceedsAccumulatedException.class, e -> {
                    assertThat(e.getRequested()).isEqualByComparingTo("1000001");
                    assertThat(e.getAccumulated()).isEqualByComparingTo("1000000");
                });
    }

    @Test
    void 종료된_계약에는_급여를_더_지급할_수_없다() {
        RetirementPension pension = closed();

        assertThatThrownBy(() -> pension.payBenefit(new BigDecimal("1"), TODAY))
                .isInstanceOf(PensionStatusNotAllowedException.class);
    }

    // ---------------------------------------------------------------- 중도인출

    @Test
    void DB형은_중도인출이_허용되지_않는다() {
        RetirementPension pension = db();
        pension.contribute(new BigDecimal("1000000"), ContributionSource.EMPLOYER, TODAY);

        assertThatThrownBy(() -> pension.withdrawMidway(new BigDecimal("100000"),
                MidWithdrawalReason.HOMELESS_HOUSE_PURCHASE, TODAY))
                .isInstanceOfSatisfying(MidWithdrawalNotPermittedException.class,
                        e -> assertThat(e.getScheme()).isEqualTo(PensionScheme.DB));
        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    void DB형은_어떤_법정사유로도_중도인출할_수_없다() {
        for (MidWithdrawalReason reason : MidWithdrawalReason.values()) {
            RetirementPension pension = db();
            pension.contribute(new BigDecimal("1000000"), ContributionSource.EMPLOYER, TODAY);

            assertThatThrownBy(() -> pension.withdrawMidway(new BigDecimal("100000"), reason, TODAY))
                    .as("사유 %s", reason)
                    .isInstanceOf(MidWithdrawalNotPermittedException.class);
        }
    }

    @Test
    void DC형은_법정사유_6종_모두로_중도인출할_수_있다() {
        assertThat(MidWithdrawalReason.values()).hasSize(6);

        for (MidWithdrawalReason reason : MidWithdrawalReason.values()) {
            RetirementPension pension = accumulated("1000000");

            PensionTransaction tx = pension.withdrawMidway(new BigDecimal("100000"), reason, TODAY);

            assertThat(tx.getType()).as("사유 %s", reason).isEqualTo(PensionTransactionType.MID_WITHDRAWAL);
            assertThat(tx.getMidWithdrawalReason()).isEqualTo(reason);
            assertThat(tx.getContributionSource()).isNull();
            assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("900000");
        }
    }

    @Test
    void IRP형도_법정사유_중도인출이_가능하다() {
        RetirementPension pension = irp();
        pension.contribute(new BigDecimal("1000000"), ContributionSource.EMPLOYEE, TODAY);

        pension.withdrawMidway(new BigDecimal("250000"), MidWithdrawalReason.NATURAL_DISASTER, TODAY);

        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("750000");
    }

    @Test
    void 중도인출로_잔액이_0이_되어도_계약은_닫히지_않는다() {
        RetirementPension pension = accumulated("1000000");

        pension.withdrawMidway(new BigDecimal("1000000"), MidWithdrawalReason.BANKRUPTCY, TODAY);

        assertThat(pension.getAccumulatedAmount()).isEqualByComparingTo("0");
        assertThat(pension.getStatus()).isEqualTo(PensionStatus.ACCUMULATING);
    }

    @Test
    void 적립금을_초과하는_중도인출은_거절된다() {
        RetirementPension pension = accumulated("1000000");

        assertThatThrownBy(() -> pension.withdrawMidway(new BigDecimal("1000001"),
                MidWithdrawalReason.PERSONAL_REHABILITATION, TODAY))
                .isInstanceOf(PensionAmountExceedsAccumulatedException.class);
    }

    @Test
    void 수급중_계약은_중도인출할_수_없다() {
        RetirementPension pension = receiving();

        assertThatThrownBy(() ->
                pension.withdrawMidway(new BigDecimal("1000"), MidWithdrawalReason.MINISTER_NOTICE, TODAY))
                .isInstanceOfSatisfying(PensionStatusNotAllowedException.class,
                        e -> assertThat(e.getOperation()).isEqualTo("중도인출"));
    }

    // ---------------------------------------------------------------- 기타

    @Test
    void 거래이력은_밖에서_변경할_수_없다() {
        RetirementPension pension = accumulated("1000000");

        List<PensionTransaction> transactions = pension.getTransactions();

        assertThat(transactions).hasSize(1);
        assertThatThrownBy(transactions::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 소유자_판정은_가입자_식별자가_같을_때만_참이다() {
        RetirementPension pension = dc();

        assertThat(pension.isOwnedBy("77")).isTrue();
        assertThat(pension.isOwnedBy("78")).isFalse();
        assertThat(pension.isOwnedBy(null)).isFalse();
    }

    @Test
    void 복원한_계약은_거래이력이_null이어도_빈_목록이_된다() {
        RetirementPension pension = RetirementPension.reconstitute(
                9L, "77", PensionScheme.IRP, null, BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE),
                PensionStatus.ACCUMULATING, OPENED, null, null, null,
                new BigDecimal("500000"), 4L, null);

        assertThat(pension.getId()).isEqualTo(9L);
        assertThat(pension.getTransactions()).isEmpty();
        assertThat(pension.getNextSeq()).isEqualTo(4L);
        assertThat(pension.getBirthDate()).isEqualTo(BIRTH);
    }

    @Test
    void 복원한_계약은_저장된_기산일부터_이자를_계산한다() {
        LocalDate lastSettled = OPENED.plusYears(1);
        RetirementPension pension = RetirementPension.reconstitute(
                9L, "77", PensionScheme.IRP, null, BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE),
                PensionStatus.ACCUMULATING, OPENED, lastSettled, null, null,
                new BigDecimal("10000000"), 4L, List.of());

        // 개설일이 아니라 저장된 기산일부터 365일 → 350,000
        assertThat(pension.accruedInterest(lastSettled.plusDays(365))).isEqualByComparingTo("350000");
    }

    @Test
    void 복원한_계약은_이어지는_seq로_거래를_적재한다() {
        RetirementPension pension = RetirementPension.reconstitute(
                9L, "77", PensionScheme.IRP, null, BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE),
                PensionStatus.ACCUMULATING, OPENED, null, null, null,
                new BigDecimal("500000"), 4L,
                List.of(PensionTransaction.reconstitute(1L, 3L, PensionTransactionType.CONTRIBUTION,
                        new BigDecimal("500000"), ContributionSource.EMPLOYEE, null, OPENED)));

        PensionTransaction tx = pension.contribute(new BigDecimal("100000"), ContributionSource.EMPLOYEE, TODAY);

        assertThat(tx.getSeq()).isEqualTo(4L);
        assertThat(tx.getId()).isNull();
        assertThat(pension.getTransactions()).hasSize(2);
    }

    // ---------------------------------------------------------------- 픽스처

    /** 적립금이 쌓인 DC 계약 (거래 1건, 개설일에 납입 → 이자 기산 검증용). */
    private static RetirementPension accumulated(String amount) {
        RetirementPension pension = dc();
        pension.contribute(new BigDecimal(amount), ContributionSource.EMPLOYER, OPENED);
        return pension;
    }

    /** 적립금 1,000,000 원으로 수급이 시작된 DC 계약(만 66세라 일시금 요건 충족). */
    private static RetirementPension receiving() {
        RetirementPension pension = accumulated("1000000");
        pension.startBenefit(BenefitType.LUMP_SUM, OPENED);
        return pension;
    }

    /** 전액 지급으로 종료된 DC 계약. */
    private static RetirementPension closed() {
        RetirementPension pension = receiving();
        pension.payBenefit(new BigDecimal("1000000"), OPENED);
        return pension;
    }

    /** TODAY 기준 가입 {@code years} 년 + 만 {@code age} 세인 적립 중 DC 계약. */
    private static RetirementPension openedYearsAgo(int years, int age) {
        return openedOnDate(TODAY.minusYears(years), TODAY.minusYears(age));
    }

    /** TODAY 기준 가입 {@code years} 년 + 지정 생년월일인 적립 중 DC 계약. */
    private static RetirementPension openedYearsAgoBornOn(int years, LocalDate birthDate) {
        return openedOnDate(TODAY.minusYears(years), birthDate);
    }

    private static RetirementPension openedOnDate(LocalDate openedOn, LocalDate birthDate) {
        RetirementPension pension = RetirementPension.open(
                "77", PensionScheme.DC, "레무엘테크", birthDate, RATE, openedOn, "정기예금형", RATE);
        pension.contribute(new BigDecimal("1000000"), ContributionSource.EMPLOYER, openedOn);
        return pension;
    }
}
