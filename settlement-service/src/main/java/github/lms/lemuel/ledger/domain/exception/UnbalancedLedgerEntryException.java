package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.ledger.domain.AccountType;

/**
 * 원장 구성 균형 위반 — 한 분개의 차변·대변 계정이 같으면 복식부기 균형이 성립하지 않는다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 충돌한 계정과목을 {@link #getAccount()} 로 보존한다.
 */
public class UnbalancedLedgerEntryException extends LedgerInvariantViolationException {

    private final transient AccountType account;

    public UnbalancedLedgerEntryException(AccountType account) {
        super("debit 과 credit 은 서로 다른 계정이어야 합니다: " + account);
        this.account = account;
    }

    public AccountType getAccount() {
        return account;
    }
}
