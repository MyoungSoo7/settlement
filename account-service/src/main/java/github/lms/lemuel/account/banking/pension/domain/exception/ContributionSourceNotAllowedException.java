package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.banking.pension.domain.ContributionSource;
import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 제도가 허용하지 않는 주체의 부담금 납입 — 예: DB형에 가입자 본인 부담금, IRP 에 사용자 부담금.
 *
 * <p>DB형은 급여가 사전 확정이라 적립 책임이 전적으로 사용자에게 있고, IRP 는 사업장 자체가 없다.
 * {@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class ContributionSourceNotAllowedException extends AccountDomainException {

    private final PensionScheme scheme;
    private final ContributionSource source;

    public ContributionSourceNotAllowedException(PensionScheme scheme, ContributionSource source) {
        super(ErrorCode.INVALID_ARGUMENT,
                scheme + "형 퇴직연금은 " + source + " 부담금을 받을 수 없습니다. (허용: "
                        + scheme.allowedContributionSources() + ")");
        this.scheme = scheme;
        this.source = source;
    }

    public PensionScheme getScheme() {
        return scheme;
    }

    public ContributionSource getSource() {
        return source;
    }
}
