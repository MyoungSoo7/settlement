package github.lms.lemuel.settlement.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 정산 도메인 불변식 위반 — 생성/환불/보류/대사 반영의 입력·값 규칙을 어겼다
 * (paymentId 양수, 결제금액 양수, settlementDate 필수, 환불/보류/clawback 금액 규칙, 보류율 0~1 등).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class SettlementInvariantViolationException extends SettlementDomainException {

    public SettlementInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
