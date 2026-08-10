package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 적금 계약 조건 불변식 위반 — 상품 유형과 맞지 않는 금액 필드, 비정상 기간·이율, 뒤집힌 일자 등.
 *
 * <p>{@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다. 계정계 카탈로그에 적금 전용 코드가 아직
 * 없어 공통 400 코드를 재사용한다(shared-common 은 이 작업 범위 밖) — 사유는 메시지로 구분한다.
 */
public class InvalidSavingsTermsException extends AccountSavingsDomainException {

    public InvalidSavingsTermsException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
