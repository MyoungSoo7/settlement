package github.lms.lemuel.investment.adapter.in.web.dto;

import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;

import java.math.BigDecimal;

/** 투자 주문 응답. */
public record InvestmentOrderResponse(
        Long id,
        Long sellerId,
        String stockCode,
        BigDecimal amount,
        int scoreAtOrder,
        String gradeAtOrder,
        InvestmentOrderStatus status) {

    public static InvestmentOrderResponse from(InvestmentOrder order) {
        return new InvestmentOrderResponse(
                order.getId(),
                order.getSellerId(),
                order.getStockCode(),
                order.getAmount(),
                order.getScoreAtOrder(),
                order.getGradeAtOrder(),
                order.getStatus());
    }
}
