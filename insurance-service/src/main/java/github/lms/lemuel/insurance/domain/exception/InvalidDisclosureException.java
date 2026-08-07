package github.lms.lemuel.insurance.domain.exception;

/**
 * 상품설명서 교부 기록 생성 실패 — 필수 항목 누락·채널 불변식 위반 등.
 */
public class InvalidDisclosureException extends RuntimeException {

    public InvalidDisclosureException(String message) {
        super(message);
    }
}
