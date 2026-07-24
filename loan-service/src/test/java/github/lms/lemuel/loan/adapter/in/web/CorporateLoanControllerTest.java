package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.loan.application.port.in.DisburseCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase;
import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase.CorporateCreditView;
import github.lms.lemuel.loan.application.port.in.RepayCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import github.lms.lemuel.loan.domain.exception.CorporateLoanNotFoundException;
import github.lms.lemuel.loan.domain.exception.CorporateLoanRejectedException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기업대출 도메인 예외 → HTTP 매핑 회귀 가드: 거절 422, NotFound 404
 * (CorporateLoanExceptionHandler). 형식 검증 실패(@Valid)는 공통 400 으로 흐른다.
 *
 * <p>추가로 IDOR 회귀 가드: 신청자(ownerUserId)는 JWT 주체에서 파생, 목록은 운영자(ADMIN/MANAGER)만
 * 전체 조회하고 그 외(CEO)는 본인 신청 건만 — 미인증은 403.
 */
@WebMvcTest(controllers = CorporateLoanController.class)
@AutoConfigureMockMvc(addFilters = false)
class CorporateLoanControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean EvaluateCorporateCreditUseCase evaluateCorporateCreditUseCase;
    @MockitoBean RequestCorporateLoanUseCase requestCorporateLoanUseCase;
    @MockitoBean DisburseCorporateLoanUseCase disburseCorporateLoanUseCase;
    @MockitoBean RepayCorporateLoanUseCase repayCorporateLoanUseCase;
    @MockitoBean LoadCorporateLoanPort loadCorporateLoanPort;

    /** 일반(CEO) 주체 — ROLE_USER, 본인 것만 조회 가능. */
    private static Authentication userAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@example.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** 운영자 주체 — ROLE_ADMIN, 전체 목록 조회 허용. */
    private static Authentication adminAuth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "admin" + userId + "@example.com", "ADMIN"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("POST /loans/corporate — 도메인 거절은 422 로 매핑된다")
    void requestRejectedMapsTo422() throws Exception {
        when(requestCorporateLoanUseCase.request(any()))
                .thenThrow(new CorporateLoanRejectedException("신청액이 한도를 초과합니다."));

        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":100000,"termDays":30}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.errorCode").value("CORPORATE_LOAN_REJECTED"));
    }

    @Test
    @DisplayName("GET /loans/corporate/credit/{stockCode} — 재무자료 없음은 404 로 매핑된다")
    void creditNotFoundMapsTo404() throws Exception {
        when(evaluateCorporateCreditUseCase.evaluate(anyString()))
                .thenThrow(new CorporateLoanNotFoundException("상장사 재무자료를 찾을 수 없습니다: 000000"));

        mockMvc.perform(get("/loans/corporate/credit/000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("CORPORATE_LOAN_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /loans/corporate — 형식 검증 실패(6자리 아님)는 400 으로 흐른다")
    void requestInvalidFormatMapsTo400() throws Exception {
        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"12","principal":100000,"termDays":30}
                                """))
                .andExpect(status().isBadRequest());
    }

    private static CorporateLoan loan() {
        return CorporateLoan.reconstitute(7L, "005930", "삼성전자",
                new BigDecimal("1000000"), new BigDecimal("6600"), new BigDecimal("1006600"),
                30, 82, "A", CorporateLoanStatus.DISBURSED, LocalDateTime.now());
    }

    @Test
    @DisplayName("GET /loans/corporate/credit/{stockCode} — 신용평가 200 + 뷰 매핑")
    void creditOk() throws Exception {
        when(evaluateCorporateCreditUseCase.evaluate("005930")).thenReturn(new CorporateCreditView(
                "005930", "삼성전자", "KOSPI", 2025, 82, "A", new BigDecimal("5000000"),
                new BigDecimal("40.2"), new BigDecimal("15.5"), new BigDecimal("8.1"), "B"));

        mockMvc.perform(get("/loans/corporate/credit/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.creditGrade").value("A"))
                .andExpect(jsonPath("$.limit").value(5000000))
                .andExpect(jsonPath("$.reputationGrade").value("B"));
    }

    @Test
    @DisplayName("POST /loans/corporate — 신청 성공은 201 + 대출 본문 (ownerUserId 는 JWT 주체에서 파생)")
    void requestCreated() throws Exception {
        when(requestCorporateLoanUseCase.request(any())).thenReturn(loan());

        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":1000000,"termDays":30}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.status").value("DISBURSED"));
    }

    @Test
    @DisplayName("POST /loans/corporate — termDays 경계값(@Max 3650)은 통과해 201")
    void requestTermDaysAtMaxBoundaryPasses() throws Exception {
        when(requestCorporateLoanUseCase.request(any())).thenReturn(loan());

        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":1000000,"termDays":3650}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /loans/corporate — termDays 초과(@Max 3650 + 1)는 400 이고 use case 미호출")
    void requestTermDaysOverMaxIs400() throws Exception {
        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":1000000,"termDays":3651}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestCorporateLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/corporate — principal 정수 17자리·소수 2자리(@Digits 상한 근처)는 통과해 201")
    void requestPrincipalAtDigitsUpperBoundPasses() throws Exception {
        when(requestCorporateLoanUseCase.request(any())).thenReturn(loan());

        // 99999999999999999.99 — 정수 17자리·소수 2자리(@Digits(17,2) 상한). 형식 검증 통과(한도초과는 mock 우회).
        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":99999999999999999.99,"termDays":30}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /loans/corporate — principal 정수 18자리(@Digits 초과)는 400 이고 use case 미호출")
    void requestPrincipalOverIntegerDigitsIs400() throws Exception {
        // 100000000000000000 — 정수 18자리(@Digits(17,2) 초과) → 형식 400.
        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":100000000000000000,"termDays":30}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestCorporateLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/corporate — principal 소수 3자리(@Digits 초과)는 400")
    void requestPrincipalOverFractionDigitsIs400() throws Exception {
        mockMvc.perform(post("/loans/corporate").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":100000.999,"termDays":30}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(requestCorporateLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/corporate — 미인증(principal 없음)이면 403 이고 use case 는 호출되지 않는다")
    void requestNoAuth403() throws Exception {
        mockMvc.perform(post("/loans/corporate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockCode":"005930","principal":1000000,"termDays":30}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(requestCorporateLoanUseCase);
    }

    /** 소유자(ownerUserId) 가 지정된 DISBURSED 기업대출 — 상환·소유권 대조 테스트용. */
    private static CorporateLoan ownedLoan(long ownerUserId) {
        return CorporateLoan.reconstitute(7L, "005930", "삼성전자",
                new BigDecimal("1000000"), new BigDecimal("6600"), new BigDecimal("1006600"),
                30, 82, "A", CorporateLoanStatus.DISBURSED, LocalDateTime.now(), ownerUserId);
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/disburse — 운영자 실행은 200 (소유권 대조 우회)")
    void disburseAsOperatorOk() throws Exception {
        when(disburseCorporateLoanUseCase.disburse(7L)).thenReturn(loan());

        mockMvc.perform(post("/loans/corporate/7/disburse").principal(adminAuth(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/disburse — 본인 소유 실행은 200")
    void disburseAsOwnerOk() throws Exception {
        when(loadCorporateLoanPort.findById(7L)).thenReturn(java.util.Optional.of(ownedLoan(7L)));
        when(disburseCorporateLoanUseCase.disburse(7L)).thenReturn(loan());

        mockMvc.perform(post("/loans/corporate/7/disburse").principal(userAuth(7L)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/disburse — 타인 대출 실행은 403 이고 use case 미호출 (IDOR 가드)")
    void disburseByNonOwnerForbidden() throws Exception {
        when(loadCorporateLoanPort.findById(7L)).thenReturn(java.util.Optional.of(ownedLoan(7L)));

        mockMvc.perform(post("/loans/corporate/7/disburse").principal(userAuth(9L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(disburseCorporateLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/disburse — 미인증이면 403 이고 use case 미호출")
    void disburseNoAuthForbidden() throws Exception {
        mockMvc.perform(post("/loans/corporate/7/disburse"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(disburseCorporateLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/repay — 본인 상환은 200 + 갱신 본문")
    void repayAsOwnerOk() throws Exception {
        when(loadCorporateLoanPort.findById(7L)).thenReturn(java.util.Optional.of(ownedLoan(7L)));
        when(repayCorporateLoanUseCase.repay(any())).thenReturn(loan());

        mockMvc.perform(post("/loans/corporate/7/repay").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":600000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));

        verify(repayCorporateLoanUseCase).repay(any());
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/repay — 타인 대출 상환은 403 이고 use case 미호출 (IDOR 가드)")
    void repayByNonOwnerForbidden() throws Exception {
        when(loadCorporateLoanPort.findById(7L)).thenReturn(java.util.Optional.of(ownedLoan(7L)));

        mockMvc.perform(post("/loans/corporate/7/repay").principal(userAuth(9L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":600000}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(repayCorporateLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/repay — 상환액 0 이하(@Positive)는 400 이고 use case 미호출")
    void repayNonPositiveAmountIs400() throws Exception {
        mockMvc.perform(post("/loans/corporate/7/repay").principal(userAuth(7L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":0}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(repayCorporateLoanUseCase);
    }

    @Test
    @DisplayName("POST /loans/corporate/{id}/repay — 미인증이면 403 이고 use case 미호출")
    void repayNoAuthForbidden() throws Exception {
        mockMvc.perform(post("/loans/corporate/7/repay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":600000}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(repayCorporateLoanUseCase);
    }

    @Test
    @DisplayName("GET /loans/corporate — 운영자는 stockCode 없으면 전체 최근 목록 조회")
    void listRecentAsOperator() throws Exception {
        when(loadCorporateLoanPort.findRecent(anyInt())).thenReturn(List.of(loan()));

        mockMvc.perform(get("/loans/corporate").principal(adminAuth(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));

        verify(loadCorporateLoanPort).findRecent(50);
    }

    @Test
    @DisplayName("GET /loans/corporate?stockCode= — 운영자는 종목별 목록 조회")
    void listByStockCodeAsOperator() throws Exception {
        when(loadCorporateLoanPort.findByStockCode("005930")).thenReturn(List.of(loan()));

        mockMvc.perform(get("/loans/corporate").principal(adminAuth(1L)).param("stockCode", "005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("005930"));

        verify(loadCorporateLoanPort).findByStockCode("005930");
    }

    @Test
    @DisplayName("GET /loans/corporate — 비운영자(CEO)는 본인 신청 건만 (findByOwner 로 스코핑)")
    void listOwnAsUser() throws Exception {
        when(loadCorporateLoanPort.findByOwner(eq(7L), anyInt())).thenReturn(List.of(loan()));

        mockMvc.perform(get("/loans/corporate").principal(userAuth(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));

        verify(loadCorporateLoanPort).findByOwner(7L, 50);
        verify(loadCorporateLoanPort, org.mockito.Mockito.never()).findRecent(anyInt());
    }

    @Test
    @DisplayName("GET /loans/corporate — 미인증이면 403")
    void listNoAuth403() throws Exception {
        mockMvc.perform(get("/loans/corporate"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(loadCorporateLoanPort);
    }
}
