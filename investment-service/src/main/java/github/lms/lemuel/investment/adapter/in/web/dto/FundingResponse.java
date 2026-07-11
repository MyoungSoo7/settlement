package github.lms.lemuel.investment.adapter.in.web.dto;

import github.lms.lemuel.investment.domain.SellerFunding;

import java.math.BigDecimal;

/** 셀러 투자 가용 재원 응답. */
public record FundingResponse(
        Long sellerId,
        BigDecimal confirmedTotal,
        BigDecimal investedTotal,
        BigDecimal available) {

    public static FundingResponse from(SellerFunding funding) {
        return new FundingResponse(
                funding.sellerId(),
                funding.confirmedTotal(),
                funding.investedTotal(),
                funding.available());
    }
}
