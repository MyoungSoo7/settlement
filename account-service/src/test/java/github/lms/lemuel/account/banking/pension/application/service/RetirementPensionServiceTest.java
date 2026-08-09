package github.lms.lemuel.account.banking.pension.application.service;

import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.ChangeInvestmentInstructionCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.ContributeCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.OpenPensionCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.PayBenefitCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.SettleInterestCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.StartBenefitCommand;
import github.lms.lemuel.account.banking.pension.application.port.in.RetirementPensionUseCase.WithdrawMidwayCommand;
import github.lms.lemuel.account.banking.pension.application.port.out.LoadRetirementPensionPort;
import github.lms.lemuel.account.banking.pension.application.port.out.SaveRetirementPensionPort;
import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.MidWithdrawalReason;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.banking.pension.domain.PensionStatus;
import github.lms.lemuel.account.banking.pension.domain.PrincipalGuaranteedProduct;
import github.lms.lemuel.account.banking.pension.domain.RetirementPension;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionAccessDeniedException;
import github.lms.lemuel.account.banking.pension.domain.exception.PensionNotFoundException;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 퇴직연금 유스케이스 테스트 — 초점은 <b>GL 로 무엇이 나가는가</b>다.
 *
 * <p>서브원장 규칙 자체는 애그리게이트 테스트가 담당하고, 여기서는 (1) 소유자 대조(IDOR),
 * (2) 도메인이 센 seq 가 전표 자연키 {@code RP-{pensionId}-{seq}} 로 그대로 흘러가는지,
 * (3) 자금 이동이 없는 명령(가입·운용지시·수급개시)이 전표를 만들지 않는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RetirementPensionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** 개설일 — 수급 요건(가입기간 10년)을 만족시키려고 TODAY 기준 16년 전으로 잡는다. */
    private static final LocalDate OPENED = LocalDate.of(2010, 1, 1);
    /** 생년월일 — TODAY 기준 만 60세. 연금·일시금 양쪽의 연령 요건(만 55세)을 넘긴다. */
    private static final LocalDate BIRTH = LocalDate.of(1966, 3, 2);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
    private static final BigDecimal RATE = new BigDecimal("0.035");
    private static final String SUBSCRIBER = "77";
    private static final Long PENSION_ID = 5L;

    @Mock SaveRetirementPensionPort saveRetirementPensionPort;
    @Mock LoadRetirementPensionPort loadRetirementPensionPort;
    @Mock RecordAccountEntryUseCase recordAccountEntryUseCase;

    private RetirementPensionService service;

    /**
     * 서비스는 {@code Clock} 을 생성자로 받는다 — 발생일을 요청에서 받지 않기 때문이다.
     * 목으로 채울 수 없으니(@InjectMocks 는 null 을 넣는다) 고정 시계로 직접 조립한다.
     */
    @BeforeEach
    void setUp() {
        service = new RetirementPensionService(saveRetirementPensionPort, loadRetirementPensionPort,
                recordAccountEntryUseCase, Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE));
    }

    // ---------------------------------------------------------------- 가입·조회

    @Test
    void 가입은_자금이동이_아니라_전표를_만들지_않는다() {
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RetirementPension saved = service.open(new OpenPensionCommand(
                SUBSCRIBER, PensionScheme.DC, "레무엘테크", BIRTH, RATE, "정기예금형", RATE));

        assertThat(saved.getSubscriberId()).isEqualTo(SUBSCRIBER);
        assertThat(saved.getStatus()).isEqualTo(PensionStatus.ACCUMULATING);
        // 개설일은 커맨드가 아니라 서버 시계에서 온다.
        assertThat(saved.getOpenedOn()).isEqualTo(TODAY);
        assertThat(saved.getAccumulatedAmount()).isEqualByComparingTo("0");
        verifyNoInteractions(recordAccountEntryUseCase);
    }

    @Test
    void 단건_조회는_소유자_대조를_거친다() {
        RetirementPension pension = accumulating("1000000", 1L);
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(pension));

        assertThat(service.get(SUBSCRIBER, PENSION_ID)).isSameAs(pension);
    }

    @Test
    void 목록_조회는_토큰의_가입자_식별자로만_필터한다() {
        RetirementPension pension = accumulating("1000000", 1L);
        when(loadRetirementPensionPort.findBySubscriberId(SUBSCRIBER)).thenReturn(List.of(pension));

        assertThat(service.listMine(SUBSCRIBER)).containsExactly(pension);
    }

    // ---------------------------------------------------------------- IDOR·미존재

    @Test
    void 남의_계약을_조작하면_403으로_닫힌다() {
        when(loadRetirementPensionPort.findById(PENSION_ID))
                .thenReturn(Optional.of(accumulating("1000000", 1L)));

        assertThatThrownBy(() -> service.contribute(new ContributeCommand(
                "999", PENSION_ID, new BigDecimal("100000"), ContributionSource.EMPLOYER)))
                .isInstanceOfSatisfying(PensionAccessDeniedException.class,
                        e -> assertThat(e.getPensionId()).isEqualTo(PENSION_ID));

        verify(saveRetirementPensionPort, never()).save(any());
        verifyNoInteractions(recordAccountEntryUseCase);
    }

    @Test
    void 남의_계약은_조회도_403이다() {
        when(loadRetirementPensionPort.findById(PENSION_ID))
                .thenReturn(Optional.of(accumulating("1000000", 1L)));

        assertThatThrownBy(() -> service.get("999", PENSION_ID))
                .isInstanceOf(PensionAccessDeniedException.class);
    }

    @Test
    void 존재하지_않는_계약이면_찾을_수_없다고_알린다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(SUBSCRIBER, PENSION_ID))
                .isInstanceOfSatisfying(PensionNotFoundException.class,
                        e -> assertThat(e.getPensionId()).isEqualTo(PENSION_ID));

        verifyNoInteractions(recordAccountEntryUseCase);
    }

    // ---------------------------------------------------------------- GL 전기

    @Test
    void 부담금_납입은_현금차변_적립금부채대변으로_기표한다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(accumulating("0", 3L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.contribute(new ContributeCommand(
                SUBSCRIBER, PENSION_ID, new BigDecimal("1000000"), ContributionSource.EMPLOYER));

        AccountEntry entry = capturedEntry();
        assertThat(entry.getOwnerType()).isEqualTo(OwnerType.DEPOSITOR);
        assertThat(entry.getOwnerId()).isEqualTo(SUBSCRIBER);
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.RETIREMENT_PENSION_LIABILITY);
        assertThat(entry.getAmount()).isEqualByComparingTo("1000000");
        assertThat(entry.getRefType()).isEqualTo("PENSION_CONTRIBUTION_PAID");
        assertThat(entry.getRefId()).isEqualTo("RP-5-3");
        assertThat(entry.getSourceTopic()).isEqualTo(AccountEntry.SOURCE_BANKING);
    }

    @Test
    void 전표의_seq는_도메인이_센_값을_그대로_쓴다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(accumulating("0", 41L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RetirementPension saved = service.contribute(new ContributeCommand(
                SUBSCRIBER, PENSION_ID, new BigDecimal("1000000"), ContributionSource.EMPLOYER));

        assertThat(capturedEntry().getRefId()).isEqualTo("RP-5-41");
        assertThat(saved.getNextSeq()).isEqualTo(42L);
    }

    @Test
    void 원_미만_금액은_반올림된_금액으로_기표된다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(accumulating("0", 1L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.contribute(new ContributeCommand(
                SUBSCRIBER, PENSION_ID, new BigDecimal("1000.6"), ContributionSource.EMPLOYER));

        assertThat(capturedEntry().getAmount()).isEqualByComparingTo("1001");
    }

    /**
     * 이자 금액은 커맨드가 아니라 애그리게이트에서 나온다 — 직전 확정일을 정확히 365일 전으로
     * 두었으므로 ACT/365 단리는 {@code 1,000,000 × 0.035 × 365/365 = 35,000} 원이 된다.
     */
    @Test
    void 운용수익_확정은_이자비용차변_적립금부채대변으로_기표한다() {
        when(loadRetirementPensionPort.findById(PENSION_ID))
                .thenReturn(Optional.of(settledOneYearAgo("1000000", 2L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settleInterest(new SettleInterestCommand(PENSION_ID));

        AccountEntry entry = capturedEntry();
        // 운영자 경로라도 전표의 owner 는 호출자가 아니라 계약에 적힌 가입자다.
        assertThat(entry.getOwnerId()).isEqualTo(SUBSCRIBER);
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.INTEREST_EXPENSE);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.RETIREMENT_PENSION_LIABILITY);
        assertThat(entry.getAmount()).isEqualByComparingTo("35000");
        assertThat(entry.getRefType()).isEqualTo("PENSION_INTEREST");
        assertThat(entry.getRefId()).isEqualTo("RP-5-2");
    }

    @Test
    void 퇴직급여_지급은_적립금부채차변_현금대변으로_기표한다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(receiving("1000000", 7L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RetirementPension saved = service.payBenefit(new PayBenefitCommand(
                PENSION_ID, new BigDecimal("1000000")));

        AccountEntry entry = capturedEntry();
        // 수급자 식별자를 커맨드로 받지 않으므로, 운영자가 남의 급여를 자기 앞으로 돌릴 자리가 없다.
        assertThat(entry.getOwnerId()).isEqualTo(SUBSCRIBER);
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.RETIREMENT_PENSION_LIABILITY);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getRefType()).isEqualTo("PENSION_BENEFIT_PAID");
        assertThat(entry.getRefId()).isEqualTo("RP-5-7");
        assertThat(saved.getStatus()).isEqualTo(PensionStatus.CLOSED);
    }

    @Test
    void 중도인출도_적립금부채차변_현금대변으로_기표한다() {
        when(loadRetirementPensionPort.findById(PENSION_ID))
                .thenReturn(Optional.of(accumulatingDc("1000000", 9L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.withdrawMidway(new WithdrawMidwayCommand(
                SUBSCRIBER, PENSION_ID, new BigDecimal("300000"),
                MidWithdrawalReason.HOMELESS_HOUSE_PURCHASE));

        AccountEntry entry = capturedEntry();
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.RETIREMENT_PENSION_LIABILITY);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getAmount()).isEqualByComparingTo("300000");
        assertThat(entry.getRefType()).isEqualTo("PENSION_MID_WITHDRAWN");
        assertThat(entry.getRefId()).isEqualTo("RP-5-9");
    }

    // ---------------------------------------------------------------- 전표 없는 명령

    @Test
    void 운용지시_변경은_전표를_만들지_않는다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(accumulating("1000000", 1L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RetirementPension saved = service.changeInvestmentInstruction(new ChangeInvestmentInstructionCommand(
                SUBSCRIBER, PENSION_ID, "원리금보장 국공채형", new BigDecimal("0.028")));

        assertThat(saved.getPrincipalGuaranteedProduct().productName()).isEqualTo("원리금보장 국공채형");
        verifyNoInteractions(recordAccountEntryUseCase);
    }

    @Test
    void 수급_개시는_상태_전이일_뿐_전표를_만들지_않는다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(accumulating("1000000", 1L)));
        when(saveRetirementPensionPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RetirementPension saved = service.startBenefit(new StartBenefitCommand(
                SUBSCRIBER, PENSION_ID, BenefitType.ANNUITY));

        assertThat(saved.getStatus()).isEqualTo(PensionStatus.RECEIVING);
        assertThat(saved.getBenefitType()).isEqualTo(BenefitType.ANNUITY);
        verifyNoInteractions(recordAccountEntryUseCase);
    }

    @Test
    void 도메인이_거절한_명령은_저장도_전기도_하지_않는다() {
        when(loadRetirementPensionPort.findById(PENSION_ID)).thenReturn(Optional.of(accumulating("1000000", 1L)));

        assertThatThrownBy(() -> service.contribute(new ContributeCommand(
                SUBSCRIBER, PENSION_ID, new BigDecimal("100000"), ContributionSource.EMPLOYEE)))
                .hasMessageContaining("EMPLOYEE");

        verify(saveRetirementPensionPort, never()).save(any());
        verifyNoInteractions(recordAccountEntryUseCase);
    }

    // ---------------------------------------------------------------- 픽스처

    private AccountEntry capturedEntry() {
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase).record(captor.capture());
        return captor.getValue();
    }

    /** 적립 중 DB형 계약 — 가입자 부담금을 거절하는 픽스처로도 함께 쓴다. */
    private static RetirementPension accumulating(String accumulated, long nextSeq) {
        return accumulating(PensionScheme.DB, accumulated, nextSeq, null);
    }

    /** 적립 중 DC형 계약 — 중도인출이 허용되는 제도. */
    private static RetirementPension accumulatingDc(String accumulated, long nextSeq) {
        return accumulating(PensionScheme.DC, accumulated, nextSeq, null);
    }

    /** 직전 이자 확정일이 정확히 365일 전인 계약 — 이자 산출을 딱 떨어지게 만든다. */
    private static RetirementPension settledOneYearAgo(String accumulated, long nextSeq) {
        return accumulating(PensionScheme.DB, accumulated, nextSeq, TODAY.minusDays(365));
    }

    private static RetirementPension accumulating(PensionScheme scheme, String accumulated, long nextSeq,
                                                  LocalDate lastInterestSettledOn) {
        return RetirementPension.reconstitute(PENSION_ID, SUBSCRIBER, scheme, "레무엘테크", BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE), PensionStatus.ACCUMULATING,
                OPENED, lastInterestSettledOn, null, null, new BigDecimal(accumulated), nextSeq, List.of());
    }

    private static RetirementPension receiving(String accumulated, long nextSeq) {
        return RetirementPension.reconstitute(PENSION_ID, SUBSCRIBER, PensionScheme.DB, "레무엘테크", BIRTH, RATE,
                new PrincipalGuaranteedProduct("정기예금형", RATE), PensionStatus.RECEIVING,
                OPENED, null, TODAY, BenefitType.LUMP_SUM, new BigDecimal(accumulated), nextSeq, List.of());
    }
}
