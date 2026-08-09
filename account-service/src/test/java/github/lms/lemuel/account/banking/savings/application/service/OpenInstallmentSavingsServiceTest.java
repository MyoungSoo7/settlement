package github.lms.lemuel.account.banking.savings.application.service;

import github.lms.lemuel.account.banking.savings.application.port.in.OpenInstallmentSavingsUseCase.OpenInstallmentSavingsCommand;
import github.lms.lemuel.account.banking.savings.application.port.out.SaveInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;
import github.lms.lemuel.account.banking.savings.domain.exception.InvalidSavingsTermsException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenInstallmentSavingsServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 서버 시계를 2026-01-01(KST)로 고정 — 개설일·만기일이 전부 여기서 파생된다. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), KST);

    @Mock
    private SaveInstallmentSavingsPort saveInstallmentSavingsPort;

    private OpenInstallmentSavingsService service;

    @Captor
    private ArgumentCaptor<InstallmentSavings> savingsCaptor;

    @BeforeEach
    void setUp() {
        service = new OpenInstallmentSavingsService(saveInstallmentSavingsPort, CLOCK);
    }

    private static OpenInstallmentSavingsCommand fixedCommand() {
        return new OpenInstallmentSavingsCommand("42", "정액적립 3개월", SavingsType.FIXED,
                new BigDecimal("100000"), null, new BigDecimal("0.0365"), new BigDecimal("0.0073"), 3);
    }

    @Test
    void 개설하면_도메인이_만든_계약을_그대로_저장한다() {
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = service.open(fixedCommand());

        verify(saveInstallmentSavingsPort).save(savingsCaptor.capture());
        InstallmentSavings passed = savingsCaptor.getValue();
        assertThat(passed.getDepositorId()).isEqualTo("42");
        assertThat(passed.getProductName()).isEqualTo("정액적립 3개월");
        assertThat(passed.getSavingsType()).isEqualTo(SavingsType.FIXED);
        assertThat(passed.getMonthlyAmount()).isEqualByComparingTo("100000");
        assertThat(passed.getPaymentLimit()).isNull();
        assertThat(passed.getStatus()).isEqualTo(SavingsStatus.ACTIVE);
        assertThat(result).isSameAs(passed);
    }

    @Test
    void 개설일은_요청이_아니라_서버_시계에서_온다() {
        // 커맨드에 날짜 필드가 아예 없다 — 소급 개설로 만기일·회차 기일을 통째로 옮기는 경로 차단
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = service.open(fixedCommand());

        assertThat(result.getOpenedOn()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.getMaturityDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void 자유적립식도_같은_경로로_개설된다() {
        when(saveInstallmentSavingsPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InstallmentSavings result = service.open(new OpenInstallmentSavingsCommand(
                "42", "자유적립 6개월", SavingsType.FLEXIBLE, null, new BigDecimal("500000"),
                new BigDecimal("0.03"), new BigDecimal("0.005"), 6));

        assertThat(result.getSavingsType()).isEqualTo(SavingsType.FLEXIBLE);
        assertThat(result.getPaymentLimit()).isEqualByComparingTo("500000");
        assertThat(result.getMonthlyAmount()).isNull();
        assertThat(result.getMaturityDate()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void 계약조건이_틀리면_저장을_시도조차_하지_않는다() {
        OpenInstallmentSavingsCommand invalid = new OpenInstallmentSavingsCommand(
                "42", "정액", SavingsType.FIXED, null, null,
                new BigDecimal("0.0365"), new BigDecimal("0.0073"), 3);

        assertThatThrownBy(() -> service.open(invalid))
                .isInstanceOf(InvalidSavingsTermsException.class);

        verify(saveInstallmentSavingsPort, never()).save(any());
    }
}
