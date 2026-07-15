package github.lms.lemuel.pgreconciliation.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * PG 대사 도메인 불변식 위반 — 필수값 규칙을 어겼다(예: 거절 사유 필수 — 감사 추적용).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class PgReconciliationInvariantViolationException extends PgReconciliationDomainException {

    public PgReconciliationInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
