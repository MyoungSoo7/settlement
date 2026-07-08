package github.lms.lemuel.market.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 종목의 특정 거래일 시세 1건.
 *
 * <p>전일 대비(priorDayDiff)·등락률(fluctuationRate)은 금융위 피드가 이미 제공하므로
 * economics 처럼 이전 관측치에서 파생 계산하지 않고 그대로 보존한다. 금액은 전부 BigDecimal,
 * 수량(거래량·상장주식수)·원 단위 총액(거래대금·시가총액)은 BigInteger 로 정밀도를 지킨다.
 */
public record StockQuote(Long id, String stockCode, LocalDate baseDate,
                         BigDecimal closePrice, BigDecimal openPrice,
                         BigDecimal highPrice, BigDecimal lowPrice,
                         BigDecimal priorDayDiff, BigDecimal fluctuationRate,
                         BigInteger volume, BigInteger tradeAmount,
                         BigInteger listedShares, BigInteger marketCap,
                         ValueSource source, Instant syncedAt) {

    public StockQuote {
        if (stockCode == null || stockCode.isBlank()) {
            throw new IllegalArgumentException("stockCode 은(는) 필수입니다");
        }
        if (baseDate == null) {
            throw new IllegalArgumentException("baseDate 은(는) 필수입니다");
        }
        if (closePrice == null) {
            throw new IllegalArgumentException("closePrice 은(는) 필수입니다");
        }
        if (source == null) {
            throw new IllegalArgumentException("source 은(는) 필수입니다");
        }
    }
}
