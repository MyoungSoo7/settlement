package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.loan.adapter.in.web.dto.LeaseDtos.LeaseApplyRequest;
import github.lms.lemuel.loan.adapter.in.web.dto.LeaseDtos.LeaseContractResponse;
import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase;
import github.lms.lemuel.loan.application.port.in.ManageLeaseContractUseCase.ApplyLeaseCommand;
import github.lms.lemuel.loan.application.port.out.LoadLeaseContractPort;
import github.lms.lemuel.loan.domain.AssetFinanceType;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.EarlyTerminationQuote;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseSchedule;
import github.lms.lemuel.loan.domain.exception.LeaseContractNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 리스·할부 API 의 <b>인가 규칙</b>을 고정한다.
 *
 * <p>MockMvc 대신 컨트롤러를 직접 호출한다 — 여기서 검증할 것이 HTTP 매핑이 아니라
 * "누구의 식별자로 조회하는가"(IDOR)와 "남의 계약에 무엇을 알려 주는가"(403 아닌 404)이기 때문이다.
 * 슬라이스 테스트는 예외를 상태코드로 바꿔 버려 그 구분을 오히려 흐린다.
 */
class LeaseControllerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 13, 9, 0, 0, 0, ZoneOffset.UTC);

    private ManageLeaseContractUseCase useCase;
    private LoadLeaseContractPort loadPort;
    private LeaseController controller;

    @BeforeEach
    void setUp() {
        useCase = mock(ManageLeaseContractUseCase.class);
        loadPort = mock(LoadLeaseContractPort.class);
        controller = new LeaseController(useCase, loadPort);
    }

    private static Authentication userAuth(long userId, String email) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, email, "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static Authentication adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(99L, "admin@example.com", "ADMIN"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static LeaseSchedule schedule() {
        return LeaseSchedule.of(AssetFinanceType.FINANCE_LEASE, new BigDecimal("30000000"),
                BigDecimal.ZERO, new BigDecimal("3000000"), new BigDecimal("6000000"), 36,
                new BigDecimal("6.0"));
    }

    private static LeaseContract contractOf(long borrowerUserId) {
        return LeaseContract.apply(Borrower.individual(borrowerUserId, "홍길동"), "지게차 3톤", schedule(), NOW);
    }

    private static LeaseApplyRequest applyRequest() {
        return new LeaseApplyRequest(AssetFinanceType.FINANCE_LEASE, "지게차 3톤",
                new BigDecimal("30000000"), null, new BigDecimal("3000000"), new BigDecimal("6000000"),
                36, new BigDecimal("6.0"));
    }

    // ── 신청 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("신청 차주는 요청 바디가 아니라 JWT 주체에서 파생한다 (IDOR 방지)")
    void applyDerivesBorrowerFromToken() {
        when(useCase.apply(any(ApplyLeaseCommand.class))).thenReturn(contractOf(7L));

        var response = controller.apply(applyRequest(), userAuth(7L, "u7@example.com"));

        ArgumentCaptor<ApplyLeaseCommand> captor = ArgumentCaptor.forClass(ApplyLeaseCommand.class);
        verify(useCase).apply(captor.capture());
        assertThat(captor.getValue().borrowerUserId()).isEqualTo(7L);
        assertThat(captor.getValue().borrowerName()).isEqualTo("u7@example.com");
        assertThat(captor.getValue().borrowerRegistrationNo()).isNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("선수금 등 선택 금액이 비면 0 으로 채워 커맨드에 넣는다")
    void applyFillsOptionalAmountsWithZero() {
        when(useCase.apply(any(ApplyLeaseCommand.class))).thenReturn(contractOf(7L));

        controller.apply(applyRequest(), userAuth(7L, "u7@example.com"));

        ArgumentCaptor<ApplyLeaseCommand> captor = ArgumentCaptor.forClass(ApplyLeaseCommand.class);
        verify(useCase).apply(captor.capture());
        assertThat(captor.getValue().downPayment()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("이메일이 없으면 표시명을 사용자 번호로 대체한다")
    void applyFallsBackToUserIdAsDisplayName() {
        when(useCase.apply(any(ApplyLeaseCommand.class))).thenReturn(contractOf(7L));

        controller.apply(applyRequest(), userAuth(7L, "  "));

        ArgumentCaptor<ApplyLeaseCommand> captor = ArgumentCaptor.forClass(ApplyLeaseCommand.class);
        verify(useCase).apply(captor.capture());
        assertThat(captor.getValue().borrowerName()).isEqualTo("사용자 7");
    }

    @Test
    @DisplayName("인증 주체가 없으면 신청을 거부한다")
    void applyRejectsAnonymous() {
        assertThatThrownBy(() -> controller.apply(applyRequest(), null))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(useCase);
    }

    // ── 조회 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("내 계약 목록은 토큰 주체로만 조회한다")
    void myContractsScopedToToken() {
        when(loadPort.findByBorrower(7L, 50)).thenReturn(List.of(contractOf(7L)));

        List<LeaseContractResponse> result = controller.myContracts(userAuth(7L, "u7@example.com"));

        assertThat(result).hasSize(1);
        verify(loadPort).findByBorrower(7L, 50);
    }

    @Test
    @DisplayName("차주는 본인 계약을 볼 수 있다")
    void detailAllowsOwner() {
        when(loadPort.findById(5L)).thenReturn(Optional.of(contractOf(7L)));

        LeaseContractResponse response = controller.detail(5L, userAuth(7L, "u7@example.com"));

        assertThat(response.assetDescription()).isEqualTo("지게차 3톤");
    }

    @Test
    @DisplayName("남의 계약은 403 이 아니라 404 — 존재 자체를 알리지 않는다")
    void detailHidesOthersContract() {
        when(loadPort.findById(5L)).thenReturn(Optional.of(contractOf(7L)));

        assertThatThrownBy(() -> controller.detail(5L, userAuth(8L, "u8@example.com")))
                .isInstanceOf(LeaseContractNotFoundException.class);
    }

    @Test
    @DisplayName("없는 계약도 같은 404 로 응답한다")
    void detailMissingContract() {
        when(loadPort.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.detail(5L, adminAuth()))
                .isInstanceOf(LeaseContractNotFoundException.class);
    }

    @Test
    @DisplayName("운영자는 남의 계약도 조회할 수 있다")
    void detailAllowsOperator() {
        when(loadPort.findById(5L)).thenReturn(Optional.of(contractOf(7L)));

        assertThat(controller.detail(5L, adminAuth())).isNotNull();
    }

    @Test
    @DisplayName("회차표도 소유권 대조를 거친다")
    void scheduleChecksOwnership() {
        when(loadPort.findById(5L)).thenReturn(Optional.of(contractOf(7L)));

        assertThat(controller.schedule(5L, userAuth(7L, "u7@example.com")).installments()).isNotEmpty();
        assertThatThrownBy(() -> controller.schedule(5L, userAuth(8L, "u8@example.com")))
                .isInstanceOf(LeaseContractNotFoundException.class);
    }

    // ── 중도해지 견적 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("차주 견적 조회는 본인 식별자를 스코프로 넘긴다")
    void quoteScopesToBorrower() {
        when(useCase.quoteEarlyTermination(eq(5L), any(BigDecimal.class), eq(7L)))
                .thenReturn(quote());

        controller.quoteEarlyTermination(5L, new BigDecimal("3"), userAuth(7L, "u7@example.com"));

        verify(useCase).quoteEarlyTermination(5L, new BigDecimal("3"), 7L);
    }

    @Test
    @DisplayName("운영자 견적 조회는 스코프 없이(null) 호출한다")
    void quoteUnscopedForOperator() {
        when(useCase.quoteEarlyTermination(eq(5L), any(BigDecimal.class), eq(null)))
                .thenReturn(quote());

        controller.quoteEarlyTermination(5L, new BigDecimal("3"), adminAuth());

        verify(useCase).quoteEarlyTermination(5L, new BigDecimal("3"), null);
    }

    private static EarlyTerminationQuote quote() {
        return EarlyTerminationQuote.of(schedule(), 6, new BigDecimal("3"));
    }

    // ── 운영자 전용 조작 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("승인·거절·취소·개시·수납·연체·기한이익상실·만기는 운영자만 가능하다")
    void operatorOnlyTransitions() {
        Authentication user = userAuth(7L, "u7@example.com");

        assertThatThrownBy(() -> controller.approve(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.reject(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.cancel(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.activate(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.payInstallment(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.markOverdue(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.markDefaulted(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.mature(5L, user)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.terminateEarly(5L, new BigDecimal("3"), user))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("운영자 조작은 각 유스케이스로 그대로 위임된다")
    void operatorTransitionsDelegate() {
        LeaseContract contract = contractOf(7L);
        when(useCase.approve(5L)).thenReturn(contract);
        when(useCase.reject(5L)).thenReturn(contract);
        when(useCase.cancel(5L)).thenReturn(contract);
        when(useCase.activate(5L)).thenReturn(contract);
        when(useCase.payInstallment(5L)).thenReturn(contract);
        when(useCase.markOverdue(5L)).thenReturn(contract);
        when(useCase.markDefaulted(5L)).thenReturn(contract);
        when(useCase.mature(5L)).thenReturn(contract);
        when(useCase.terminateEarly(eq(5L), any(BigDecimal.class))).thenReturn(quote());
        Authentication admin = adminAuth();

        controller.approve(5L, admin);
        controller.reject(5L, admin);
        controller.cancel(5L, admin);
        controller.activate(5L, admin);
        controller.payInstallment(5L, admin);
        controller.markOverdue(5L, admin);
        controller.markDefaulted(5L, admin);
        controller.mature(5L, admin);
        controller.terminateEarly(5L, new BigDecimal("3"), admin);

        verify(useCase).approve(5L);
        verify(useCase).reject(5L);
        verify(useCase).cancel(5L);
        verify(useCase).activate(5L);
        verify(useCase).payInstallment(5L);
        verify(useCase).markOverdue(5L);
        verify(useCase).markDefaulted(5L);
        verify(useCase).mature(5L);
        verify(useCase).terminateEarly(5L, new BigDecimal("3"));
    }
}
