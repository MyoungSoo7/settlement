package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.domain.CorporateLoan;
import github.lms.lemuel.loan.domain.CorporateLoanStatus;

import java.math.BigDecimal;

/**
 * 기업 신용대출 응답.
 */
public record CorporateLoanResponse(
        Long id,
        String stockCode,
        String corpName,
        BigDecimal principal,
        BigDecimal fee,
        BigDecimal outstanding,
        int termDays,
        int creditScore,
        String creditGrade,
        CorporateLoanStatus status) {

    public static CorporateLoanResponse from(CorporateLoan loan) {
        return new CorporateLoanResponse(
                loan.getId(),
                loan.getStockCode(),
                loan.getCorpName(),
                loan.getPrincipal(),
                loan.getFee(),
                loan.getOutstanding(),
                loan.getTermDays(),
                loan.getCreditScore(),
                loan.getCreditGrade(),
                loan.getStatus());
    }
}
