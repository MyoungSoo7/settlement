package github.lms.lemuel.insurance.domain.exception;

/**
 * 일반지급 산출·생성 입력이 유효하지 않음 — 음수 보험료, 역전된 일자,
 * 12를 나누지 못하는 납입주기, 0원 이하 지급 생성 시도 등.
 */
public class InvalidGeneralPayoutException extends RuntimeException {

    public InvalidGeneralPayoutException(String message) {
        super(message);
    }
}
