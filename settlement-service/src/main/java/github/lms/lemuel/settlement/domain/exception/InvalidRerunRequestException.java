package github.lms.lemuel.settlement.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 정산 배치 재실행 요청의 사전조건 위반 — 미래 일자, 허용 소급 범위 초과, 필수값 누락, 설정 오류.
 *
 * <p>운영자 입력 오류이므로 {@link ErrorCode#INVALID_PARAMETER}(400) 로 매핑된다. generic
 * {@code IllegalArgumentException} 대신 타입 예외를 쓰는 이유는 금융 도메인의 실패 사유가
 * 스택트레이스가 아니라 <b>타입</b>으로 구분되어야 감사·알람에서 분류 가능하기 때문이다.
 */
public class InvalidRerunRequestException extends SettlementDomainException {

    public InvalidRerunRequestException(String message) {
        super(ErrorCode.INVALID_PARAMETER, message);
    }
}
