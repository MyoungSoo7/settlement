package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.application.port.in.EvaluateCorporateCreditUseCase.CorporateCreditView;

import java.math.BigDecimal;

/**
 * 기업 신용평가·한도 조회 응답.
 */
public record CorporateCreditResponse(
        String stockCode,
        String corpName,
        String market,
        Integer fiscalYear,
        int creditScore,
        String creditGrade,
        BigDecimal limit,
        BigDecimal debtRatio,
        BigDecimal operatingMargin,
        BigDecimal roa,
        String reputationGrade) {

    public static CorporateCreditResponse from(CorporateCreditView v) {
        return new CorporateCreditResponse(
                v.stockCode(),
                v.corpName(),
                v.market(),
                v.fiscalYear(),
                v.creditScore(),
                v.creditGrade(),
                v.limit(),
                v.debtRatio(),
                v.operatingMargin(),
                v.roa(),
                v.reputationGrade());
    }
}
