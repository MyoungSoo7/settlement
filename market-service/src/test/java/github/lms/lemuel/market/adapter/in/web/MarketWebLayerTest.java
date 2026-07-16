package github.lms.lemuel.market.adapter.in.web;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import github.lms.lemuel.market.application.port.in.GetStockSeriesUseCase;
import github.lms.lemuel.market.application.port.in.GetStocksUseCase;
import github.lms.lemuel.market.application.port.in.GetStocksUseCase.StockSnapshot;
import github.lms.lemuel.market.application.port.in.SyncQuotesUseCase;
import github.lms.lemuel.market.application.port.in.SyncResult;
import github.lms.lemuel.market.audit.application.port.out.RecordAuditPort;
import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockNotFoundException;
import github.lms.lemuel.market.domain.StockQuote;
import github.lms.lemuel.market.domain.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketWebLayerTest {

    private final GetStocksUseCase getStocks = mock(GetStocksUseCase.class);
    private final GetStockSeriesUseCase getSeries = mock(GetStockSeriesUseCase.class);
    private final SyncQuotesUseCase syncQuotes = mock(SyncQuotesUseCase.class);
    private final SyncStatusTracker tracker = new SyncStatusTracker(new SimpleMeterRegistry());
    private final TaskExecutor inlineExecutor = Runnable::run;

    private final Stock samsung = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, Instant.now());

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        StockController stockController = new StockController(getStocks, getSeries);
        MarketSyncAdminController adminController =
                new MarketSyncAdminController(syncQuotes, tracker, inlineExecutor, mock(RecordAuditPort.class));
        mvc = MockMvcBuilders.standaloneSetup(stockController, adminController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 카탈로그_조회() throws Exception {
        when(getStocks.getStocks(eq(Market.KOSPI), any(), eq(50))).thenReturn(List.of(samsung));

        mvc.perform(get("/api/market/stocks").param("market", "KOSPI").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stockCode").value("005930"))
                .andExpect(jsonPath("$[0].market").value("KOSPI"));
    }

    @Test
    void 최신시세_조회_시세있음() throws Exception {
        when(getStocks.getStock("005930")).thenReturn(new StockSnapshot(samsung, quote()));

        mvc.perform(get("/api/market/stocks/005930/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.latest.closePrice").value(78000.00))
                .andExpect(jsonPath("$.latest.source").value("KRX"));
    }

    @Test
    void 최신시세_조회_시세없음() throws Exception {
        when(getStocks.getStock("005930")).thenReturn(new StockSnapshot(samsung, null));

        mvc.perform(get("/api/market/stocks/005930/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latest").doesNotExist());
    }

    @Test
    void 시계열_조회() throws Exception {
        when(getStocks.getStock("005930")).thenReturn(new StockSnapshot(samsung, null));
        when(getSeries.getSeries(eq("005930"), any(), any())).thenReturn(List.of(quote()));

        mvc.perform(get("/api/market/stocks/005930/series")
                        .param("from", "2026-01-01").param("to", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("005930"))
                .andExpect(jsonPath("$.points[0].closePrice").value(78000.00));
    }

    @Test
    void 없는종목_조회는_404() throws Exception {
        when(getStocks.getStock("999999")).thenThrow(new StockNotFoundException("999999"));

        mvc.perform(get("/api/market/stocks/999999/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 잘못된기간_조회는_400() throws Exception {
        when(getStocks.getStock("005930")).thenReturn(new StockSnapshot(samsung, null));
        when(getSeries.getSeries(eq("005930"), any(), any()))
                .thenThrow(new IllegalArgumentException("기간 오류"));

        mvc.perform(get("/api/market/stocks/005930/series"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 수집트리거는_202_이후_status는_DONE() throws Exception {
        when(syncQuotes.syncQuotes(any())).thenReturn(new SyncResult(10, 9, 1, 0));

        mvc.perform(post("/admin/market/sync").param("baseDate", "2026-07-07"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.statusUrl").value("/admin/market/sync/status"));

        assertThat(tracker.current().state()).isEqualTo(SyncStatusTracker.State.DONE);

        mvc.perform(get("/admin/market/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DONE"));
    }

    @Test
    void 수집중이면_409() throws Exception {
        tracker.tryStart("quotes:existing");   // 강제로 RUNNING 선점

        mvc.perform(post("/admin/market/sync").param("baseDate", "2026-07-07"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 수집실패는_status가_FAILED() throws Exception {
        when(syncQuotes.syncQuotes(any())).thenThrow(new IllegalStateException("boom"));

        mvc.perform(post("/admin/market/sync").param("baseDate", "2026-07-07"))
                .andExpect(status().isAccepted());

        assertThat(tracker.current().state()).isEqualTo(SyncStatusTracker.State.FAILED);
        assertThat(tracker.current().error()).isEqualTo("boom");
    }

    @Test
    void syncStatusTracker_State_enum() {
        assertThat(SyncStatusTracker.State.valueOf("IDLE")).isEqualTo(SyncStatusTracker.State.IDLE);
        assertThat(SyncStatusTracker.State.values()).hasSize(4);
    }

    private static StockQuote quote() {
        return new StockQuote(1L, "005930", LocalDate.of(2026, 7, 7),
                new BigDecimal("78000.00"), new BigDecimal("77000"), new BigDecimal("79000"),
                new BigDecimal("76000"), new BigDecimal("1000"), new BigDecimal("1.30"),
                BigInteger.valueOf(1000), BigInteger.valueOf(78_000_000), BigInteger.valueOf(5_000_000),
                BigInteger.valueOf(465_000_000), ValueSource.KRX, Instant.now());
    }
}
