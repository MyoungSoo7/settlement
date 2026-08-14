package github.lms.lemuel.account.banking.timedeposit.adapter.in.web;

import github.lms.lemuel.account.banking.timedeposit.adapter.in.web.dto.OpenTimeDepositRequest;
import github.lms.lemuel.account.banking.timedeposit.adapter.in.web.dto.TimeDepositResponse;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.CloseTimeDepositUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.OpenTimeDepositUseCase;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.OpenTimeDepositUseCase.OpenTimeDepositCommand;
import github.lms.lemuel.account.banking.timedeposit.application.port.in.TimeDepositQueryUseCase;
import github.lms.lemuel.account.banking.timedeposit.domain.Compounding;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDeposit;
import github.lms.lemuel.account.banking.timedeposit.domain.TimeDepositStatus;
import github.lms.lemuel.account.banking.timedeposit.domain.exception.TimeDepositAccessDeniedException;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 정기예금 컨트롤러 — 예금주 식별자가 <b>오직 JWT 주체</b>에서만 나오는지 검증한다.
 *
 * <p>MockMvc 없이 순수 Mockito 로 메서드를 직접 호출한다. 여기서 지켜야 할 계약은 HTTP 배선이 아니라
 * "요청 본문·경로에 담긴 어떤 값도 예금주를 결정하지 못한다"는 IDOR 불변식이기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class TimeDepositControllerTest {

    private static final Long USER_ID = 42L;
    private static final Long DEPOSIT_ID = 77L;
    private static final BigDecimal PRINCIPAL = new BigDecimal("10000000");

    @Mock
    private OpenTimeDepositUseCase openTimeDepositUseCase;
    @Mock
    private CloseTimeDepositUseCase closeTimeDepositUseCase;
    @Mock
    private TimeDepositQueryUseCase timeDepositQueryUseCase;

    @InjectMocks
    private TimeDepositController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Long userId) {
        AuthPrincipal principal = new AuthPrincipal(userId, "depositor@lemuel.co.kr", "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static TimeDeposit deposit(String depositorId) {
        return TimeDeposit.reconstitute(DEPOSIT_ID, depositorId, "정기예금 12개월", PRINCIPAL,
                new BigDecimal("0.04"), new BigDecimal("0.005"), Compounding.SIMPLE, 12,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                TimeDepositStatus.ACTIVE, null, null, null);
    }

    private static OpenTimeDepositRequest openRequest() {
        return new OpenTimeDepositRequest("정기예금 12개월", PRINCIPAL,
                new BigDecimal("0.04"), new BigDecimal("0.005"), Compounding.SIMPLE, 12);
    }

    // ── 개설 ────────────────────────────────────────────────────────────────

    @Test
    void 개설_요청의_예금주는_JWT_주체에서만_나온다() {
        authenticateAs(USER_ID);
        given(openTimeDepositUseCase.open(any())).willReturn(deposit("42"));
        ArgumentCaptor<OpenTimeDepositCommand> captor = ArgumentCaptor.forClass(OpenTimeDepositCommand.class);

        ResponseEntity<TimeDepositResponse> response = controller.open(openRequest());

        verify(openTimeDepositUseCase).open(captor.capture());
        // 요청 DTO 에는 예금주 필드 자체가 없다 — 유일한 출처가 토큰의 userId 임을 값으로 못 박는다
        assertThat(captor.getValue().depositorId()).isEqualTo("42");
        assertThat(captor.getValue().productName()).isEqualTo("정기예금 12개월");
        assertThat(captor.getValue().principal()).isEqualByComparingTo(PRINCIPAL);
        assertThat(captor.getValue().compounding()).isEqualTo(Compounding.SIMPLE);
        assertThat(captor.getValue().termMonths()).isEqualTo(12);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(DEPOSIT_ID);
    }

    @Test
    void 예치기간이_빠진_요청은_0으로_내려가_도메인이_거절하게_한다() {
        authenticateAs(USER_ID);
        given(openTimeDepositUseCase.open(any())).willReturn(deposit("42"));
        ArgumentCaptor<OpenTimeDepositCommand> captor = ArgumentCaptor.forClass(OpenTimeDepositCommand.class);

        controller.open(new OpenTimeDepositRequest("정기예금", PRINCIPAL,
                new BigDecimal("0.04"), new BigDecimal("0.005"), Compounding.SIMPLE, null));

        verify(openTimeDepositUseCase).open(captor.capture());
        assertThat(captor.getValue().termMonths()).isZero();
    }

    // ── 해지 ────────────────────────────────────────────────────────────────

    @Test
    void 만기해지는_토큰_예금주와_경로의_계좌id_로_위임된다() {
        authenticateAs(USER_ID);
        TimeDeposit closed = deposit("42");
        given(closeTimeDepositUseCase.closeOnMaturity("42", DEPOSIT_ID)).willReturn(closed);

        ResponseEntity<TimeDepositResponse> response = controller.closeOnMaturity(DEPOSIT_ID);

        verify(closeTimeDepositUseCase).closeOnMaturity("42", DEPOSIT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().depositorId()).isEqualTo("42");
    }

    @Test
    void 중도해지도_토큰_예금주로_위임된다() {
        authenticateAs(USER_ID);
        given(closeTimeDepositUseCase.closeEarly("42", DEPOSIT_ID)).willReturn(deposit("42"));

        controller.closeEarly(DEPOSIT_ID);

        verify(closeTimeDepositUseCase).closeEarly(eq("42"), eq(DEPOSIT_ID));
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Test
    void 단건_조회는_응답_DTO_로_변환해_돌려준다() {
        authenticateAs(USER_ID);
        given(timeDepositQueryUseCase.get("42", DEPOSIT_ID)).willReturn(deposit("42"));

        TimeDepositResponse body = controller.get(DEPOSIT_ID).getBody();

        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(DEPOSIT_ID);
        assertThat(body.principal()).isEqualByComparingTo(PRINCIPAL);
        assertThat(body.status()).isEqualTo(TimeDepositStatus.ACTIVE);
        assertThat(body.maturityDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        // 해지 전에는 이자·지급액·해지일이 비어 있다 (0 으로 채우지 않는다)
        assertThat(body.settledInterest()).isNull();
        assertThat(body.payoutAmount()).isNull();
        assertThat(body.closedOn()).isNull();
    }

    @Test
    void 목록_조회는_다른_예금주의_계좌를_요청할_방법이_없다() {
        authenticateAs(USER_ID);
        given(timeDepositQueryUseCase.listMine("42")).willReturn(List.of(deposit("42")));

        ResponseEntity<List<TimeDepositResponse>> response = controller.listMine();

        // listMine 은 파라미터가 아예 없다 — 남의 목록을 지목할 표면 자체가 없다
        verify(timeDepositQueryUseCase).listMine("42");
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).depositorId()).isEqualTo("42");
    }

    // ── 인증 주체 부재 ────────────────────────────────────────────────────────

    @Test
    void 인증이_없으면_403_이고_유스케이스는_호출되지_않는다() {
        assertThatThrownBy(() -> controller.listMine())
                .isInstanceOf(TimeDepositAccessDeniedException.class);

        verifyNoInteractions(openTimeDepositUseCase, closeTimeDepositUseCase, timeDepositQueryUseCase);
    }

    @Test
    void userId_없는_구토큰은_500_이_아니라_403_으로_끊긴다() {
        authenticateAs(null);

        assertThatThrownBy(() -> controller.get(DEPOSIT_ID))
                .isInstanceOfSatisfying(TimeDepositAccessDeniedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
                    assertThat(e.getErrorCode().status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getDepositId()).isNull();   // 계좌가 특정되기 전에 끊긴다
                });

        verifyNoInteractions(timeDepositQueryUseCase);
    }

    @Test
    void 주체가_AuthPrincipal_이_아니면_개설도_403_이다() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymous", null, List.of()));
        OpenTimeDepositRequest request = openRequest();

        assertThatThrownBy(() -> controller.open(request))
                .isInstanceOf(TimeDepositAccessDeniedException.class);

        verifyNoInteractions(openTimeDepositUseCase);
    }

    @Test
    void 해지_경로도_인증이_없으면_403_이다() {
        assertThatThrownBy(() -> controller.closeOnMaturity(DEPOSIT_ID))
                .isInstanceOf(TimeDepositAccessDeniedException.class);
        assertThatThrownBy(() -> controller.closeEarly(DEPOSIT_ID))
                .isInstanceOf(TimeDepositAccessDeniedException.class);

        verifyNoInteractions(closeTimeDepositUseCase);
    }
}
