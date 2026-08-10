package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.banking.pension.domain.BenefitType;
import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 수급 요건 미충족 — 연금은 만 55세 + 가입기간 10년, 일시금은 만 55세.
 *
 * <p>{@code ErrorCode.INVALID_STATE}(400) 로 매핑된다. 요구치와 실제치를 함께 보존해
 * "몇 년/몇 살이 모자란지"를 응답 없이도 진단할 수 있게 한다.
 */
public class BenefitEligibilityNotMetException extends AccountDomainException {

    private final BenefitType benefitType;
    private final int age;
    private final int subscribedYears;

    public BenefitEligibilityNotMetException(BenefitType benefitType, int age, int subscribedYears) {
        super(ErrorCode.INVALID_STATE,
                benefitType + " 수급 요건 미충족 — 요구(만 " + benefitType.minimumAge() + "세 이상, 가입기간 "
                        + benefitType.minimumSubscribedYears() + "년 이상), 실제(만 " + age + "세, 가입기간 "
                        + subscribedYears + "년).");
        this.benefitType = benefitType;
        this.age = age;
        this.subscribedYears = subscribedYears;
    }

    public BenefitType getBenefitType() {
        return benefitType;
    }

    public int getAge() {
        return age;
    }

    public int getSubscribedYears() {
        return subscribedYears;
    }
}
