package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

/**
 * 담보서류 대사 판정 결과 — {@link CollateralDocumentMatcher} 가 만들고
 * {@link CollateralDocument#applyDecision} 이 적용한다.
 *
 * @param status 도달할 상태 (MATCHED / MISMATCHED / NEEDS_REVIEW)
 * @param note   판정 근거 (MATCHED 는 null — 일치에 사유가 필요 없다)
 */
public record CollateralDocumentMatchDecision(CollateralDocumentStatus status, String note) {

    public static CollateralDocumentMatchDecision matched() {
        return new CollateralDocumentMatchDecision(CollateralDocumentStatus.MATCHED, null);
    }

    public static CollateralDocumentMatchDecision mismatched(String note) {
        return new CollateralDocumentMatchDecision(CollateralDocumentStatus.MISMATCHED, requireNote(note));
    }

    public static CollateralDocumentMatchDecision needsReview(String note) {
        return new CollateralDocumentMatchDecision(CollateralDocumentStatus.NEEDS_REVIEW, requireNote(note));
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new LoanInvariantViolationException("불일치·리뷰 판정에는 근거가 필수입니다");
        }
        return note;
    }
}
