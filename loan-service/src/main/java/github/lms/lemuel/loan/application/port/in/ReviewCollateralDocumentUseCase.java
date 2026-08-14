package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CollateralDocument;

/**
 * 담보서류 운영자 리뷰 유스케이스 — NEEDS_REVIEW(신뢰도 미달·선순위/평가기준일 판독 불가)를
 * 육안 대조로 종결한다.
 */
public interface ReviewCollateralDocumentUseCase {

    CollateralDocument review(ReviewCollateralDocumentCommand command);

    /**
     * @param documentId 서류 식별자
     * @param reviewerId 리뷰어 userId (운영자 — 컨트롤러가 권한 대조)
     * @param matched    true=대사 확정(MATCHED) / false=반려(MISMATCHED)
     * @param note       육안 대조 근거
     */
    record ReviewCollateralDocumentCommand(Long documentId, Long reviewerId, boolean matched, String note) {
    }
}
