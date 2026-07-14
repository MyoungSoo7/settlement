package github.lms.lemuel.account.application.port.in;

import github.lms.lemuel.account.domain.TrialBalance;

/**
 * 전사 시산표 조회 역할 (원장 정합 축).
 *
 * <p>{@link AccountQueryUseCase} 의 응집 축 중 하나. 시산표만 검증하는 소비처는 이 역할만 의존하면
 * 된다(ISP).
 */
public interface TrialBalanceQuery {

    /** 전사 시산표. */
    TrialBalance trialBalance();
}
