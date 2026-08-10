package github.lms.lemuel.account.banking.timedeposit.application.service;

import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.OpenTimeDepositUseCase.OpenTimeDepositCommand;
import github.lms.lemuel.account.banking.timedeposit.application.port.out.LoadTimeDepositPort;
import github.lms.lemuel.account.banking.timedeposit.application.port.out.SaveTimeDepositPort;
import github.lms.lemuel.account.banking.timedeposit.domain.Compounding;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDepositStatus;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositAccessDeniedException;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositNotFoundException;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 정기예금 응용 서비스 — 서브원장 전이와 GL 전기가 정확히 짝을 이루는지 검증.
 *
 * <p>여기서 확인하는 핵심은 "GL 에 무엇이 올라갔는가"다. 계정과목·금액·자연키(refType/refId)까지
 * 값으로 못 박아, 팩토리를 우회한 임의 조립이나 계정과목 뒤바뀜이 조용히 통과하지 못하게 한다.
 */
@ExtendWith(MockitoExtension.class)
class TimeDepositServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String DEPOSITOR = "42";
    private static final Long DEPOSIT_ID = 77L;
    private static final LocalDate OPENED = LocalDate.of(2026, 1, 1);
    private static final BigDecimal PRINCIPAL = new BigDecimal("10000000");
    private static final BigDecimal ANNUAL_RATE = new BigDecimal("0.04");
    private static final BigDecimal EARLY_RATE = new BigDecimal("0.005");

    @Mock
    private SaveTimeDepositPort saveTimeDepositPort;
    @Mock
    private LoadTimeDepositPort loadTimeDepositPort;
    @Mock
    private RecordAccountEntryUseCase recordAccountEntryUseCase;

    private TimeDepositService serviceAt(LocalDate today) {
        return new TimeDepositService(saveTimeDepositPort, loadTimeDepositPort, recordAccountEntryUseCase,
                Clock.fixed(today.atStartOfDay(ZONE).toInstant(), ZONE));
    }

    private static TimeDeposit activeDeposit() {
        return TimeDeposit.reconstitute(DEPOSIT_ID, DEPOSITOR, "정기예금 12개월", PRINCIPAL, ANNUAL_RATE, EARLY_RATE,
                Compounding.SIMPLE, 12, OPENED, LocalDate.of(2027, 1, 1),
                TimeDepositStatus.ACTIVE, null, null, null);
    }

    /** 저장 포트는 채번 결과를 돌려준다 — 해지 경로에서는 인자를 그대로 되돌려주면 id 가 보존된다. */
    private void savePassesThrough() {
        given(saveTimeDepositPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));
    }

    private List<AccountEntry> recordedEntries(int expectedCount) {
        ArgumentCaptor<AccountEntry> captor = ArgumentCaptor.forClass(AccountEntry.class);
        verify(recordAccountEntryUseCase, times(expectedCount)).record(captor.capture());
        return captor.getAllValues();
    }

    // ── 개설 ────────────────────────────────────────────────────────────────

    @Test
    void 개설일은_요청이_아니라_서버_시계가_정한다() {
        given(saveTimeDepositPort.save(any())).willReturn(activeDeposit());
        ArgumentCaptor<TimeDeposit> captor = ArgumentCaptor.forClass(TimeDeposit.class);

        serviceAt(OPENED).open(new OpenTimeDepositCommand(DEPOSITOR, "정기예금 12개월", PRINCIPAL,
                ANNUAL_RATE, EARLY_RATE, Compounding.SIMPLE, 12));

        verify(saveTimeDepositPort).save(captor.capture());
        TimeDeposit toSave = captor.getValue();
        assertThat(toSave.getOpenedOn()).isEqualTo(OPENED);
        assertThat(toSave.getMaturityDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(toSave.getDepositorId()).isEqualTo(DEPOSITOR);
        assertThat(toSave.getStatus()).isEqualTo(TimeDepositStatus.ACTIVE);
    }

    @Test
    void 개설하면_원금_전표가_현금차변_수신부채대변으로_전기된다() {
        given(saveTimeDepositPort.save(any())).willReturn(activeDeposit());

        TimeDeposit opened = serviceAt(OPENED).open(new OpenTimeDepositCommand(DEPOSITOR, "정기예금 12개월",
                PRINCIPAL, ANNUAL_RATE, EARLY_RATE, Compounding.SIMPLE, 12));

        assertThat(opened.getId()).isEqualTo(DEPOSIT_ID);
        AccountEntry entry = recordedEntries(1).get(0);
        assertThat(entry.getOwnerType()).isEqualTo(OwnerType.DEPOSITOR);
        assertThat(entry.getOwnerId()).isEqualTo(DEPOSITOR);
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.TIME_DEPOSIT_LIABILITY);
        assertThat(entry.getAmount()).isEqualByComparingTo(PRINCIPAL);
        assertThat(entry.getRefType()).isEqualTo("TIME_DEPOSIT_OPENED");
        assertThat(entry.getRefId()).isEqualTo("TD-77");   // 자연키는 채번된 id 로만 만들어진다
        assertThat(entry.getSourceTopic()).isEqualTo(AccountEntry.SOURCE_BANKING);
    }

    // ── 만기 해지 ────────────────────────────────────────────────────────────

    @Test
    void 만기해지는_이자전표와_지급전표_두장을_순서대로_올린다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));
        savePassesThrough();

        TimeDeposit closed = serviceAt(LocalDate.of(2027, 1, 1)).closeOnMaturity(DEPOSITOR, DEPOSIT_ID);

        assertThat(closed.getStatus()).isEqualTo(TimeDepositStatus.CLOSED);
        assertThat(closed.getSettledInterest()).isEqualByComparingTo("400000");
        assertThat(closed.getPayoutAmount()).isEqualByComparingTo("10400000");

        List<AccountEntry> entries = recordedEntries(2);

        AccountEntry interest = entries.get(0);
        assertThat(interest.getDebitAccount()).isEqualTo(GlAccount.INTEREST_EXPENSE);
        assertThat(interest.getCreditAccount()).isEqualTo(GlAccount.TIME_DEPOSIT_LIABILITY);
        assertThat(interest.getAmount()).isEqualByComparingTo("400000");
        assertThat(interest.getRefType()).isEqualTo("TIME_DEPOSIT_INTEREST");
        assertThat(interest.getRefId()).isEqualTo("TD-77");
        assertThat(interest.getOwnerType()).isEqualTo(OwnerType.DEPOSITOR);

        AccountEntry payout = entries.get(1);
        assertThat(payout.getDebitAccount()).isEqualTo(GlAccount.TIME_DEPOSIT_LIABILITY);
        assertThat(payout.getCreditAccount()).isEqualTo(GlAccount.CASH);
        assertThat(payout.getAmount()).isEqualByComparingTo("10400000");
        assertThat(payout.getRefType()).isEqualTo("TIME_DEPOSIT_CLOSED");
        assertThat(payout.getRefId()).isEqualTo("TD-77");
        assertThat(payout.getSourceTopic()).isEqualTo(AccountEntry.SOURCE_BANKING);
    }

    @Test
    void 해지는_서브원장_저장이_끝난_뒤에_GL_로_전기된다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));
        savePassesThrough();

        serviceAt(LocalDate.of(2027, 1, 1)).closeOnMaturity(DEPOSITOR, DEPOSIT_ID);

        InOrder order = inOrder(saveTimeDepositPort, recordAccountEntryUseCase);
        order.verify(saveTimeDepositPort).save(any());
        order.verify(recordAccountEntryUseCase, times(2)).record(any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void 수신부채는_개설_이자_지급을_합치면_0으로_닫힌다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));
        savePassesThrough();

        serviceAt(LocalDate.of(2027, 1, 1)).closeOnMaturity(DEPOSITOR, DEPOSIT_ID);
        List<AccountEntry> entries = recordedEntries(2);

        // 개설 시 대변 10,000,000 + 이자 대변 400,000 − 지급 차변 10,400,000 = 0
        BigDecimal liabilityDelta = PRINCIPAL
                .add(entries.get(0).getAmount())
                .subtract(entries.get(1).getAmount());
        assertThat(liabilityDelta).isEqualByComparingTo("0");
    }

    // ── 중도 해지 ────────────────────────────────────────────────────────────

    @Test
    void 중도해지는_중도해지이율로_계산된_이자로_전기된다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));
        savePassesThrough();

        TimeDeposit closed = serviceAt(LocalDate.of(2026, 4, 11)).closeEarly(DEPOSITOR, DEPOSIT_ID);

        assertThat(closed.getSettledInterest()).isEqualByComparingTo("13699");
        List<AccountEntry> entries = recordedEntries(2);
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo("13699");
        assertThat(entries.get(0).getRefType()).isEqualTo("TIME_DEPOSIT_INTEREST");
        assertThat(entries.get(1).getAmount()).isEqualByComparingTo("10013699");
    }

    @Test
    void 확정이자가_0이면_이자전표는_아예_만들지_않는다() {
        // AccountEntry 팩토리는 0 금액을 거부한다 — 그냥 호출하면 해지 전체가 예외로 죽는다
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));
        savePassesThrough();

        TimeDeposit closed = serviceAt(OPENED).closeEarly(DEPOSITOR, DEPOSIT_ID);

        assertThat(closed.getSettledInterest()).isEqualByComparingTo("0");
        AccountEntry only = recordedEntries(1).get(0);
        assertThat(only.getRefType()).isEqualTo("TIME_DEPOSIT_CLOSED");
        assertThat(only.getAmount()).isEqualByComparingTo(PRINCIPAL);
    }

    // ── 소유권 (IDOR) ────────────────────────────────────────────────────────

    @Test
    void 남의_계좌를_해지하려_하면_403_이고_전표도_저장도_없다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));
        TimeDepositService service = serviceAt(LocalDate.of(2027, 1, 1));

        assertThatThrownBy(() -> service.closeOnMaturity("99", DEPOSIT_ID))
                .isInstanceOf(TimeDepositAccessDeniedException.class);

        verify(saveTimeDepositPort, never()).save(any());
        verifyNoInteractions(recordAccountEntryUseCase);
    }

    @Test
    void 남의_계좌를_조회하려_하면_403_이다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));
        TimeDepositService service = serviceAt(OPENED);

        assertThatThrownBy(() -> service.get("99", DEPOSIT_ID))
                .isInstanceOfSatisfying(TimeDepositAccessDeniedException.class,
                        e -> assertThat(e.getDepositId()).isEqualTo(DEPOSIT_ID));
    }

    @Test
    void 없는_계좌를_해지하거나_조회하면_존재하지_않음_예외다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.empty());
        TimeDepositService service = serviceAt(OPENED);

        assertThatThrownBy(() -> service.closeEarly(DEPOSITOR, DEPOSIT_ID))
                .isInstanceOf(TimeDepositNotFoundException.class);
        assertThatThrownBy(() -> service.get(DEPOSITOR, DEPOSIT_ID))
                .isInstanceOf(TimeDepositNotFoundException.class);

        verifyNoInteractions(recordAccountEntryUseCase);
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Test
    void 본인_계좌_단건_조회는_그대로_돌려준다() {
        given(loadTimeDepositPort.findById(DEPOSIT_ID)).willReturn(Optional.of(activeDeposit()));

        TimeDeposit found = serviceAt(OPENED).get(DEPOSITOR, DEPOSIT_ID);

        assertThat(found.getId()).isEqualTo(DEPOSIT_ID);
        assertThat(found.getDepositorId()).isEqualTo(DEPOSITOR);
    }

    @Test
    void 내_계좌_목록은_예금주_기준으로만_조회한다() {
        given(loadTimeDepositPort.findByDepositorId(DEPOSITOR)).willReturn(List.of(activeDeposit()));

        List<TimeDeposit> mine = serviceAt(OPENED).listMine(DEPOSITOR);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getDepositorId()).isEqualTo(DEPOSITOR);
        verify(loadTimeDepositPort).findByDepositorId(DEPOSITOR);
        verifyNoInteractions(recordAccountEntryUseCase);
    }
}
