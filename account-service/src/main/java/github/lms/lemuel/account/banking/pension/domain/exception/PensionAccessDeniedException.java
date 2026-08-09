package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 본인 계약이 아닌 퇴직연금에 대한 조작·조회 시도 (IDOR 차단).
 *
 * <p>가입자 식별자는 요청 본문·경로가 아니라 JWT 주체에서만 파생되며, 그 값과 계약의 가입자가
 * 다르면 이 예외로 닫는다. {@code ErrorCode.ACCESS_DENIED}(403) 로 매핑된다 —
 * 404 로 바꾸면 계약 존재 여부가 새므로 굳이 감추지 않고 권한 부재로 명시한다.
 */
public class PensionAccessDeniedException extends AccountDomainException {

    private final Long pensionId;

    public PensionAccessDeniedException(Long pensionId) {
        super(ErrorCode.ACCESS_DENIED, "본인의 퇴직연금 계약이 아닙니다: " + pensionId);
        this.pensionId = pensionId;
    }

    public Long getPensionId() {
        return pensionId;
    }
}
