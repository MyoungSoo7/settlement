package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 제도가 중도인출 자체를 허용하지 않는다 — 실질적으로 DB형 전용 거절이다.
 *
 * <p>DB형은 급여가 사전 확정된 사용자 적립 재원이라 가입자가 인출할 수 있는 개인 계정이 존재하지 않는다.
 * 법정 사유({@code MidWithdrawalReason})를 아무리 정확히 대도 결과는 같다 — 사유 이전에 제도가 막는다.
 * {@code ErrorCode.INVALID_STATE}(400) 로 매핑된다.
 */
public class MidWithdrawalNotPermittedException extends AccountDomainException {

    private final PensionScheme scheme;

    public MidWithdrawalNotPermittedException(PensionScheme scheme) {
        super(ErrorCode.INVALID_STATE, scheme + "형 퇴직연금은 중도인출이 허용되지 않습니다.");
        this.scheme = scheme;
    }

    public PensionScheme getScheme() {
        return scheme;
    }
}
