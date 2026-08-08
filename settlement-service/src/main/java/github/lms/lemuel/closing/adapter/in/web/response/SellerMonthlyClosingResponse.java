package github.lms.lemuel.closing.adapter.in.web.response;

import github.lms.lemuel.closing.domain.SellerMonthlyClosing;

import java.math.BigDecimal;

/** 셀러 월 마트 행 응답. */
public record SellerMonthlyClosingResponse(Long sellerId, long settlementCount,
                                           BigDecimal grossAmount, BigDecimal refundedAmount,
                                           BigDecimal commissionAmount, BigDecimal holdbackAmount,
                                           BigDecimal netAmount) {

    public static SellerMonthlyClosingResponse from(SellerMonthlyClosing row) {
        return new SellerMonthlyClosingResponse(row.getSellerId(), row.getSettlementCount(),
                row.getGrossAmount(), row.getRefundedAmount(), row.getCommissionAmount(),
                row.getHoldbackAmount(), row.getNetAmount());
    }
}
