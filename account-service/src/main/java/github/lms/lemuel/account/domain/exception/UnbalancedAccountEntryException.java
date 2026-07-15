package github.lms.lemuel.account.domain.exception;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 전표 구성 균형 위반 — 한 전표의 차변·대변 계정이 같으면 균형 분개가 성립하지 않는다.
 *
 * <p>{@code ErrorCode.UNBALANCED_ACCOUNT_ENTRY}(400) 로 매핑된다 — 기존 {@code INVALID_ARGUMENT}
 * 와 동일한 400 상태를 유지하되 계정계 전용 코드를 카탈로그에서 부여한다(account 는 소비 전용이라 웹 노출 경로 없음).
 * 충돌한 계정과목을 {@link #getAccount()} 로 보존한다.
 */
public class UnbalancedAccountEntryException extends AccountDomainException {

    private final transient GlAccount account;

    public UnbalancedAccountEntryException(GlAccount account) {
        super(ErrorCode.UNBALANCED_ACCOUNT_ENTRY, "차변과 대변 계정은 달라야 합니다: " + account);
        this.account = account;
    }

    public GlAccount getAccount() {
        return account;
    }
}
