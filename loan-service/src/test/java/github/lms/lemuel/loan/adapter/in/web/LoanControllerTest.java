package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.in.ManageLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * loan-service 는 자체 @ControllerAdvice 가 없어 IllegalArgumentException 이 shared-common 의
 * GlobalExceptionHandler 로 처리되어야 한다. 과거에는 공통 핸들러에 IllegalArgumentException 매핑이
 * 없어 500(INTERNAL_SERVER_ERROR)으로 누수되던 경로를 400 으로 고정하는 회귀 가드.
 *
 * <p>추가로 IDOR 회귀 가드: 신청 셀러는 JWT 주체에서 파생(바디 미전달), 목록은 본인 것만(불일치/미인증 403).
 */
@WebMvcTest(controllers = LoanController.class)
@AutoConfigureMockMvc(addFilters = false)
class LoanControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean RequestLoanUseCase requestLoanUseCase;
    @MockitoBean DisburseLoanUseCase disburseLoanUseCase;
    @MockitoBean ManageLoanCollectionUseCase manageLoanCollectionUseCase;
    @MockitoBean LoadLoanPort loadLoanPort;

    /** 인증 주체 — userId=sellerId, ROLE_USER. */
    private static Authentication auth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@example.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** 운영자 주체 — ROLE_ADMIN. 회수(연체·상각) 조작 허용 대상. */
    private static Authentication adminAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "admin" + userId + "@example.com", "ADMIN"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("POST /loans — 도메인 검증 실패(IllegalArgumentException)는 500 이 아닌 400 으로 매핑된다")
    void requestLoanInvalidArgumentMapsTo400() throws Exception {
        when(requestLoanUseCase.request(any()))
                .thenThrow(new IllegalArgumentException("financing days exceed limit"));

        mockMvc.perform(post("/loans").principal(auth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":100000,"financingDays":7}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("financing days exceed limit"));
    }

    @Test
    @DisplayName("POST /loans/{id}/disburse — 없는 대출(IllegalArgumentException)은 400 으로 매핑되고 use case 미호출")
    void disburseInvalidArgumentMapsTo400() throws Exception {
        // 소유권 대조 위해 컨트롤러가 먼저 load 하므로, 없는 대출은 load 단계에서 400 으로 표면화된다.
        when(loadLoanPort.load(999L))
                .thenThrow(new IllegalArgumentException("대출을 찾을 수 없습니다. loanId=999"));

        mockMvc.perform(post("/loans/999/disburse").principal(auth(7L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));

        verifyNoInteractions(disburseLoanUseCase);
    }

    private static LoanAdvance loan() {
        return LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("POST /loans — 신청 성공은 201 + 대출 본문 (sellerId 는 JWT 주체에서 파생)")
    void requestCreated() throws Exception {
        when(requestLoanUseCase.request(any())).thenReturn(loan());

        mockMvc.perform(post("/loans").principal(auth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":800000,"financingDays":7}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sellerId").value(7))
                .andExpect(jsonPath("$.status").value("DISBURSED"));
    }

    @Test
    @DisplayName("POST /loans — financingDays 경계값(@Max 365)은 통과해 201")
    void requestFinancingDaysAtMaxBoundaryPasses() throws Exception {
        when(requestLoanUseCase.request(any())).thenReturn(loan());

        mockMvc.perform(post("/loans").principal(auth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":800000,"financingDays":365}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /loans — financingDays 경계값(@Min 0)은 통과해 201")
    void requestFinancingDaysAtMinBoundaryPasses() throws Exception {
        when(requestLoanUseCase.request(any())).thenReturn(loan());

        mockMvc.perform(post("/loans").principal(auth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":800000,"financingDays":0}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /loans — financingDays 초과(@Max 365 + 1)는 400 이고 use case 미호출")
    void requestFinancingDaysOverMaxIs400() throws Exception {
        mockMvc.perform(post("/loans").principal(auth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":800000,"financingDays":366}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans — financingDays 음수(@Min 0 - 1)는 400 이고 use case 미호출")
    void requestFinancingDaysUnderMinIs400() throws Exception {
        mockMvc.perform(post("/loans").principal(auth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":800000,"financingDays":-1}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans — principal 소수 3자리(@Digits 초과)는 400 이고 use case 미호출")
    void requestPrincipalOverFractionDigitsIs400() throws Exception {
        mockMvc.perform(post("/loans").principal(auth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":800000.999,"financingDays":7}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans — 미인증(principal 없음)이면 403 이고 use case 는 호출되지 않는다")
    void requestNoAuth403() throws Exception {
        mockMvc.perform(post("/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principal":800000,"financingDays":7}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(requestLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/{id}/disburse — 본인 소유 대출 실행은 200")
    void disburseOk() throws Exception {
        when(loadLoanPort.load(1L)).thenReturn(loan());          // 대출 셀러=7
        when(disburseLoanUseCase.disburse(1L)).thenReturn(loan());

        mockMvc.perform(post("/loans/1/disburse").principal(auth(7L)))   // 호출자=7 (본인)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("POST /loans/{id}/disburse — 타 셀러 대출 실행은 403 이고 use case 미호출 (IDOR 가드)")
    void disburseByNonOwnerForbidden() throws Exception {
        when(loadLoanPort.load(1L)).thenReturn(loan());          // 대출 셀러=7

        mockMvc.perform(post("/loans/1/disburse").principal(auth(9L)))   // 호출자=9 (타인)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(disburseLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/{id}/disburse — 미인증이면 403 이고 use case 미호출")
    void disburseNoAuthForbidden() throws Exception {
        mockMvc.perform(post("/loans/1/disburse"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(disburseLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/{id}/overdue — 관리자 연체 진입은 200")
    void markOverdueAsAdminOk() throws Exception {
        when(manageLoanCollectionUseCase.markOverdue(1L))
                .thenReturn(LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                        new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.OVERDUE));

        mockMvc.perform(post("/loans/1/overdue").principal(adminAuth(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OVERDUE"));
    }

    @Test
    @DisplayName("POST /loans/{id}/overdue — 비관리자(USER)는 403 이고 use case 미호출")
    void markOverdueAsUserForbidden() throws Exception {
        mockMvc.perform(post("/loans/1/overdue").principal(auth(7L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(manageLoanCollectionUseCase);
    }

    @Test
    @DisplayName("POST /loans/{id}/write-off — 관리자 상각은 200")
    void writeOffAsAdminOk() throws Exception {
        when(manageLoanCollectionUseCase.writeOff(1L))
                .thenReturn(LoanAdvance.reconstitute(1L, 7L, new BigDecimal("800000"),
                        new BigDecimal("800"), new BigDecimal("800800"), LoanStatus.WRITTEN_OFF));

        mockMvc.perform(post("/loans/1/write-off").principal(adminAuth(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WRITTEN_OFF"));
    }

    @Test
    @DisplayName("POST /loans/{id}/write-off — 미인증이면 403 이고 use case 미호출")
    void writeOffNoAuthForbidden() throws Exception {
        mockMvc.perform(post("/loans/1/write-off"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(manageLoanCollectionUseCase);
    }

    @Test
    @DisplayName("GET /loans?sellerId= — 본인 셀러 대출 목록")
    void bySeller() throws Exception {
        when(loadLoanPort.findBySeller(7L)).thenReturn(List.of(loan()));

        mockMvc.perform(get("/loans").principal(auth(7L)).param("sellerId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sellerId").value(7));

        verify(loadLoanPort).findBySeller(eq(7L));
    }

    @Test
    @DisplayName("GET /loans?sellerId= — 타 셀러 조회 시 403 이고 조회는 실행되지 않는다")
    void bySellerOthers403() throws Exception {
        mockMvc.perform(get("/loans").principal(auth(7L)).param("sellerId", "9"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(loadLoanPort);
    }

    @Test
    @DisplayName("GET /loans?sellerId= — 미인증이면 403")
    void bySellerNoAuth403() throws Exception {
        mockMvc.perform(get("/loans").param("sellerId", "7"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(loadLoanPort);
    }
}
