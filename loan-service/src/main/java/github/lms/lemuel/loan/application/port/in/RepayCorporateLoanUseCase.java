package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CorporateLoan;

import java.math.BigDecimal;

/**
 * 기업 신용대출 상환 인바운드 포트. 무담보 신용대출이라 셀러 정산 saga 가 아니라
 * 명시적 상환(차감)으로 미상환잔액을 줄인다. 소유권 대조는 어댑터(컨트롤러)가 JWT 주체에서
 * 파생·강제하고, 이 유스케이스는 loanId·금액만 받는다.
 */
public interface RepayCorporateLoanUseCase {

    /**
     * 기업대출 상환. 미상환잔액에서 {@code amount} 만큼 차감(잔액 초과분은 clamp)하고,
     * 잔액이 0 이 되면 REPAID 로 전이한다. 차감>0 이면 상환 전표(현금/대출채권)를 기록한다.
     *
     * @return 상환 반영된 대출(잔액·상태 갱신)
     */
    CorporateLoan repay(RepayCorporateLoanCommand command);

    record RepayCorporateLoanCommand(Long loanId, BigDecimal amount) {
    }
}
