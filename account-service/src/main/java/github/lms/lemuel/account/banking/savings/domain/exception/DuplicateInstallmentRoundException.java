package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 같은 회차를 두 번 납입하려는 요청.
 *
 * <p>멱등성 자체는 상위(GL 자연키 {@code SV-{savingsId}-{round}} UNIQUE + savings_installments 의
 * {@code uq_savings_installment_round})가 보장한다. 그래도 <b>도메인이 먼저 거절해야</b> 하는 이유는,
 * 스키마 제약이 막아주는 시점엔 이미 애그리거트 상태(납입 누계·이자 계산 대상)가 오염돼 있기 때문이다.
 *
 * <p>{@code ErrorCode.INVALID_STATE}(400) 로 매핑된다 — 요청 형식이 아니라 계약의 현재 상태 문제다.
 */
public class DuplicateInstallmentRoundException extends AccountSavingsDomainException {

    private final int round;

    public DuplicateInstallmentRoundException(int round) {
        super(ErrorCode.INVALID_STATE, "이미 납입된 회차입니다: " + round);
        this.round = round;
    }

    public int getRound() {
        return round;
    }
}
