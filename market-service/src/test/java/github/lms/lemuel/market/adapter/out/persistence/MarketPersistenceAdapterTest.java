package github.lms.lemuel.market.adapter.out.persistence;

import github.lms.lemuel.market.domain.Market;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockQuote;
import github.lms.lemuel.market.domain.ValueSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketPersistenceAdapterTest {

    private StockRepository stockRepository;
    private StockQuoteRepository quoteRepository;
    private MarketPersistenceAdapter adapter;

    private final Stock samsung = new Stock("005930", "KR7005930003", "삼성전자", Market.KOSPI, Instant.now());

    @BeforeEach
    void setUp() {
        stockRepository = mock(StockRepository.class);
        quoteRepository = mock(StockQuoteRepository.class);
        adapter = new MarketPersistenceAdapter(stockRepository, quoteRepository);
    }

    @Test
    void search_는_market_와_name_조합에_따라_분기한다() {
        when(stockRepository.findByMarketAndNameContainingIgnoreCaseOrderByNameAsc(any(), any(), any()))
                .thenReturn(List.of(StockJpaEntity.fromDomain(samsung)));
        assertThat(adapter.search(Market.KOSPI, "삼성", 10)).hasSize(1);

        when(stockRepository.findByMarketOrderByNameAsc(any(), any())).thenReturn(List.of());
        assertThat(adapter.search(Market.KOSPI, null, 10)).isEmpty();

        when(stockRepository.findByNameContainingIgnoreCaseOrderByNameAsc(any(), any())).thenReturn(List.of());
        assertThat(adapter.search(null, "삼성", 10)).isEmpty();

        when(stockRepository.findByOrderByNameAsc(any())).thenReturn(List.of());
        assertThat(adapter.search(null, null, 10)).isEmpty();
    }

    @Test
    void findByCode_와_findLatest_findSeries() {
        when(stockRepository.findById("005930")).thenReturn(Optional.of(StockJpaEntity.fromDomain(samsung)));
        assertThat(adapter.findByCode("005930")).isPresent();

        StockQuoteJpaEntity q = StockQuoteJpaEntity.fromDomain(quote());
        when(quoteRepository.findFirstByStockCodeOrderByBaseDateDesc("005930")).thenReturn(Optional.of(q));
        assertThat(adapter.findLatest("005930")).isPresent();

        when(quoteRepository.findByStockCodeAndBaseDateBetweenOrderByBaseDateAsc(eq("005930"), any(), any()))
                .thenReturn(List.of(q));
        assertThat(adapter.findSeries("005930", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))).hasSize(1);
    }

    @Test
    void upsertStock_는_기존이면_적용해_저장한다() {
        StockJpaEntity existing = StockJpaEntity.fromDomain(samsung);
        when(stockRepository.findById("005930")).thenReturn(Optional.of(existing));

        adapter.upsertStock(new Stock("005930", "KR7005930003", "삼성전자우", Market.KOSPI, null));

        verify(stockRepository).save(existing);
        assertThat(existing.toDomain().name()).isEqualTo("삼성전자우");
    }

    @Test
    void upsertStock_는_없으면_신규저장한다() {
        when(stockRepository.findById("000660")).thenReturn(Optional.empty());

        adapter.upsertStock(new Stock("000660", null, "SK하이닉스", Market.KOSPI, null));

        ArgumentCaptor<StockJpaEntity> captor = ArgumentCaptor.forClass(StockJpaEntity.class);
        verify(stockRepository).save(captor.capture());
        assertThat(captor.getValue().toDomain().stockCode()).isEqualTo("000660");
    }

    @Test
    void upsertQuote_는_기존이면_적용해_저장한다() {
        StockQuote quote = quote();
        StockQuoteJpaEntity existing = StockQuoteJpaEntity.fromDomain(quote);
        when(quoteRepository.findByStockCodeAndBaseDate("005930", quote.baseDate()))
                .thenReturn(Optional.of(existing));

        adapter.upsertQuote(quote);

        verify(quoteRepository).save(existing);
    }

    @Test
    void upsertQuote_는_없으면_신규저장한다() {
        when(quoteRepository.findByStockCodeAndBaseDate(eq("000660"), any())).thenReturn(Optional.empty());

        adapter.upsertQuote(new StockQuote(null, "000660", LocalDate.of(2026, 7, 7),
                new BigDecimal("180000"), null, null, null, null, null,
                null, null, null, null, ValueSource.KRX, null));

        verify(quoteRepository).save(any(StockQuoteJpaEntity.class));
    }

    private static StockQuote quote() {
        return new StockQuote(1L, "005930", LocalDate.of(2026, 7, 7),
                new BigDecimal("78000.00"), new BigDecimal("77000"), new BigDecimal("79000"),
                new BigDecimal("76000"), new BigDecimal("1000"), new BigDecimal("1.30"),
                BigInteger.valueOf(1000), BigInteger.valueOf(78_000_000), BigInteger.valueOf(5_000_000),
                BigInteger.valueOf(465_000_000), ValueSource.KRX, Instant.now());
    }
}
