package github.lms.lemuel.market.application.service;

import github.lms.lemuel.market.application.port.in.SyncQuotesUseCase;
import github.lms.lemuel.market.application.port.in.SyncResult;
import github.lms.lemuel.market.application.port.out.KrxClientPort;
import github.lms.lemuel.market.application.port.out.SaveQuotePort;
import github.lms.lemuel.market.domain.Stock;
import github.lms.lemuel.market.domain.StockQuote;
import github.lms.lemuel.market.domain.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * KRX 시세 수집 배치.
 *
 * <p>특정 거래일(baseDate)의 전 종목 시세를 금융위 API 에서 받아 종목 마스터와 시세를
 * {@code (stock_code, base_date)} UNIQUE upsert 로 저장(SEED → KRX 대체)한다.
 * economics 가 지표별로 API 를 도는 것과 달리, 금융위 피드는 하루치 전 종목을 한 번에 주므로
 * 날짜 1건이 곧 배치 1회다. 개별 종목 저장 실패는 집계만 하고 계속 진행한다.
 */
@Service
public class KrxSyncService implements SyncQuotesUseCase {

    private static final Logger log = LoggerFactory.getLogger(KrxSyncService.class);
    private static final int PROGRESS_LOG_INTERVAL = 500;

    private final KrxClientPort krxClient;
    private final SaveQuotePort saveQuotePort;

    public KrxSyncService(KrxClientPort krxClient, SaveQuotePort saveQuotePort) {
        this.krxClient = krxClient;
        this.saveQuotePort = saveQuotePort;
    }

    @Override
    // 시세 upsert 후 조회 캐시를 비워 정합 유지 — TTL(600s) 만 믿지 않는다.
    @CacheEvict(cacheNames = {"stockCatalog", "stockSnapshots", "stockSeries"}, allEntries = true)
    public SyncResult syncQuotes(LocalDate baseDate) {
        requireConfigured();
        if (baseDate == null) {
            throw new IllegalArgumentException("baseDate 은(는) 필수입니다");
        }
        List<KrxClientPort.StockPrice> prices = krxClient.fetchQuotes(baseDate);

        int scanned = 0;
        int upserted = 0;
        int skipped = 0;
        int failed = 0;
        for (KrxClientPort.StockPrice price : prices) {
            scanned++;
            if (price.market() == null || price.closePrice() == null) {
                skipped++;   // 시장구분 불명/종가 결측 — 저장 스킵
                continue;
            }
            try {
                saveQuotePort.upsertStock(new Stock(
                        price.stockCode(), price.isin(), price.name(), price.market(), null));
                saveQuotePort.upsertQuote(new StockQuote(
                        null, price.stockCode(), price.baseDate(),
                        price.closePrice(), price.openPrice(), price.highPrice(), price.lowPrice(),
                        price.priorDayDiff(), price.fluctuationRate(),
                        price.volume(), price.tradeAmount(), price.listedShares(), price.marketCap(),
                        ValueSource.KRX, null));
                upserted++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("시세 저장 실패 stockCode={}: {}", price.stockCode(), e.getMessage());
            }
            logProgress(scanned, prices.size(), upserted, failed);
        }
        log.info("KRX 시세 수집 완료 baseDate={} — 스캔 {}, upsert {}, 스킵 {}, 실패 {}",
                baseDate, scanned, upserted, skipped, failed);
        return new SyncResult(scanned, upserted, skipped, failed);
    }

    private void requireConfigured() {
        if (!krxClient.isConfigured()) {
            throw new IllegalStateException("KRX API 키가 설정되지 않았습니다 (KRX_API_KEY)");
        }
    }

    private void logProgress(int scanned, int total, int upserted, int failed) {
        if (scanned % PROGRESS_LOG_INTERVAL == 0) {
            log.info("KRX 시세 수집 진행 {}/{} (upsert {}, 실패 {})", scanned, total, upserted, failed);
        }
    }
}
