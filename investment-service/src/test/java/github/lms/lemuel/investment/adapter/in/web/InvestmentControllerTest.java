package github.lms.lemuel.investment.adapter.in.web;

import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import github.lms.lemuel.investment.adapter.out.persistence.InvestmentManualIdempotencyGuard;
import github.lms.lemuel.investment.application.exception.InsufficientFundingException;
import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.application.exception.NotInvestableException;
import github.lms.lemuel.investment.application.port.in.CancelInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.ExecuteInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.GetBeginnerCheckUseCase;
import github.lms.lemuel.investment.application.port.in.GetFundingUseCase;
import github.lms.lemuel.investment.application.port.in.GetInvestmentScoreUseCase;
import github.lms.lemuel.investment.application.port.in.GetStockRecommendationsUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase;
import github.lms.lemuel.investment.application.port.in.PlaceInvestmentOrderUseCase.PlaceInvestmentOrderCommand;
import github.lms.lemuel.investment.application.port.out.LoadInvestmentOrderPort;
import github.lms.lemuel.investment.domain.BeginnerInvestmentCheck;
import github.lms.lemuel.investment.domain.InvestmentGrade;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import github.lms.lemuel.investment.domain.InvestmentScore;
import github.lms.lemuel.investment.domain.MacroCheck;
import github.lms.lemuel.investment.domain.NewsRiskCheck;
import github.lms.lemuel.investment.domain.PricePositionCheck;
import github.lms.lemuel.investment.domain.SellerFunding;
import github.lms.lemuel.investment.domain.StockRecommendation;
import github.lms.lemuel.investment.domain.TradePlanPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvestmentControllerTest {

    private final GetInvestmentScoreUseCase getScore = mock(GetInvestmentScoreUseCase.class);
    private final GetBeginnerCheckUseCase getCheck = mock(GetBeginnerCheckUseCase.class);
    private final PlaceInvestmentOrderUseCase place = mock(PlaceInvestmentOrderUseCase.class);
    private final ExecuteInvestmentOrderUseCase execute = mock(ExecuteInvestmentOrderUseCase.class);
    private final CancelInvestmentOrderUseCase cancel = mock(CancelInvestmentOrderUseCase.class);
    private final GetFundingUseCase getFunding = mock(GetFundingUseCase.class);
    private final GetStockRecommendationsUseCase getRecommendations = mock(GetStockRecommendationsUseCase.class);
    private final LoadInvestmentOrderPort loadOrder = mock(LoadInvestmentOrderPort.class);
    private final InvestmentManualIdempotencyGuard idempotency = mock(InvestmentManualIdempotencyGuard.class);

    private MockMvc mvc;

    /** 인증 주체 — userId=sellerId. */
    private static Authentication auth(long userId) {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(userId, "u" + userId + "@example.com", "USER"),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @BeforeEach
    void setUp() {
        InvestmentController controller = new InvestmentController(
                getScore, getCheck, place, execute, cancel, getFunding, getRecommendations, loadOrder, idempotency);
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
                .andExpect(jsonPath("$.growth.maxScore").value(30))
                // 개선 포인트 — margin 10%(12점)의 다음 구간 15% 도달 시 +4점이 첫 항목
                .andExpect(jsonPath("$.improvements").isArray())
                .andExpect(jsonPath("$.improvements[0].axis").value("PROFITABILITY"))
                .andExpect(jsonPath("$.improvements[0].metric").value("operatingMargin"))
                .andExpect(jsonPath("$.improvements[0].potentialGain").value(4));
    }

    @Test
    @DisplayName("GET /recommendations 200 — 추천일·항목(이유/1차매수/손절/익절)·가격규칙·고지문")
    void recommendations200() throws Exception {
        when(getRecommendations.getLatestRecommendations()).thenReturn(List.of(
                StockRecommendation.rehydrate("267260", "HD현대일렉트릭", "전력기기",
                        "FY2025 매출 +22.8%·영업이익률 24.4%, 규칙 5종 통과",
                        java.time.LocalDate.of(2026, 7, 15),
                        new BigDecimal("797000"), new BigDecimal("704000"), new BigDecimal("908000"), 1)));

        mvc.perform(get("/api/investment/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedDate").value("2026-07-15"))
                .andExpect(jsonPath("$.items[0].stockCode").value("267260"))
                .andExpect(jsonPath("$.items[0].stockName").value("HD현대일렉트릭"))
                .andExpect(jsonPath("$.items[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.items[0].entryPrice").value(797000))
                .andExpect(jsonPath("$.items[0].stopLossPrice").value(704000))
                .andExpect(jsonPath("$.items[0].takeProfitPrice").value(908000))
                .andExpect(jsonPath("$.priceRule").isNotEmpty())
                .andExpect(jsonPath("$.disclaimer").isNotEmpty());
    }

    @Test
    @DisplayName("GET /recommendations 200 — 추천 세트 없으면 빈 items + recommendedDate null")
    void recommendationsEmpty200() throws Exception {
        when(getRecommendations.getLatestRecommendations()).thenReturn(List.of());

        mvc.perform(get("/api/investment/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedDate").doesNotExist())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.disclaimer").isNotEmpty());
    }

    @Test
    @DisplayName("GET /checks/{stockCode} 200 — 4축 + 매매계획 + 고지문")
    void check200() throws Exception {
        NewsRiskCheck newsRisk = NewsRiskCheck.of(3, List.of(new NewsRiskCheck.Flag(
                "유상증자", "유상증자 결정", "https://n/1", java.time.Instant.parse("2026-07-10T00:00:00Z"))));
        PricePositionCheck pricePosition = new PricePositionCheck(
                PricePositionCheck.Status.OK, java.time.LocalDate.of(2026, 7, 10), new BigDecimal("63500"),
                1, false, new BigDecimal("70000"), new BigDecimal("50000"), new BigDecimal("-9.29"), false);
        MacroCheck macro = MacroCheck.of(List.of(new github.lms.lemuel.investment.domain.EconomicIndicatorSnapshot(
                "BASE_RATE", "기준금리", "%", new BigDecimal("2.50"),
                java.time.LocalDate.of(2026, 6, 30), new BigDecimal("-0.25"))));
        when(getCheck.getCheck("005930", new BigDecimal("3000000"))).thenReturn(new BeginnerInvestmentCheck(
                "005930", score(), newsRisk, pricePosition, macro,
                new TradePlanPolicy().plan(new BigDecimal("63500"), new BigDecimal("3000000"))));

        mvc.perform(get("/api/investment/checks/005930").param("budget", "3000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.score.grade").value("AA"))
                .andExpect(jsonPath("$.newsRisk.status").value("FLAGGED"))
                .andExpect(jsonPath("$.newsRisk.flags[0].keyword").value("유상증자"))
                .andExpect(jsonPath("$.pricePosition.status").value("OK"))
                .andExpect(jsonPath("$.macro.indicators[0].code").value("BASE_RATE"))
                .andExpect(jsonPath("$.tradePlan.feasible").value(true))
                .andExpect(jsonPath("$.tradePlan.totalQuantity").value(49))
                .andExpect(jsonPath("$.tradePlan.stopLossPrice").value(55600))
                .andExpect(jsonPath("$.disclaimer").value(
                        github.lms.lemuel.investment.adapter.in.web.dto.BeginnerCheckResponse.DISCLAIMER));
    }

    @Test
    @DisplayName("GET /checks/{stockCode} — 시세 축 없으면 tradePlan 은 null 로 내려간다")
    void checkWithoutTradePlan() throws Exception {
        when(getCheck.getCheck("005930", null)).thenReturn(new BeginnerInvestmentCheck(
                "005930", score(), NewsRiskCheck.noData(), PricePositionCheck.unavailable(),
                MacroCheck.unavailable(), null));

        mvc.perform(get("/api/investment/checks/005930"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newsRisk.status").value("NO_DATA"))
                .andExpect(jsonPath("$.pricePosition.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.macro.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.tradePlan").doesNotExist());
    }

    @Test
    @DisplayName("GET /checks/{stockCode} — 회계자료 없으면 404 (점수 조회와 동일 규약)")
    void check404() throws Exception {
        when(getCheck.getCheck("999999", null)).thenThrow(new InvestmentNotFoundException("재무제표 없음"));

        mvc.perform(get("/api/investment/checks/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /orders 201 — sellerId 는 인증 주체에서 파생(바디 미전달)")
    void place201() throws Exception {
        when(place.place(any(PlaceInvestmentOrderCommand.class)))
                .thenReturn(order(1L, InvestmentOrderStatus.REQUESTED));

        mvc.perform(post("/api/investment/orders").principal(auth(7L)).contentType(APPLICATION_JSON).content("""
                        {"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    @DisplayName("POST /orders — 미인증(principal 없음)이면 403")
    void placeNoAuth403() throws Exception {
        mvc.perform(post("/api/investment/orders").contentType(APPLICATION_JSON).content("""
                        {"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /orders 검증 실패는 400")
    void placeValidation400() throws Exception {
        mvc.perform(post("/api/investment/orders").principal(auth(7L)).contentType(APPLICATION_JSON).content("""
                        {"stockCode":"12","amount":-5}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /orders — 소수 3자리 amount 는 @Digits 로 400(저장 정밀도 초과 조기 차단)")
    void placeAmountTooManyDecimals400() throws Exception {
        mvc.perform(post("/api/investment/orders").principal(auth(7L)).contentType(APPLICATION_JSON).content("""
                        {"stockCode":"005930","amount":1000.555}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /checks/{stockCode} — budget 이 음수/0 이면 400(양수 검증)")
    void checkNonPositiveBudget400() throws Exception {
        mvc.perform(get("/api/investment/checks/005930").param("budget", "-100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /orders/{id}/execute 200")
    void execute200() throws Exception {
        when(execute.execute(1L, 7L)).thenReturn(order(1L, InvestmentOrderStatus.EXECUTED));

        mvc.perform(post("/api/investment/orders/1/execute").principal(auth(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    @DisplayName("POST /orders/{id}/execute — 타 셀러 주문이면 403")
    void executeOthers403() throws Exception {
        when(execute.execute(1L, 9L)).thenThrow(new AccessDeniedException("본인 소유가 아닌 투자 주문입니다. orderId=1"));

        mvc.perform(post("/api/investment/orders/1/execute").principal(auth(9L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("POST /orders/{id}/cancel 200")
    void cancel200() throws Exception {
        when(cancel.cancel(1L, 7L)).thenReturn(order(1L, InvestmentOrderStatus.CANCELED));

        mvc.perform(post("/api/investment/orders/1/cancel").principal(auth(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("POST execute — 동일 Idempotency-Key 재요청은 409 + 유스케이스 미호출")
    void executeDuplicateIdempotencyKey409() throws Exception {
        // 키 선점 실패(이미 선점됨) → 중복 조작이므로 409, 집행 유스케이스는 호출되지 않는다.
        when(idempotency.claim(anyString(), anyString(), anyString())).thenReturn(false);

        mvc.perform(post("/api/investment/orders/1/execute").principal(auth(7L))
                        .header("Idempotency-Key", "dup-key"))
                .andExpect(status().isConflict());

        verify(execute, never()).execute(any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("POST execute — 신규 Idempotency-Key 는 선점 성공 후 정상 집행(200)")
    void executeNewIdempotencyKeyProceeds() throws Exception {
        when(idempotency.claim(anyString(), anyString(), anyString())).thenReturn(true);
        when(execute.execute(1L, 7L)).thenReturn(order(1L, InvestmentOrderStatus.EXECUTED));

        mvc.perform(post("/api/investment/orders/1/execute").principal(auth(7L))
                        .header("Idempotency-Key", "new-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        verify(idempotency).claim("new-key", "investment:execute:1", "7");
    }

    @Test
    @DisplayName("POST execute — Idempotency-Key 미제공 시 기존 동작(가드 미호출, 200)")
    void executeNoIdempotencyKeyKeepsLegacyBehavior() throws Exception {
        when(execute.execute(1L, 7L)).thenReturn(order(1L, InvestmentOrderStatus.EXECUTED));

        mvc.perform(post("/api/investment/orders/1/execute").principal(auth(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        verify(idempotency, never()).claim(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST cancel — 동일 Idempotency-Key 재요청은 409 + 유스케이스 미호출")
    void cancelDuplicateIdempotencyKey409() throws Exception {
        when(idempotency.claim(anyString(), anyString(), anyString())).thenReturn(false);

        mvc.perform(post("/api/investment/orders/1/cancel").principal(auth(7L))
                        .header("Idempotency-Key", "dup-key"))
                .andExpect(status().isConflict());

        verify(cancel, never()).cancel(any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("POST orders(place) — 동일 Idempotency-Key 재요청은 409 + 유스케이스 미호출")
    void placeDuplicateIdempotencyKey409() throws Exception {
        when(idempotency.claim(anyString(), anyString(), anyString())).thenReturn(false);

        mvc.perform(post("/api/investment/orders").principal(auth(7L))
                        .header("Idempotency-Key", "dup-key")
                        .contentType(APPLICATION_JSON).content("""
                        {"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isConflict());

        verify(place, never()).place(any(PlaceInvestmentOrderCommand.class));
    }

    @Test
    @DisplayName("GET /orders?sellerId 200 — 본인 것만")
    void bySeller200() throws Exception {
        when(loadOrder.findBySeller(7L)).thenReturn(List.of(
                order(1L, InvestmentOrderStatus.REQUESTED),
                order(2L, InvestmentOrderStatus.EXECUTED)));

        mvc.perform(get("/api/investment/orders").principal(auth(7L)).param("sellerId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("GET /orders?sellerId — 타 셀러 조회 시 403")
    void bySellerOthers403() throws Exception {
        mvc.perform(get("/api/investment/orders").principal(auth(7L)).param("sellerId", "9"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /funding/{sellerId} 200 — 본인")
    void funding200() throws Exception {
        when(getFunding.getFunding(7L)).thenReturn(
                SellerFunding.of(7L, new BigDecimal("2000000"), new BigDecimal("500000")));

        mvc.perform(get("/api/investment/funding/7").principal(auth(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(7))
                .andExpect(jsonPath("$.available").value(1500000));
    }

    @Test
    @DisplayName("GET /funding/{sellerId} — 타 셀러 재원 조회 시 403")
    void fundingOthers403() throws Exception {
        mvc.perform(get("/api/investment/funding/9").principal(auth(7L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("NotFound → 404 (예외 핸들러)")
    void notFound404() throws Exception {
        when(execute.execute(404L, 7L)).thenThrow(new InvestmentNotFoundException("없음 orderId=404"));

        mvc.perform(post("/api/investment/orders/404/execute").principal(auth(7L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("없음 orderId=404"));
    }

    @Test
    @DisplayName("NotInvestable → 422 (예외 핸들러)")
    void notInvestable422() throws Exception {
        when(place.place(any())).thenThrow(new NotInvestableException("부적격"));

        mvc.perform(post("/api/investment/orders").principal(auth(7L)).contentType(APPLICATION_JSON).content("""
                        {"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    @DisplayName("InsufficientFunding → 422 (메시지 null 도 안전)")
    void insufficient422NullMessage() throws Exception {
        when(place.place(any())).thenThrow(new InsufficientFundingException(null));

        mvc.perform(post("/api/investment/orders").principal(auth(7L)).contentType(APPLICATION_JSON).content("""
                        {"stockCode":"005930","amount":1000000}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(""));
    }
}
