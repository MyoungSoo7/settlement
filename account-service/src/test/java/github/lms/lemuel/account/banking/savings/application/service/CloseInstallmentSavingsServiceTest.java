package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.CloseInstallmentSavingsUseCase.CloseInstallmentSavingsCommand;
import github.lms.lemuel.account.banking.savings.application.port.out.LoadInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.application.port.out.SaveInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsInstallment;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidSavingsTermsException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAccessDeniedException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAlreadyClosedException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsNotFoundException;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해지 서비스 단위 테스트.
 *
 * <p>해지일은 커맨드가 아니라 서버 시계에서 오므로, 시나리오마다 {@link Clock#fixed}로 "오늘"을 옮겨
 * 만기 시점·중도 시점을 만든다. {@code Instant.parse("...T00:00:00Z")} 는 KST 로 같은 날 09:00 이라
 * 문자열의 날짜가 그대로 그날의 {@code LocalDate} 가 된다.
 */
@ExtendWith(MockitoExtension.class)
class CloseInstallmentSavingsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate OPENED_ON = LocalDate.of(2026, 1, 1);
    private static final LocalDate MATURITY = LocalDate.of(2026, 4, 1);
    private static final BigDecimal MONTHLY = new BigDecimal("100000");

    @Mock
    private LoadInstallmentSavingsPort loadInstallmentSavingsPort;

    @Mock
    private SaveInstallmentSavingsPort saveInstallmentSavingsPort;

    @Mock
    private RecordAccountEntryUseCase recordAccountEntryUseCase;

    @Captor
    private ArgumentCaptor<AccountEntry> entryCaptor;

    /** "오늘"이 {@code isoDate} 인 서비스. */
    private CloseInstallmentSavingsService serviceOn(String isoDate) {
        return new CloseInstallmentSavingsService(loadInstallmentSavingsPort, saveInstallmentSavingsPort,
                recordAccountEntryUseCase, Clock.fixed(Instant.parse(isoDate + "T00:00:00Z"), KST));
    }

    private static SavingsInstallment installment(int round, LocalDate paidOn) {
        LocalDate due = OPENED_ON.plusMonths(round - 1L);
        return SavingsInstallment.reconstitute((long) round, round, MONTHLY, due, paidOn, 0);
    }

    private static InstallmentSavings activeSavings(BigDecimal annualRate,
                                                    List<SavingsInstallment> installments) {
        return InstallmentSavings.reconstitute(9L, "42", "정액적립 3개월", SavingsType.FIXED,
                MONTHLY, null, annualRate, new BigDecimal("0.0073"),
                3, OPENED_ON, MATURITY, SavingsStatus.ACTIVE, null, null, null, installments);
    }

    private static List<SavingsInstallment> threeOnTimeRounds() {
        return List.of(installment(1, LocalDate.of(2026, 1, 1)),
                installment(2, LocalDate.of(2026, 2, 1)),
                installment(3, LocalDate.of(2026, 3, 1)));
    }

    @Test
    void 만기해지는_이자확정_전표와_원리금지급_전표를_순서대로_기표한다() {
        InstallmentSavings savings = activeSavings(new BigDecimal("0.0365"), threeOnTimeRounds());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = serviceOn("2026-04-01")
                .closeOnMaturity(new CloseInstallmentSavingsCommand(9L, "42"));

        assertThat(result.getStatus()).isEqualTo(SavingsStatus.CLOSED);
        assertThat(result.getClosedOn()).isEqualTo(MATURITY);
        assertThat(result.getSettledInterest()).isEqualByComparingTo("1800");
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("301800");

        verify(recordAccountEntryUseCase, times(2)).record(entryCaptor.capture());
        List<AccountEntry> entries = entryCaptor.getAllValues();

        AccountEntry interest = entries.get(0);
        assertThat(interest.getRefType()).isEqualTo("SAVINGS_INTEREST");
        assertThat(interest.getRefId()).isEqualTo("SV-9");
        assertThat(interest.getOwnerType()).isEqualTo(OwnerType.DEPOSITOR);
        assertThat(interest.getOwnerId()).isEqualTo("42");
        assertThat(interest.getDebitAccount()).isEqualTo(GlAccount.INTEREST_EXPENSE);
        assertThat(interest.getCreditAccount()).isEqualTo(GlAccount.INSTALLMENT_SAVINGS_LIABILITY);
        assertThat(interest.getAmount()).isEqualByComparingTo("1800");

        AccountEntry closed = entries.get(1);
        assertThat(closed.getRefType()).isEqualTo("SAVINGS_CLOSED");
        assertThat(closed.getRefId()).isEqualTo("SV-9");
        assertThat(closed.getDebitAccount()).isEqualTo(GlAccount.INSTALLMENT_SAVINGS_LIABILITY);
        assertThat(closed.getCreditAccount()).isEqualTo(GlAccount.CASH);
        // 지급액 = 이자 인식으로 늘어난 수신부채 전액 — 이 금액이라야 부채가 0 으로 닫힌다
        assertThat(closed.getAmount()).isEqualByComparingTo("301800");
    }

    @Test
    void 만기_전에는_만기해지가_거절된다() {
        // 해지일을 요청으로 받지 않으니 "만기 전 만기해지"는 서버 시계로만 판정된다
        when(loadInstallmentSavingsPort.findById(9L))
                .thenReturn(Optional.of(activeSavings(new BigDecimal("0.0365"), threeOnTimeRounds())));

        assertThatThrownBy(() -> serviceOn("2026-03-31")
                .closeOnMaturity(new CloseInstallmentSavingsCommand(9L, "42")))
                .isInstanceOf(InvalidSavingsTermsException.class);

        verify(saveInstallmentSavingsPort, never()).save(any());
        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 중도해지는_중도해지이율로_계산된_이자를_기표한다() {
        InstallmentSavings savings = activeSavings(new BigDecimal("0.0365"),
                List.of(installment(1, LocalDate.of(2026, 1, 1)),
                        installment(2, LocalDate.of(2026, 2, 1))));
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = serviceOn("2026-03-01")
                .closeEarly(new CloseInstallmentSavingsCommand(9L, "42"));

        assertThat(result.getClosedOn()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(result.getSettledInterest()).isEqualByComparingTo("174");   // 0.0073 적용
        assertThat(result.getPayoutAmount()).isEqualByComparingTo("200174");

        verify(recordAccountEntryUseCase, times(2)).record(entryCaptor.capture());
        assertThat(entryCaptor.getAllValues().get(0).getAmount()).isEqualByComparingTo("174");
        assertThat(entryCaptor.getAllValues().get(1).getAmount()).isEqualByComparingTo("200174");
    }

    @Test
    void 해지일을_미래로_밀어_이자를_부풀릴_수_없다() {
        // 같은 계약을 하루 뒤에 해지하면 이자가 늘지만, 그 "하루 뒤"를 정하는 건 오직 서버 시계다
        when(loadInstallmentSavingsPort.findById(9L))
                .thenReturn(Optional.of(activeSavings(new BigDecimal("0.0365"),
                        List.of(installment(1, LocalDate.of(2026, 1, 1))))))
                .thenReturn(Optional.of(activeSavings(new BigDecimal("0.0365"),
                        List.of(installment(1, LocalDate.of(2026, 1, 1))))));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal onMarch1 = serviceOn("2026-03-01")
                .closeEarly(new CloseInstallmentSavingsCommand(9L, "42")).getSettledInterest();
        BigDecimal onMarch31 = serviceOn("2026-03-31")
                .closeEarly(new CloseInstallmentSavingsCommand(9L, "42")).getSettledInterest();

        assertThat(onMarch1).isEqualByComparingTo("118");    // 59일 × 100,000 × 0.00002
        assertThat(onMarch31).isEqualByComparingTo("178");   // 89일
        assertThat(onMarch31).isGreaterThan(onMarch1);
    }

    @Test
    void 이자가_0이면_이자전표는_아예_만들지_않는다() {
        // AccountEntry 팩토리가 비양수 금액을 거절하므로 0 원 전표는 생성 자체가 사고다.
        InstallmentSavings savings = activeSavings(BigDecimal.ZERO, threeOnTimeRounds());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = serviceOn("2026-04-01")
                .closeOnMaturity(new CloseInstallmentSavingsCommand(9L, "42"));

        assertThat(result.getSettledInterest()).isEqualByComparingTo("0");
        verify(recordAccountEntryUseCase, times(1)).record(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getRefType()).isEqualTo("SAVINGS_CLOSED");
        assertThat(entryCaptor.getValue().getAmount()).isEqualByComparingTo("300000");
    }

    @Test
    void 한_회차도_없는_계약의_해지는_전표가_하나도_없다() {
        InstallmentSavings savings = activeSavings(new BigDecimal("0.0365"), List.of());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = serviceOn("2026-02-01")
                .closeEarly(new CloseInstallmentSavingsCommand(9L, "42"));

        assertThat(result.getPayoutAmount()).isEqualByComparingTo("0");
        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 없는_계약은_만기해지도_중도해지도_할_수_없다() {
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceOn("2026-04-01")
                .closeOnMaturity(new CloseInstallmentSavingsCommand(9L, "42")))
                .isInstanceOf(SavingsNotFoundException.class);
        assertThatThrownBy(() -> serviceOn("2026-02-01")
                .closeEarly(new CloseInstallmentSavingsCommand(9L, "42")))
                .isInstanceOf(SavingsNotFoundException.class);

        verify(saveInstallmentSavingsPort, never()).save(any());
        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 남의_계약은_해지할_수_없다() {
        when(loadInstallmentSavingsPort.findById(9L))
                .thenReturn(Optional.of(activeSavings(new BigDecimal("0.0365"), threeOnTimeRounds())));

        assertThatThrownBy(() -> serviceOn("2026-04-01")
                .closeOnMaturity(new CloseInstallmentSavingsCommand(9L, "999")))
                .isInstanceOf(SavingsAccessDeniedException.class);

        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 이미_해지된_계약은_다시_해지할_수_없다() {
        InstallmentSavings closed = InstallmentSavings.reconstitute(9L, "42", "정액적립 3개월",
                SavingsType.FIXED, MONTHLY, null, new BigDecimal("0.0365"), new BigDecimal("0.0073"),
                3, OPENED_ON, MATURITY, SavingsStatus.CLOSED, MATURITY,
                new BigDecimal("1800"), new BigDecimal("301800"), threeOnTimeRounds());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> serviceOn("2026-04-01")
                .closeOnMaturity(new CloseInstallmentSavingsCommand(9L, "42")))
                .isInstanceOf(SavingsAlreadyClosedException.class);

        verify(saveInstallmentSavingsPort, never()).save(any());
        verify(recordAccountEntryUseCase, never()).record(any());
    }
}
