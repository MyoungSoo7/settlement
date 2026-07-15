package github.lms.lemuel.report.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 캐시플로우 리포트 도메인 불변식 위반 — 조회 기간/버킷/그래뉼래리티 입력 규칙을 어겼다
 * (from/to 필수 및 순서, granularity 필수, bucket 필수, 지원하지 않는 groupBy).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 */
public class ReportInvariantViolationException extends ReportDomainException {

    public ReportInvariantViolationException(String message) {
        super(ErrorCode.INVALID_ARGUMENT, message);
    }
}
