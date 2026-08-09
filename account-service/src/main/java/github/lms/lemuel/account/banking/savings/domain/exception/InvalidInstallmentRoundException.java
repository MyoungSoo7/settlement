package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 회차 번호가 계약 기간(1..termMonths) 밖 — 존재하지 않는 회차에 납입하려는 요청.
 *
 * <p>{@code ErrorCode.INVALID_ARGUMENT}(400). 위반한 회차·허용 상한을 보존해 진단에 쓴다.
 */
public class InvalidInstallmentRoundException extends AccountSavingsDomainException {

    private final int round;
    private final int termMonths;

    public InvalidInstallmentRoundException(int round, int termMonths) {
        super(ErrorCode.INVALID_ARGUMENT,
                "회차는 1..." + termMonths + " 범위여야 합니다: " + round);
        this.round = round;
        this.termMonths = termMonths;
    }

    public int getRound() {
        return round;
    }

    public int getTermMonths() {
        return termMonths;
    }
}
