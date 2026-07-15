package github.lms.lemuel.chargeback.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 카드사 분쟁 도메인 불변식 위반 — 금액/필수값 규칙을 어겼다
 * (amount 양수, PG_WEBHOOK 의 pgChargebackId 필수, 결정자·기각 사유·settlementId 규칙).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class ChargebackInvariantViolationException extends ChargebackDomainException {

    public ChargebackInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
