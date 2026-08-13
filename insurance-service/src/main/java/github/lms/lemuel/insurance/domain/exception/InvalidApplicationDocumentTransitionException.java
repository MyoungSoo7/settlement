package github.lms.lemuel.insurance.domain.exception;

/**
 * 청약서류 상태 전이 위반 — 종결(MATCHED/MISMATCHED) 재판정, NEEDS_REVIEW 아닌 상태의 리뷰 등.
 * 웹 어댑터가 409 로 매핑한다 (번복은 새 서류 첨부로만).
 */
public class InvalidApplicationDocumentTransitionException extends RuntimeException {

    public InvalidApplicationDocumentTransitionException(String message) {
        super(message);
    }
}
