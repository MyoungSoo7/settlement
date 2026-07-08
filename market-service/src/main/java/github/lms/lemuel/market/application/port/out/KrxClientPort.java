package github.lms.lemuel.market.application.port.out;

import github.lms.lemuel.market.domain.Market;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

public interface KrxClientPort {

    boolean isConfigured();

    /**
     * 특정 거래일 전 종목 시세 조회(페이지네이션은 어댑터 내부에서 완주).
     * 휴장일/데이터 없음은 빈 리스트 — 에러 아님.
     */
    List<StockPrice> fetchQuotes(LocalDate baseDate);

    /** 금융위 주식시세정보 1행 — 종목 마스터(코드/ISIN/이름/시장) + 당일 시세를 함께 담는다. */
    record StockPrice(String stockCode, String isin, String name, Market market,
                      LocalDate baseDate, BigDecimal closePrice, BigDecimal openPrice,
                      BigDecimal highPrice, BigDecimal lowPrice,
                      BigDecimal priorDayDiff, BigDecimal fluctuationRate,
                      BigInteger volume, BigInteger tradeAmount,
                      BigInteger listedShares, BigInteger marketCap) { }
}
