package github.lms.lemuel.tax.domain.exception;

/**
 * 세무 불변식 위반 — 음수 금액·대사 항등식 파손·필수값 누락 등 구성적 규칙 위반 시 던진다.
 */
public class TaxInvariantViolationException extends TaxDomainException {

    public TaxInvariantViolationException(String message) {
        super(message);
    }
}
