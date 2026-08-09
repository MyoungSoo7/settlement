package github.lms.lemuel.account.banking.timedeposit.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 남의 정기예금 계좌에 대한 조회·해지 시도 (IDOR 차단).
 *
 * <p>예금주 식별자는 요청(경로·본문·쿼리)이 아니라 <b>JWT 주체</b>에서만 파생되며, 적재된 계좌의
 * {@code depositorId} 와 일치하지 않으면 이 예외로 끊는다. {@code ErrorCode.ACCESS_DENIED}(403) 로
 * 매핑되어 공통 {@code GlobalExceptionHandler} 가 403 을 응답한다.
 */
public class TimeDepositAccessDeniedException extends AccountDomainException {

    private final transient Long depositId;

    public TimeDepositAccessDeniedException(Long depositId) {
        this(depositId, "본인의 정기예금이 아닙니다: " + depositId);
    }

    private TimeDepositAccessDeniedException(Long depositId, String message) {
        super(ErrorCode.ACCESS_DENIED, message);
        this.depositId = depositId;
    }

    /**
     * JWT 에 {@code userId} 가 없어 예금주를 특정할 수 없는 경우 — 구(舊) 토큰.
     * 500 폴백으로 새지 않도록 여기서 403 으로 못 박는다(어떤 계좌인지 특정되기 <b>전</b>이라 depositId 는 없다).
     */
    public static TimeDepositAccessDeniedException unidentifiedDepositor() {
        return new TimeDepositAccessDeniedException(null,
                "본인 확인이 불가한 토큰입니다 — 재로그인 후 다시 시도하세요");
    }

    public Long getDepositId() {
        return depositId;
    }
}
