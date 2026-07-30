package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.loan.adapter.out.persistence.LoanManualIdempotencyGuard;
import github.lms.lemuel.loan.application.port.in.DisburseSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.ManageSecuredLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.in.PrepaySecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.PrepaySecuredLoanUseCase.PrepayResult;
import github.lms.lemuel.loan.application.port.in.RepaySecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestSecuredLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadSecuredLoanPort;
import github.lms.lemuel.loan.domain.Borrower;
import github.lms.lemuel.loan.domain.LoanProductType;
import github.lms.lemuel.loan.domain.RepaymentMethod;
import github.lms.lemuel.loan.domain.SecuredLoan;
import github.lms.lemuel.loan.domain.SecuredLoanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 담보/개인신용 대출 상환·중도상환의 <b>순차 재제출 멱등 가드</b> 회귀 테스트.
 *
 * <p>서비스의 비관적 락은 동시 요청을 직렬화할 뿐, 앞선 상환이 커밋된 뒤 같은 요청이 다시 도착하는
 * 재제출(더블클릭·게이트웨이 재시도)은 막지 못한다. 상환·중도상환 전표(SEC_REPAYMENT·SEC_EARLY_FEE)는
 * 회차성이라 원장 유니크에서도 제외돼 있어, Idempotency-Key 선점이 유일한 방어선이다 —
 * {@code CorporateLoanController} 상환의 #4 선례와 동형.
 */
@WebMvcTest(controllers = SecuredLoanController.class)
@AutoConfigureMockMvc(addFilters = false)
class SecuredLoanControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RequestSecuredLoanUseCase requestSecuredLoanUseCase;
    @MockitoBean DisburseSecuredLoanUseCase disburseSecuredLoanUseCase;
    @MockitoBean RepaySecuredLoanUseCase repaySecuredLoanUseCase;
    @MockitoBean PrepaySecuredLoanUseCase prepaySecuredLoanUseCase;
    @MockitoBean ManageSecuredLoanCollectionUseCase manageSecuredLoanCollectionUseCase;
    @MockitoBean LoadSecuredLoanPort loadSecuredLoanPort;
    @MockitoBean LoanManualIdempotencyGuard idempotencyGuard;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 10, 0);

    private static Authentication userAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@example.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static SecuredLoan disbursedLoan(long id) {
        return SecuredLoan.reconstitute(id, Borrower.individual(42L, "홍길동"),
                LoanProductType.PERSONAL_CREDIT, null, new BigDecimal("10000000.00"), 36,
                new BigDecimal("6.00"), RepaymentMethod.EQUAL_PAYMENT, 780, "B",
                new BigDecimal("5000000.00"), SecuredLoanStatus.DISBURSED, NOW, NOW);
    }

    // ─── 중도상환 멱등 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /loans/secured/{id}/prepay — 동일 Idempotency-Key 재제출은 409 이고 use case 미호출")
    void prepayDuplicateIdempotencyKeyIs409() throws Exception {
        // 이미 선점된 키 → claim=false(중복). 두 번째 요청이 잔액을 다시 차감하고 수수료를
        // 이중 수취하는 경로를 여기서 끊는다 — 두 전표 모두 원장 유니크 제외라 DB 방어선이 없다.
        when(idempotencyGuard.claim(any(), any(), any())).thenReturn(false);

        mockMvc.perform(post("/loans/secured/7/prepay").principal(userAuth(42L))
                        .header("Idempotency-Key", "dup-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000000}
                                """))
                .andExpect(status().isConflict());

        verifyNoInteractions(prepaySecuredLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/secured/{id}/prepay — 새 Idempotency-Key 는 선점 후 200 (차감액·수수료 응답)")
    void prepayFreshIdempotencyKeyProceeds() throws Exception {
        when(idempotencyGuard.claim(eq("fresh-key-1"), eq("loan:secured:prepay:7"), any()))
                .thenReturn(true);
        when(prepaySecuredLoanUseCase.prepay(eq(7L), eq(42L), any())).thenReturn(new PrepayResult(
                disbursedLoan(7L), new BigDecimal("1000000"), new BigDecimal("12000.00")));

        mockMvc.perform(post("/loans/secured/7/prepay").principal(userAuth(42L))
                        .header("Idempotency-Key", "fresh-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prepaidAmount").value(1000000))
                .andExpect(jsonPath("$.earlyRepaymentFee").value(12000.00))
                .andExpect(jsonPath("$.loan.loanId").value(7));

        verify(prepaySecuredLoanUseCase).prepay(eq(7L), eq(42L), any());
    }

    @Test
    @DisplayName("POST /loans/secured/{id}/prepay — 키 미제공은 멱등 미적용으로 통과(하위호환)")
    void prepayWithoutKeyProceeds() throws Exception {
        when(idempotencyGuard.claim(any(), any(), any())).thenReturn(true);
        when(prepaySecuredLoanUseCase.prepay(eq(7L), eq(42L), any())).thenReturn(new PrepayResult(
                disbursedLoan(7L), new BigDecimal("1000000"), new BigDecimal("12000.00")));

        mockMvc.perform(post("/loans/secured/7/prepay").principal(userAuth(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000000}
                                """))
                .andExpect(status().isOk());

        verify(prepaySecuredLoanUseCase).prepay(eq(7L), eq(42L), any());
    }

    // ─── 회차 상환 멱등 (같은 공백 — 함께 봉합) ─────────────────────────────────

    @Test
    @DisplayName("POST /loans/secured/{id}/repay — 동일 Idempotency-Key 재제출은 409 이고 use case 미호출")
    void repayDuplicateIdempotencyKeyIs409() throws Exception {
        when(idempotencyGuard.claim(any(), any(), any())).thenReturn(false);

        mockMvc.perform(post("/loans/secured/7/repay").principal(userAuth(42L))
                        .header("Idempotency-Key", "dup-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principalPortion":1000000,"interestPortion":50000}
                                """))
                .andExpect(status().isConflict());

        verifyNoInteractions(repaySecuredLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/secured/{id}/repay — 새 Idempotency-Key 는 선점 후 200")
    void repayFreshIdempotencyKeyProceeds() throws Exception {
        when(idempotencyGuard.claim(eq("fresh-key-2"), eq("loan:secured:repay:7"), any()))
                .thenReturn(true);
        when(repaySecuredLoanUseCase.repay(eq(7L), eq(42L), any(), any())).thenReturn(disbursedLoan(7L));

        mockMvc.perform(post("/loans/secured/7/repay").principal(userAuth(42L))
                        .header("Idempotency-Key", "fresh-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principalPortion":1000000,"interestPortion":50000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanId").value(7));

        verify(repaySecuredLoanUseCase).repay(eq(7L), eq(42L), any(), any());
    }
}
