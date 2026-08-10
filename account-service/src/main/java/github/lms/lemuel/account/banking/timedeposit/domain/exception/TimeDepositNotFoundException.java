package github.lms.lemuel.account.banking.timedeposit.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 존재하지 않는 정기예금 계좌 참조.
 *
 * <p>{@code ErrorCode.TIME_DEPOSIT_NOT_FOUND}(404) 로 매핑한다 — 형제 도메인
 * (SECURED_LOAN_NOT_FOUND·INVESTMENT_NOT_FOUND·CARD_NOT_FOUND)과 같은 상품별 전용 코드 규약을 따른다.
 * 400 으로 대용하면 클라이언트가 "요청이 틀렸다"와 "리소스가 없다"를 구분할 수 없다.
 *
 * <p>열거 공격 대비로 상태 코드를 뭉개지는 않는다 — 소유자 불일치는 이미 403 이라 400/403 조합에서도
 * 존재 여부가 드러났고, 코드만 낮춰선 아무것도 막지 못한다. 진짜로 막으려면 남의 계좌도 404 로
 * 응답해야 하는데, 그건 이 리포의 기존 소유권 응답 규약(403)과 어긋나므로 별도 결정 사항으로 남긴다.
 */
public class TimeDepositNotFoundException extends AccountDomainException {

    private final transient Long depositId;

    public TimeDepositNotFoundException(Long depositId) {
        super(ErrorCode.TIME_DEPOSIT_NOT_FOUND, "정기예금을 찾을 수 없습니다: " + depositId);
        this.depositId = depositId;
    }

    public Long getDepositId() {
        return depositId;
    }
}
