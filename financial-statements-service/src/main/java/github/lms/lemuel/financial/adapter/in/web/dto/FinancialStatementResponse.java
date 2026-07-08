package github.lms.lemuel.financial.adapter.in.web.dto;

import github.lms.lemuel.financial.domain.FinancialStatement;

import java.math.BigDecimal;

/** 요약 재무제표 + 파생지표(%). 파생지표는 계산 불가 시 null → 화면에서 N/A. */
public record FinancialStatementResponse(
        int fiscalYear,
        String fsDivision,
        String currency,
        BigDecimal revenue,
        BigDecimal operatingProfit,
        BigDecimal netIncome,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity,
        BigDecimal operatingMargin,
        BigDecimal netMargin,
        BigDecimal debtRatio,
        BigDecimal equityRatio,
        BigDecimal roa,
        String source
) {

    public static FinancialStatementResponse from(FinancialStatement s) {
        return new FinancialStatementResponse(
                s.fiscalYear(),
                s.fsDivision().name(),
                s.currency(),
                s.revenue(),
                s.operatingProfit(),
                s.netIncome(),
                s.totalAssets(),
                s.totalLiabilities(),
                s.totalEquity(),
                s.operatingMargin(),
                s.netMargin(),
                s.debtRatio(),
                s.equityRatio(),
                s.roa(),
                s.source().name());
    }
}
