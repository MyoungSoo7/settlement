package github.lms.lemuel.insurance.domain.exception;

/**
 * 청약서류 미존재 — 웹 어댑터가 404 로 매핑한다.
 */
public class ApplicationDocumentNotFoundException extends RuntimeException {

    public ApplicationDocumentNotFoundException(Long documentId) {
        super("청약서류를 찾을 수 없습니다: documentId=" + documentId);
    }
}
