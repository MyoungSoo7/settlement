package github.lms.lemuel.account.domain.exception;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 전표 구성 균형 위반 — 한 전표의 차변·대변 계정이 같으면 균형 분개가 성립하지 않는다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 충돌한 계정과목을 {@link #getAccount()} 로 보존한다.
 */
public class UnbalancedAccountEntryException extends AccountDomainException {

    private final transient GlAccount account;

    public UnbalancedAccountEntryException(GlAccount account) {
        super(ErrorCode.INVALID_ARGUMENT, "차변과 대변 계정은 달라야 합니다: " + account);
        this.account = account;
    }

    public GlAccount getAccount() {
        return account;
    }
}
