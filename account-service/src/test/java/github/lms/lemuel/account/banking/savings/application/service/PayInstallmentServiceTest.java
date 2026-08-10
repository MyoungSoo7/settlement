package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.PayInstallmentUseCase.PayInstallmentCommand;
import github.lms.lemuel.account.banking.savings.application.port.out.LoadInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.application.port.out.SaveInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsInstallment;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;
import github.lms.lemuel.account.banking.savings.domain.exception.DuplicateInstallmentRoundException;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidInstallmentAmountException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsAccessDeniedException;
import github.lms.lemuel.account.banking.savings.domain.exception.SavingsNotFoundException;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayInstallmentServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 서버 시계를 2026-02-11(KST)로 고정 — 2회차 기일(2026-02-01) 기준 10일 연체 시점이다. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-11T00:00:00Z"), KST);
    private static final LocalDate TODAY = LocalDate.of(2026, 2, 11);
    private static final LocalDate OPENED_ON = LocalDate.of(2026, 1, 1);
    private static final BigDecimal MONTHLY = new BigDecimal("100000");

    @Mock
    private LoadInstallmentSavingsPort loadInstallmentSavingsPort;

    @Mock
    private SaveInstallmentSavingsPort saveInstallmentSavingsPort;

    @Mock
    private RecordAccountEntryUseCase recordAccountEntryUseCase;

    private PayInstallmentService service;

    @Captor
    private ArgumentCaptor<AccountEntry> entryCaptor;

    @BeforeEach
    void setUp() {
        service = new PayInstallmentService(loadInstallmentSavingsPort, saveInstallmentSavingsPort,
                recordAccountEntryUseCase, CLOCK);
    }

    private static InstallmentSavings activeSavings(List<SavingsInstallment> installments) {
        return InstallmentSavings.reconstitute(9L, "42", "정액적립 3개월", SavingsType.FIXED,
                MONTHLY, null, new BigDecimal("0.0365"), new BigDecimal("0.0073"),
                3, OPENED_ON, LocalDate.of(2026, 4, 1),
                SavingsStatus.ACTIVE, null, null, null, installments);
    }

    @Test
    void 회차를_납입하면_애그리거트에_회차가_늘고_저장된다() {
        InstallmentSavings savings = activeSavings(List.of());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = service.pay(new PayInstallmentCommand(9L, "42", 1, MONTHLY));

        assertThat(result.getInstallments()).hasSize(1);
        assertThat(result.totalPaidAmount()).isEqualByComparingTo("100000");
        verify(saveInstallmentSavingsPort).save(savings);
    }

    @Test
    void 납입일은_요청이_아니라_서버_시계에서_온다() {
        // 커맨드에 납입일 필드가 없다 — 소급 납입으로 예치일수(=이자)를 부풀리는 경로 차단
        InstallmentSavings savings = activeSavings(List.of());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = service.pay(new PayInstallmentCommand(9L, "42", 2, MONTHLY));

        assertThat(result.getInstallments().get(0).getPaidOn()).isEqualTo(TODAY);
    }

    @Test
    void 회차_납입은_GL_에_현금차변_수신부채대변_전표를_기표한다() {
        InstallmentSavings savings = activeSavings(List.of());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.pay(new PayInstallmentCommand(9L, "42", 2, MONTHLY));

        verify(recordAccountEntryUseCase).record(entryCaptor.capture());
        AccountEntry entry = entryCaptor.getValue();
        assertThat(entry.getOwnerType()).isEqualTo(OwnerType.DEPOSITOR);
        assertThat(entry.getOwnerId()).isEqualTo("42");
        assertThat(entry.getDebitAccount()).isEqualTo(GlAccount.CASH);
        assertThat(entry.getCreditAccount()).isEqualTo(GlAccount.INSTALLMENT_SAVINGS_LIABILITY);
        assertThat(entry.getAmount()).isEqualByComparingTo("100000");
        assertThat(entry.getRefType()).isEqualTo("SAVINGS_INSTALLMENT_PAID");
        assertThat(entry.getRefId()).isEqualTo("SV-9-2");   // 자연키에 회차가 들어가야 회차 단위 멱등이 성립
    }

    @Test
    void 없는_계약이면_찾을_수_없다는_예외가_난다() {
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(new PayInstallmentCommand(9L, "42", 1, MONTHLY)))
                .isInstanceOf(SavingsNotFoundException.class);

        verify(saveInstallmentSavingsPort, never()).save(any());
        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 남의_계약이면_접근이_거절되고_기표도_없다() {
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(activeSavings(List.of())));

        assertThatThrownBy(() -> service.pay(new PayInstallmentCommand(9L, "999", 1, MONTHLY)))
                .isInstanceOf(SavingsAccessDeniedException.class);

        verify(saveInstallmentSavingsPort, never()).save(any());
        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 중복_회차는_도메인이_거절해_전표가_생기지_않는다() {
        InstallmentSavings savings = activeSavings(List.of(
                SavingsInstallment.reconstitute(1L, 1, MONTHLY, OPENED_ON, OPENED_ON, 0)));
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));

        assertThatThrownBy(() -> service.pay(new PayInstallmentCommand(9L, "42", 1, MONTHLY)))
                .isInstanceOf(DuplicateInstallmentRoundException.class);

        verify(saveInstallmentSavingsPort, never()).save(any());
        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 금액이_약정액과_다르면_기표되지_않는다() {
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(activeSavings(List.of())));

        assertThatThrownBy(() -> service.pay(
                new PayInstallmentCommand(9L, "42", 1, new BigDecimal("50000"))))
                .isInstanceOf(InvalidInstallmentAmountException.class);

        verify(recordAccountEntryUseCase, never()).record(any());
    }

    @Test
    void 연체_납입도_그대로_기표된다() {
        // 2회차 기일 2026-02-01, 서버 시계 2026-02-11 → 연체 10일
        InstallmentSavings savings = activeSavings(List.of());
        when(loadInstallmentSavingsPort.findById(9L)).thenReturn(Optional.of(savings));
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = service.pay(new PayInstallmentCommand(9L, "42", 2, MONTHLY));

        assertThat(result.hasOverdueInstallment()).isTrue();
        assertThat(result.getInstallments().get(0).getOverdueDays()).isEqualTo(10);
        verify(recordAccountEntryUseCase).record(entryCaptor.capture());
        // 연체는 이자 계산에만 영향을 준다 — 납입 전표 금액은 실제 납입액 그대로다.
        assertThat(entryCaptor.getValue().getAmount()).isEqualByComparingTo("100000");
    }
}
