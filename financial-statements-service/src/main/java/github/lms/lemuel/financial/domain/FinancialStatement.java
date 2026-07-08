package github.lms.lemuel.financial.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 기업의 연간 요약 재무제표 (사업보고서 기준 6개 핵심 계정, 원 단위).
 *
 * <p>파생지표(이익률·부채비율 등)는 저장하지 않고 여기서 계산한다 — null 계정, 0 분모,
 * 자본잠식(자본총계 음수) 케이스에서 null 을 반환해 표시 계층이 "N/A" 처리하게 한다.
 */
public class FinancialStatement {

    private static final int MIN_YEAR = 1990;
    private static final int MAX_YEAR = 2100;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Long id;
    private final String stockCode;
    private final int fiscalYear;
    private final FsDivision fsDivision;
    private final String currency;
    private final BigDecimal revenue;
    private final BigDecimal operatingProfit;
    private final BigDecimal netIncome;
    private final BigDecimal totalAssets;
    private final BigDecimal totalLiabilities;
    private final BigDecimal totalEquity;
    private final StatementSource source;
    private final Instant syncedAt;

    public FinancialStatement(Long id, String stockCode, int fiscalYear, FsDivision fsDivision,
                              String currency, BigDecimal revenue, BigDecimal operatingProfit,
                              BigDecimal netIncome, BigDecimal totalAssets, BigDecimal totalLiabilities,
                              BigDecimal totalEquity, StatementSource source, Instant syncedAt) {
        if (stockCode == null || stockCode.length() != 6) {
            throw new IllegalArgumentException("종목코드는 6자리여야 합니다: " + stockCode);
        }
        if (fiscalYear < MIN_YEAR || fiscalYear > MAX_YEAR) {
            throw new IllegalArgumentException("사업연도 범위 초과: " + fiscalYear);
        }
        if (fsDivision == null) {
            throw new IllegalArgumentException("재무제표 구분(fsDivision)은 필수입니다");
        }
        if (source == null) {
            throw new IllegalArgumentException("데이터 출처(source)는 필수입니다");
        }
        this.id = id;
        this.stockCode = stockCode;
        this.fiscalYear = fiscalYear;
        this.fsDivision = fsDivision;
        this.currency = currency == null || currency.isBlank() ? "KRW" : currency;
        this.revenue = revenue;
        this.operatingProfit = operatingProfit;
        this.netIncome = netIncome;
        this.totalAssets = totalAssets;
        this.totalLiabilities = totalLiabilities;
        this.totalEquity = totalEquity;
        this.source = source;
        this.syncedAt = syncedAt == null ? Instant.now() : syncedAt;
    }

    /** 영업이익률(%) = 영업이익 / 매출액 × 100. 계산 불가 시 null. */
    public BigDecimal operatingMargin() {
        return ratio(operatingProfit, revenue);
    }

    /** 순이익률(%) = 당기순이익 / 매출액 × 100. 계산 불가 시 null. */
    public BigDecimal netMargin() {
        return ratio(netIncome, revenue);
    }

    /** 부채비율(%) = 부채총계 / 자본총계 × 100. 자본잠식(자본총계 ≤ 0)이면 null. */
    public BigDecimal debtRatio() {
        if (totalEquity != null && totalEquity.signum() <= 0) {
            return null;
        }
        return ratio(totalLiabilities, totalEquity);
    }

    /** 자기자본비율(%) = 자본총계 / 자산총계 × 100. 계산 불가 시 null. */
    public BigDecimal equityRatio() {
        return ratio(totalEquity, totalAssets);
    }

    /** ROA(%) = 당기순이익 / 자산총계 × 100. 계산 불가 시 null. */
    public BigDecimal roa() {
        return ratio(netIncome, totalAssets);
    }

    /**
     * 재무상태표 항등식 검증 — |자산 − (부채 + 자본)| ≤ 자산 × 허용오차.
     * 세 계정 중 하나라도 없으면 검증 불가로 false.
     */
    public boolean isBalanced(BigDecimal tolerancePct) {
        if (totalAssets == null || totalLiabilities == null || totalEquity == null
                || totalAssets.signum() == 0) {
            return false;
        }
        BigDecimal diff = totalAssets.subtract(totalLiabilities.add(totalEquity)).abs();
        BigDecimal allowed = totalAssets.abs().multiply(tolerancePct).divide(HUNDRED, 4, RoundingMode.HALF_UP);
        return diff.compareTo(allowed) <= 0;
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    public Long id() {
        return id;
    }

    public String stockCode() {
        return stockCode;
    }

    public int fiscalYear() {
        return fiscalYear;
    }

    public FsDivision fsDivision() {
        return fsDivision;
    }

    public String currency() {
        return currency;
    }

    public BigDecimal revenue() {
        return revenue;
    }

    public BigDecimal operatingProfit() {
        return operatingProfit;
    }

    public BigDecimal netIncome() {
        return netIncome;
    }

    public BigDecimal totalAssets() {
        return totalAssets;
    }

    public BigDecimal totalLiabilities() {
        return totalLiabilities;
    }

    public BigDecimal totalEquity() {
        return totalEquity;
    }

    public StatementSource source() {
        return source;
    }

    public Instant syncedAt() {
        return syncedAt;
    }
}
