package github.lms.lemuel.insurance.application.port.in;

import github.lms.lemuel.insurance.domain.ApplicationDocument;

/**
 * 청약서류 리뷰 유스케이스 — NEEDS_REVIEW(신뢰도 미달·보장금액/청약일 판독 불가)를 육안 대조로 종결한다.
 */
public interface ReviewApplicationDocumentUseCase {

    ApplicationDocument review(ReviewDocumentCommand command);

    /**
     * @param documentId 서류 식별자
     * @param reviewerId 리뷰어 식별자 — JWT 주체에서 파생
     * @param matched    true=대사 확정(MATCHED) / false=반려(MISMATCHED)
     * @param note       육안 대조 근거
     */
    record ReviewDocumentCommand(Long documentId, String reviewerId, boolean matched, String note) {
    }
}
