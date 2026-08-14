package github.lms.lemuel.tax.application.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * OCR 을 쓸 수 없다 — 미구성(키 없음)·벤더 호출 실패·응답 파손. 503 으로 나간다.
 *
 * <p><b>폴백을 두지 않는 것이 계약이다</b>: 값을 지어내거나 부분 결과를 저장하면 세무 대사에 거짓 근거가
 * 남는다. 실패는 실패로 드러내고 스캔 행을 만들지 않는다(ai-service 의 LLM 실패 계약과 동형).
 */
public class TaxOcrUnavailableException extends BusinessException {

    public TaxOcrUnavailableException(String message) {
        super(ErrorCode.TAX_OCR_UNAVAILABLE, message);
    }

    public TaxOcrUnavailableException(String message, Throwable cause) {
        super(ErrorCode.TAX_OCR_UNAVAILABLE, message, cause);
    }
}
