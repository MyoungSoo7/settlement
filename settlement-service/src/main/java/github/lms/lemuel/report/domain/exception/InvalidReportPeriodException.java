package github.lms.lemuel.report.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 집계할 수 없는 조회 기간 (역전·누락·상한 초과).
 *
 * <p>사용자 입력으로 도달하는 상태라 400 이 옳다 — {@link ReportDomainException} 을 거쳐
 * 공통 {@code GlobalExceptionHandler} 가 매핑한다. 이 경계를 도메인에 두는 이유는
 * "무엇이 집계 가능한 기간인가"가 화면이 아니라 리포트 도메인의 규칙이기 때문이다.
 */
public class InvalidReportPeriodException extends ReportDomainException {

    public InvalidReportPeriodException(String message) {
        super(ErrorCode.INVALID_PARAMETER, message);
    }
}
