package github.lms.lemuel.account.banking.savings.adapter.in.web;

import github.lms.lemuel.account.banking.savings.adapter.in.web.dto.InstallmentSavingsResponse;
import github.lms.lemuel.account.banking.savings.adapter.in.web.dto.OpenInstallmentSavingsRequest;
import github.lms.lemuel.account.banking.savings.adapter.in.web.dto.PayInstallmentRequest;
import github.lms.lemuel.account.banking.savings.application.port.in.CloseInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.CloseInstallmentSavingsUseCase.CloseInstallmentSavingsCommand;
import github.lms.lemuel.account.banking.savings.application.port.in.OpenInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.OpenInstallmentSavingsUseCase.OpenInstallmentSavingsCommand;
import github.lms.lemuel.account.banking.savings.application.port.in.PayInstallmentUseCase;
import github.lms.lemuel.account.banking.savings.application.port.in.PayInstallmentUseCase.PayInstallmentCommand;
import github.lms.lemuel.account.banking.savings.application.port.in.QueryInstallmentSavingsUseCase;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsInstallment;
import github.lms.lemuel.account.banking.savings.domain.SavingsStatus;
import github.lms.lemuel.account.banking.savings.domain.SavingsType;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 컨트롤러 단위 테스트 — MockMvc 없이 유스케이스 포트에 대해 순수 Mockito 로 검증한다.
 *
 * <p>여기서 지키는 계약은 세 가지다: (1) 예금주 식별자는 <b>오직 JWT 주체</b>에서 나온다,
 * (2) 요청 본문·경로의 어떤 값도 그 자리를 대신할 수 없다, (3) <b>날짜 필드가 어디에도 없다</b> —
 * 개설일·납입일·해지일은 응용 서비스의 서버 시계가 정하므로 이 표면은 날짜를 아예 옮기지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class InstallmentSavingsControllerTest {

    private static final LocalDate OPENED_ON = LocalDate.of(2026, 1, 1);
    private static final BigDecimal MONTHLY = new BigDecimal("100000");

    @Mock
    private OpenInstallmentSavingsUseCase openInstallmentSavingsUseCase;

    @Mock
    private PayInstallmentUseCase payInstallmentUseCase;

    @Mock
    private CloseInstallmentSavingsUseCase closeInstallmentSavingsUseCase;

    @Mock
    private QueryInstallmentSavingsUseCase queryInstallmentSavingsUseCase;

    @InjectMocks
    private InstallmentSavingsController controller;

    @Captor
    private ArgumentCaptor<OpenInstallmentSavingsCommand> openCaptor;

    @Captor
    private ArgumentCaptor<PayInstallmentCommand> payCaptor;

    @Captor
    private ArgumentCaptor<CloseInstallmentSavingsCommand> closeCaptor;

    private static Authentication authentication(Long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "depositor@lemuel.co.kr", "USER"), null);
    }

    private static InstallmentSavings savings() {
        return InstallmentSavings.reconstitute(9L, "42", "정액적립 3개월", SavingsType.FIXED,
                MONTHLY, null, new BigDecimal("0.0365"), new BigDecimal("0.0073"),
                3, OPENED_ON, LocalDate.of(2026, 4, 1), SavingsStatus.ACTIVE, null, null, null,
                List.of(SavingsInstallment.reconstitute(1L, 1, MONTHLY, OPENED_ON,
                        OPENED_ON.plusDays(2), 2)));
    }

    private static OpenInstallmentSavingsRequest openRequest() {
        return new OpenInstallmentSavingsRequest("정액적립 3개월", SavingsType.FIXED,
                MONTHLY, null, new BigDecimal("0.0365"), new BigDecimal("0.0073"), 3);
    }

    @Test
    void 개설은_JWT_주체의_userId_를_예금주로_넘긴다() {
        when(openInstallmentSavingsUseCase.open(any())).thenReturn(savings());

        ResponseEntity<InstallmentSavingsResponse> response =
                controller.open(openRequest(), authentication(42L));

        verify(openInstallmentSavingsUseCase).open(openCaptor.capture());
        OpenInstallmentSavingsCommand command = openCaptor.getValue();
        assertThat(command.depositorId()).isEqualTo("42");
        assertThat(command.productName()).isEqualTo("정액적립 3개월");
        assertThat(command.savingsType()).isEqualTo(SavingsType.FIXED);
        assertThat(command.monthlyAmount()).isEqualByComparingTo(MONTHLY);
        assertThat(command.termMonths()).isEqualTo(3);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(9L);
        assertThat(response.getBody().depositorId()).isEqualTo("42");
        assertThat(response.getBody().totalPaidAmount()).isEqualByComparingTo(MONTHLY);
        assertThat(response.getBody().installments()).hasSize(1);
        assertThat(response.getBody().installments().get(0).overdue()).isTrue();
        assertThat(response.getBody().installments().get(0).overdueDays()).isEqualTo(2);
    }

    @Test
    void 회차_납입은_경로의_계약id_와_토큰의_예금주를_함께_넘긴다() {
        when(payInstallmentUseCase.pay(any())).thenReturn(savings());

        ResponseEntity<InstallmentSavingsResponse> response = controller.pay(
                9L, new PayInstallmentRequest(2, MONTHLY), authentication(42L));

        verify(payInstallmentUseCase).pay(payCaptor.capture());
        PayInstallmentCommand command = payCaptor.getValue();
        assertThat(command.savingsId()).isEqualTo(9L);
        assertThat(command.depositorId()).isEqualTo("42");
        assertThat(command.round()).isEqualTo(2);
        assertThat(command.amount()).isEqualByComparingTo(MONTHLY);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void 만기해지와_중도해지는_각각의_유스케이스로_간다() {
        when(closeInstallmentSavingsUseCase.closeOnMaturity(any())).thenReturn(savings());
        when(closeInstallmentSavingsUseCase.closeEarly(any())).thenReturn(savings());

        // 해지 엔드포인트는 본문을 받지 않는다 — 해지일은 서버 시계 소관
        assertThat(controller.closeOnMaturity(9L, authentication(42L)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.closeEarly(9L, authentication(42L)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        verify(closeInstallmentSavingsUseCase).closeOnMaturity(closeCaptor.capture());
        assertThat(closeCaptor.getValue().savingsId()).isEqualTo(9L);
        assertThat(closeCaptor.getValue().depositorId()).isEqualTo("42");

        verify(closeInstallmentSavingsUseCase).closeEarly(closeCaptor.capture());
        assertThat(closeCaptor.getValue().depositorId()).isEqualTo("42");
    }

    @Test
    void 단건_조회는_경로_계약id_와_토큰_예금주로_조회한다() {
        when(queryInstallmentSavingsUseCase.get(9L, "42")).thenReturn(savings());

        ResponseEntity<InstallmentSavingsResponse> response = controller.get(9L, authentication(42L));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(9L);
        verify(queryInstallmentSavingsUseCase).get(9L, "42");
    }

    @Test
    void 목록_조회에는_사용자_식별자가_경로에_없다() {
        when(queryInstallmentSavingsUseCase.listMine("42")).thenReturn(List.of(savings(), savings()));

        ResponseEntity<List<InstallmentSavingsResponse>> response =
                controller.listMine(authentication(42L));

        assertThat(response.getBody()).hasSize(2);
        verify(queryInstallmentSavingsUseCase).listMine("42");
    }

    @Test
    void 인증이_없으면_403_이고_유스케이스는_호출되지_않는다() {
        assertThatThrownBy(() -> controller.open(openRequest(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.listMine(null))
                .isInstanceOf(AccessDeniedException.class);

        verify(openInstallmentSavingsUseCase, never()).open(any());
        verifyNoInteractions(queryInstallmentSavingsUseCase);
    }

    @Test
    void userId_가_없는_구_토큰은_403_이다() {
        Authentication legacy = authentication(null);

        assertThatThrownBy(() -> controller.get(9L, legacy))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.pay(9L, new PayInstallmentRequest(1, MONTHLY), legacy))
                .isInstanceOf(AccessDeniedException.class);

        verify(queryInstallmentSavingsUseCase, never()).get(anyLong(), anyString());
        verify(payInstallmentUseCase, never()).pay(any());
    }

    @Test
    void 주체가_AuthPrincipal_이_아니면_403_이다() {
        Authentication foreign = new UsernamePasswordAuthenticationToken("anonymous", null);

        assertThatThrownBy(() -> controller.closeEarly(9L, foreign))
                .isInstanceOf(AccessDeniedException.class);

        verify(closeInstallmentSavingsUseCase, never()).closeEarly(any());
    }
}
