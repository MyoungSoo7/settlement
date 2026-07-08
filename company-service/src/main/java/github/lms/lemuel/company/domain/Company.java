package github.lms.lemuel.company.domain;

import java.util.Objects;

/**
 * 뉴스·평판 조회 대상 기업.
 *
 * <p>비즈니스 키는 종목코드(stockCode, 6자리)다 — financial-statements-service 와 공용 식별자
 * (ADR 0023). DART 고유번호(corpCode, 8자리)는 시드 단계에서 알 수 없어 nullable.
 */
public class Company {

    private final String stockCode;
    private final String corpCode;
    private final String name;
    private final String market;

    public Company(String stockCode, String corpCode, String name, String market) {
        if (stockCode == null || stockCode.length() != 6) {
            throw new IllegalArgumentException("종목코드는 6자리여야 합니다: " + stockCode);
        }
        if (corpCode != null && corpCode.length() != 8) {
            throw new IllegalArgumentException("DART 고유번호는 8자리여야 합니다: " + corpCode);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("기업명은 필수입니다");
        }
        this.stockCode = stockCode;
        this.corpCode = corpCode;
        this.name = name;
        this.market = market == null || market.isBlank() ? "KOSPI" : market;
    }

    public String stockCode() {
        return stockCode;
    }

    public String corpCode() {
        return corpCode;
    }

    public String name() {
        return name;
    }

    public String market() {
        return market;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Company other && stockCode.equals(other.stockCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stockCode);
    }
}
