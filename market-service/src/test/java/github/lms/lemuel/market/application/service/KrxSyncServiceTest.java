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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KrxSyncServiceTest {

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
    void 키미설정이면_예외() {
        when(krxClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.syncQuotes(LocalDate.of(2026, 7, 7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KRX_API_KEY");
    }

    @Test
    void 정상행은_종목과_시세를_upsert한다() {
        when(krxClient.isConfigured()).thenReturn(true);
        when(krxClient.fetchQuotes(any())).thenReturn(List.of(price("005930", Market.KOSPI, new BigDecimal("78000"))));

        SyncResult result = service.syncQuotes(LocalDate.of(2026, 7, 7));

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        verify(saveQuotePort).upsertStock(any());
        verify(saveQuotePort).upsertQuote(any());
    }

    @Test
    void 시장불명이나_종가결측_행은_스킵된다() {
        when(krxClient.isConfigured()).thenReturn(true);
        when(krxClient.fetchQuotes(any())).thenReturn(List.of(
                price("005930", null, new BigDecimal("78000")),       // 시장 불명
                price("000660", Market.KOSPI, null)));                // 종가 결측

        SyncResult result = service.syncQuotes(LocalDate.of(2026, 7, 7));

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.upserted()).isZero();
        assertThat(result.skipped()).isEqualTo(2);
        verify(saveQuotePort, never()).upsertQuote(any());
    }

    private static StockPrice price(String code, Market market, BigDecimal close) {
        return new StockPrice(code, "ISIN" + code, "종목" + code, market, LocalDate.of(2026, 7, 7),
                close, null, null, null, null, null, null, null, null, null);
    }
}
