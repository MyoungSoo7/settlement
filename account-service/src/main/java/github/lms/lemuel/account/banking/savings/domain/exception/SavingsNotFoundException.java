package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 존재하지 않는 적금 계약 조회·조작.
 *
 * <p>{@code ErrorCode.INSTALLMENT_SAVINGS_NOT_FOUND}(404) 로 매핑한다 — 형제 도메인
 * (SECURED_LOAN_NOT_FOUND·INVESTMENT_NOT_FOUND·CARD_NOT_FOUND)과 같은 상품별 전용 코드 규약.
 * {@code LEDGER_NOT_FOUND} 재사용은 응답 본문의 errorCode 가 "원장 항목 없음"으로 나가
 * 클라이언트가 적금 계약 부재와 GL 전표 부재를 구분하지 못하게 만든다.
 */
public class SavingsNotFoundException extends AccountSavingsDomainException {

    private final Long savingsId;

    public SavingsNotFoundException(Long savingsId) {
        super(ErrorCode.INSTALLMENT_SAVINGS_NOT_FOUND, "적금 계약을 찾을 수 없습니다: " + savingsId);
        this.savingsId = savingsId;
    }

    public Long getSavingsId() {
        return savingsId;
    }
}
