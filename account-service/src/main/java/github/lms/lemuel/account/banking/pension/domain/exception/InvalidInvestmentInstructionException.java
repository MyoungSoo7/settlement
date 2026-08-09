package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 운용지시가 성립하지 않는다 — 원리금보장 상품명이 비어 있다.
 *
 * <p>이 서브도메인의 운용지시 모델은 의도적으로 최소다(원리금보장 상품 1개, 비중 배분 없음).
 * 그래서 검증할 것도 "상품명이 실재하는가"와 이율 범위({@link InvalidPensionRateException}) 둘뿐이다.
 * {@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class InvalidInvestmentInstructionException extends AccountDomainException {

    private final String productName;

    public InvalidInvestmentInstructionException(String productName) {
        super(ErrorCode.INVALID_ARGUMENT, "원리금보장 상품명이 비어 있습니다: " + productName);
        this.productName = productName;
    }

    public String getProductName() {
        return productName;
    }
}
