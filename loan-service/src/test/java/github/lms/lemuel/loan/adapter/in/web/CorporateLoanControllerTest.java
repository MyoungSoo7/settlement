package github.lms.lemuel.loan.adapter.in.web;

import github.lms.lemuel.common.config.jwt.JwtUtil;
import github.lms.lemuel.loan.application.port.in.DisburseCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase;
import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase.CorporateCreditView;
import github.lms.lemuel.loan.application.port.in.RequestCorporateLoanUseCase;
import github.lms.lemuel.loan.application.port.out.LoadCorporateLoanPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanNotFoundException;
import github.lms.lemuel.loan.domain.CorporateLoanRejectedException;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기업대출 도메인 예외 → HTTP 매핑 회귀 가드: 거절 422, NotFound 404
 * (CorporateLoanExceptionHandler). 형식 검증 실패(@Valid)는 공통 400 으로 흐른다.
 */
@WebMvcTest(controllers = CorporateLoanController.class)
@AutoConfigureMockMvc(addFilters = false)
class CorporateLoanControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean EvaluateCorporateCreditUseCase evaluateCorporateCreditUseCase;
    @MockitoBean RequestCorporateLoanUseCase requestCorporateLoanUseCase;
    @MockitoBean DisburseCorporateLoanUseCase disburseCorporateLoanUseCase;
    @MockitoBean LoadCorporateLoanPort loadCorporateLoanPort;

    @Test
    @DisplayName("POST /loans/corporate — 도메인 거절은 422 로 매핑된다")
    void requestRejectedMapsTo422() throws Exception {
        when(requestCorporateLoanUseCase.request(any()))
                .thenThrow(new CorporateLoanRejectedException("신청액이 한도를 초과합니다."));

        mockMvc.perform(post("/loans/corporate")
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
        mockMvc.perform(post("/loans/corporate")
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
    @DisplayName("POST /loans/corporate — 신청 성공은 201 + 대출 본문")
    void requestCreated() throws Exception {
        when(requestCorporateLoanUseCase.request(any())).thenReturn(loan());

        mockMvc.perform(post("/loans/corporate")
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
    @DisplayName("POST /loans/corporate/{id}/disburse — 실행 성공은 200")
    void disburseOk() throws Exception {
        when(disburseCorporateLoanUseCase.disburse(7L)).thenReturn(loan());

        mockMvc.perform(post("/loans/corporate/7/disburse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    @DisplayName("GET /loans/corporate — stockCode 없으면 최근 목록 조회")
    void listRecent() throws Exception {
        when(loadCorporateLoanPort.findRecent(anyInt())).thenReturn(List.of(loan()));

        mockMvc.perform(get("/loans/corporate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7));

        verify(loadCorporateLoanPort).findRecent(50);
    }

    @Test
    @DisplayName("GET /loans/corporate?stockCode= — 종목별 목록 조회")
    void listByStockCode() throws Exception {
        when(loadCorporateLoanPort.findByStockCode("005930")).thenReturn(List.of(loan()));

        mockMvc.perform(get("/loans/corporate").param("stockCode", "005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("005930"));

        verify(loadCorporateLoanPort).findByStockCode("005930");
    }
}
