package github.lms.lemuel.financial.domain;

import java.util.Objects;

/**
 * 상장 기업(코스피/코스닥).
 *
 * <p>비즈니스 키는 종목코드(stockCode, 6자리)다 — 시드 단계에서는 DART 고유번호(corpCode, 8자리)를
 * 알 수 없으므로 corpCode 는 nullable 이고, DART 기업 동기화가 나중에 채운다.
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

    /** DART 동기화 결과 반영 — 종목코드 기준으로 기업명/고유번호를 갱신한 새 인스턴스. */
    public Company mergedWith(String newCorpCode, String newName) {
        return new Company(stockCode, newCorpCode,
                newName == null || newName.isBlank() ? name : newName, market);
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

    public boolean hasCorpCode() {
        return corpCode != null;
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
