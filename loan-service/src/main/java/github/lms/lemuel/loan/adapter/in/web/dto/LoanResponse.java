package github.lms.lemuel.loan.adapter.in.web.dto;

import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 선정산 대출 응답. 실행 시각·만기(dueAt)를 함께 노출해 프론트가 만기·연체 임박을 표시할 수 있게 한다
 * (실행 전/구 데이터는 null).
 */
public record LoanResponse(
        Long id,
        Long sellerId,
        BigDecimal principal,
        BigDecimal fee,
        BigDecimal outstanding,
        LoanStatus status,
        LocalDateTime disbursedAt,
        LocalDateTime dueAt) {

    public static LoanResponse from(LoanAdvance loan) {
        return new LoanResponse(
                loan.getId(),
                loan.getSellerId(),
                loan.getPrincipal(),
                loan.getFee(),
                loan.getOutstanding(),
                loan.getStatus(),
                loan.getDisbursedAt(),
                loan.getDueAt());
    }
}
