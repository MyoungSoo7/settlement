package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 이미 해지된 계약에 대한 납입·재해지 시도.
 *
 * <p>이중 해지를 막는 것이 자금 안전의 핵심이다 — 해지는 {@code savingsClosed} 전표(원리금 지급)를
 * 만들어내므로, 두 번 통과하면 두 번 지급된다. GL 자연키({@code SV-{savingsId}})가 최종 방어선이지만
 * 도메인이 먼저 막아야 settledInterest·payoutAmount 가 덮어써지지 않는다.
 *
 * <p>{@code ErrorCode.INVALID_STATE}(400).
 */
public class SavingsAlreadyClosedException extends AccountSavingsDomainException {

    private final Long savingsId;

    public SavingsAlreadyClosedException(Long savingsId) {
        super(ErrorCode.INVALID_STATE, "이미 해지된 적금입니다: " + savingsId);
        this.savingsId = savingsId;
    }

    public Long getSavingsId() {
        return savingsId;
    }
}
