package github.lms.lemuel.market.application.service;

import github.lms.lemuel.market.application.port.in.SyncResult;
import github.lms.lemuel.market.application.port.out.KrxClientPort;
import github.lms.lemuel.market.application.port.out.KrxClientPort.StockPrice;
import github.lms.lemuel.market.application.port.out.SaveQuotePort;
import github.lms.lemuel.market.domain.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KrxSyncServiceFailureTest {

    private KrxClientPort krxClient;
    private SaveQuotePort saveQuotePort;
    private KrxSyncService service;

    @BeforeEach
    void setUp() {
        krxClient = mock(KrxClientPort.class);
        saveQuotePort = mock(SaveQuotePort.class);
        service = new KrxSyncService(krxClient, saveQuotePort);
    }

    @Test
    void 저장중_예외는_failed로_집계되고_계속_진행한다() {
        when(krxClient.isConfigured()).thenReturn(true);
        when(krxClient.fetchQuotes(any())).thenReturn(List.of(
                price("005930", Market.KOSPI, new BigDecimal("78000"))));
        doThrow(new RuntimeException("DB down")).when(saveQuotePort).upsertQuote(any());

        SyncResult result = service.syncQuotes(LocalDate.of(2026, 7, 7));

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.upserted()).isZero();
    }

    @Test
    void baseDate_null이면_예외() {
        when(krxClient.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.syncQuotes(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDate");
    }

    private static StockPrice price(String code, Market market, BigDecimal close) {
        return new StockPrice(code, "ISIN" + code, "종목" + code, market, LocalDate.of(2026, 7, 7),
                close, null, null, null, null, null, null, null, null, null);
    }
}
