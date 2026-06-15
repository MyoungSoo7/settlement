package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;

import java.math.BigDecimal;

/**
 * 선정산 대출 응답.
 */
public record LoanResponse(
        Long id,
        Long sellerId,
        BigDecimal principal,
        BigDecimal fee,
        BigDecimal outstanding,
        LoanStatus status) {

    public static LoanResponse from(LoanAdvance loan) {
        return new LoanResponse(
                loan.getId(),
                loan.getSellerId(),
                loan.getPrincipal(),
                loan.getFee(),
                loan.getOutstanding(),
                loan.getStatus());
    }
}
