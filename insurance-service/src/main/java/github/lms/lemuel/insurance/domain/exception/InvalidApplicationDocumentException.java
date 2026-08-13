package github.lms.lemuel.insurance.domain.exception;

/**
 * 청약서류 입력 검증 위반 — 필수값 누락·범위 밖 값·빈 파일 등. 웹 어댑터가 400 으로 매핑한다.
 */
public class InvalidApplicationDocumentException extends IllegalArgumentException {

    public InvalidApplicationDocumentException(String message) {
        super(message);
    }
}
