package github.lms.lemuel.market.application.service;

import github.lms.lemuel.market.application.port.in.GetStocksUseCase.StockSnapshot;
import github.lms.lemuel.market.application.port.out.LoadStockPort;
import github.lms.lemuel.market.application.port.out.LoadStockQuotePort;
import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockNotFoundException;
import github.lms.lemuel.market.domain.StockQuote;
import github.lms.lemuel.market.domain.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockQueryServiceTest {

    private LoadStockPort loadStockPort;
    private LoadStockQuotePort loadStockQuotePort;
    private StockQueryService service;

    private final Stock samsung = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, null);

    @BeforeEach
    void setUp() {
        loadStockPort = mock(LoadStockPort.class);
        loadStockQuotePort = mock(LoadStockQuotePort.class);
        service = new StockQueryService(loadStockPort, loadStockQuotePort);
    }

    @Test
    void getStocks_는_limit_이_0이하면_기본값_100_으로_조회한다() {
        when(loadStockPort.search(any(), any(), eq(100))).thenReturn(List.of(samsung));

        List<Stock> result = service.getStocks(Market.KOSPI, null, 0);

        assertThat(result).containsExactly(samsung);
    }

    @Test
    void getStocks_는_limit_상한_500_을_넘지_않는다() {
        when(loadStockPort.search(any(), any(), eq(500))).thenReturn(List.of());

        service.getStocks(null, null, 99999);

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(loadStockPort).search(any(), any(), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(500);
    }

    @Test
    void getStocks_는_공백_name_을_null_로_정규화한다() {
        service.getStocks(null, "   ", 10);
        verify(loadStockPort).search(eq(null), eq(null), eq(10));
    }

    @Test
    void getStock_은_최신시세를_붙여_스냅샷을_돌려준다() {
        StockQuote latest = new StockQuote(1L, "005930", LocalDate.of(2026, 7, 7),
                new BigDecimal("78000.00"), null, null, null, null, null,
                null, null, null, null, ValueSource.KRX, null);
        when(loadStockPort.findByCode("005930")).thenReturn(Optional.of(samsung));
        when(loadStockQuotePort.findLatest("005930")).thenReturn(Optional.of(latest));

        StockSnapshot snapshot = service.getStock("005930");

        assertThat(snapshot.stock()).isEqualTo(samsung);
        assertThat(snapshot.latest()).isEqualTo(latest);
    }

    @Test
    void getStock_은_없는_종목이면_404예외() {
        when(loadStockPort.findByCode("999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStock("999999"))
                .isInstanceOf(StockNotFoundException.class);
    }

    @Test
    void getSeries_는_없는_종목이면_404예외() {
        when(loadStockPort.findByCode("999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSeries("999999", null, null))
                .isInstanceOf(StockNotFoundException.class);
    }

    @Test
    void getSeries_는_from_이_to_보다_뒤면_400예외() {
        when(loadStockPort.findByCode("005930")).thenReturn(Optional.of(samsung));

        assertThatThrownBy(() -> service.getSeries("005930",
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSeries_는_기간을_넘겨_조회한다() {
        when(loadStockPort.findByCode("005930")).thenReturn(Optional.of(samsung));
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 7, 1);
        when(loadStockQuotePort.findSeries("005930", from, to)).thenReturn(List.of());

        service.getSeries("005930", from, to);

        verify(loadStockQuotePort).findSeries("005930", from, to);
    }
}
