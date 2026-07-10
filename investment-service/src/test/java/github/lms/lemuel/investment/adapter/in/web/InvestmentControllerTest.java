package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import github.lms.lemuel.investment.application.port.in.CancelInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.GetFundingUseCase;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.domain.InvestmentGrade;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import github.lms.lemuel.investment.domain.InvestmentScore;
import github.lms.lemuel.investment.domain.SellerFunding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvestmentControllerTest {

    private final GetInvestmentScoreUseCase getScore = mock(GetInvestmentScoreUseCase.class);
    private final PlaceInvestmentOrderUseCase place = mock(PlaceInvestmentOrderUseCase.class);
    private final ExecuteInvestmentOrderUseCase execute = mock(ExecuteInvestmentOrderUseCase.class);
    private final CancelInvestmentOrderUseCase cancel = mock(CancelInvestmentOrderUseCase.class);
    private final GetFundingUseCase getFunding = mock(GetFundingUseCase.class);
    private final LoadInvestmentOrderPort loadOrder = mock(LoadInvestmentOrderPort.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InvestmentController controller = new InvestmentController(
                getScore, place, execute, cancel, getFunding, loadOrder);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InvestmentExceptionHandler())
                .build();
    }

    private static InvestmentScore score() {
        return new InvestmentScore("005930", "삼성전자", "KOSPI", 2024, 82, InvestmentGrade.AA, true,
                new InvestmentScore.Profitability(30, new BigDecimal("10.0"), new BigDecimal("5.0")),
                new InvestmentScore.Stability(30, new BigDecimal("66.6"), new BigDecimal("60.0")),
                new InvestmentScore.Growth(22, new BigDecimal("8.0"), new BigDecimal("12.0")));
    }

    private static InvestmentOrder order(long id, InvestmentOrderStatus status) {
        return InvestmentOrder.reconstitute(id, 7L, "005930", new BigDecimal("1000000"),
                82, "AA", status, LocalDateTime.now());
    }

    @Test
    @DisplayName("GET /scores/{stockCode} 200 + 3축 응답")
    void score200() throws Exception {
        when(getScore.getScore("005930")).thenReturn(score());

        mvc.perform(get("/api/investment/scores/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.grade").value("AA"))
                .andExpect(jsonPath("$.investable").value(true))
                .andExpect(jsonPath("$.profitability.maxScore").value(35))
                .andExpect(jsonPath("$.stability.maxScore").value(35))
                .andExpect(jsonPath("$.growth.maxScore").value(30));
    }

    @Test
    @DisplayName("POST /orders 201 Created")
    void place201() throws Exception {
        when(place.place(any(PlaceInvestmentOrderCommand.class)))
                .thenReturn(order(1L, InvestmentOrderStatus.REQUESTED));

        mvc.perform(post("/api/investment/orders").contentType(APPLICATION_JSON).content("""
                        {"sellerId":7,"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    @DisplayName("POST /orders 검증 실패는 400")
    void placeValidation400() throws Exception {
        mvc.perform(post("/api/investment/orders").contentType(APPLICATION_JSON).content("""
                        {"sellerId":7,"stockCode":"12","amount":-5}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /orders/{id}/execute 200")
    void execute200() throws Exception {
        when(execute.execute(1L)).thenReturn(order(1L, InvestmentOrderStatus.EXECUTED));

        mvc.perform(post("/api/investment/orders/1/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    @DisplayName("POST /orders/{id}/cancel 200")
    void cancel200() throws Exception {
        when(cancel.cancel(1L)).thenReturn(order(1L, InvestmentOrderStatus.CANCELED));

        mvc.perform(post("/api/investment/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("GET /orders?sellerId 200 목록")
    void bySeller200() throws Exception {
        when(loadOrder.findBySeller(7L)).thenReturn(List.of(
                order(1L, InvestmentOrderStatus.REQUESTED),
                order(2L, InvestmentOrderStatus.EXECUTED)));

        mvc.perform(get("/api/investment/orders").param("sellerId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("GET /funding/{sellerId} 200")
    void funding200() throws Exception {
        when(getFunding.getFunding(7L)).thenReturn(
                SellerFunding.of(7L, new BigDecimal("2000000"), new BigDecimal("500000")));

        mvc.perform(get("/api/investment/funding/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(7))
                .andExpect(jsonPath("$.available").value(1500000));
    }

    @Test
    @DisplayName("NotFound → 404 (예외 핸들러)")
    void notFound404() throws Exception {
        when(execute.execute(404L)).thenThrow(new InvestmentNotFoundException("없음 orderId=404"));

        mvc.perform(post("/api/investment/orders/404/execute"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("없음 orderId=404"));
    }

    @Test
    @DisplayName("NotInvestable → 422 (예외 핸들러)")
    void notInvestable422() throws Exception {
        when(place.place(any())).thenThrow(new NotInvestableException("부적격"));

        mvc.perform(post("/api/investment/orders").contentType(APPLICATION_JSON).content("""
                        {"sellerId":7,"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("InsufficientFunding → 422 (메시지 null 도 안전)")
    void insufficient422NullMessage() throws Exception {
        when(place.place(any())).thenThrow(new InsufficientFundingException(null));

        mvc.perform(post("/api/investment/orders").contentType(APPLICATION_JSON).content("""
                        {"sellerId":7,"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(""));
    }
}
