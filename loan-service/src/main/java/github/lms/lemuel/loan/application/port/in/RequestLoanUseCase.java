package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.LoanAdvance;

import java.math.BigDecimal;

/**
 * 셀러의 선정산 대출 신청 인바운드 포트.
 */
public interface RequestLoanUseCase {

    LoanAdvance request(RequestLoanCommand command);

    /**
     * @param sellerId      신청 셀러
     * @param principal     선지급 신청 원금
     * @param financingDays 선지급일수(정산예정일까지) — 수수료 산정 기준
     */
    record RequestLoanCommand(Long sellerId, BigDecimal principal, int financingDays) {
    }
}
