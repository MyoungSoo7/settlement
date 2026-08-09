package github.lms.lemuel.account.banking.timedeposit.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 정기예금 개설 조건 불변식 위반 — 원금·이율·기간이 상품으로 성립하지 않는다.
 *
 * <p>원금 ≤ 0, 이율이 [0,1) 밖(연 100% 이상은 이율이 아니라 배수 오입력), 기간 ≤ 0 개월,
 * 원금의 소수 자릿수가 2 초과(=GL {@code numeric(19,2)} 에 무손실로 못 들어감) 를 모두 이 예외로 거절한다.
 * 조용한 반올림·보정은 하지 않는다 — 수신 상품에서 1원 드리프트는 곧 부채 잔액 오류다.
 *
 * <p>{@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑한다. 공통 카탈로그({@code shared-common})에
 * banking 전용 코드가 아직 없어 공통 400 코드를 재사용한다 — 카탈로그에 수신 상품 코드가 추가되면
 * 이 한 줄만 교체하면 된다.
 */
public class InvalidTimeDepositTermsException extends AccountDomainException {

    public InvalidTimeDepositTermsException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
