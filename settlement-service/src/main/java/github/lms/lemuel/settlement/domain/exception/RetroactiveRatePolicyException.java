package github.lms.lemuel.settlement.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 이미 정산이 생성된 구간에 요율 정책을 소급 등록하려 함 (ADR 0032 결정 ⑤).
 *
 * <p>생성된 정산은 요율 스냅샷이라 재계산되지 않는다. 정책만 바꾸면 장부와 정책이 어긋난 채 남으므로
 * 등록 자체를 막는다. 진짜 소급 보정은 {@code SettlementAdjustment}(ADR 0004)가 정식 경로다.
 */
public class RetroactiveRatePolicyException extends BusinessException {

    public RetroactiveRatePolicyException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
