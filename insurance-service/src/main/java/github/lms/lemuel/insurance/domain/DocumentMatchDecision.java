package github.lms.lemuel.insurance.domain;

import github.lms.lemuel.insurance.domain.exception.InvalidApplicationDocumentException;

/**
 * 청약서류 대사 판정 결과 — {@link ApplicationDocumentMatcher} 가 만들고
 * {@link ApplicationDocument#applyDecision} 이 적용한다.
 *
 * @param status 도달할 상태 (MATCHED / MISMATCHED / NEEDS_REVIEW)
 * @param note   판정 근거 (MATCHED 는 null — 일치에 사유가 필요 없다)
 */
public record DocumentMatchDecision(ApplicationDocumentStatus status, String note) {

    public static DocumentMatchDecision matched() {
        return new DocumentMatchDecision(ApplicationDocumentStatus.MATCHED, null);
    }

    public static DocumentMatchDecision mismatched(String note) {
        return new DocumentMatchDecision(ApplicationDocumentStatus.MISMATCHED, requireNote(note));
    }

    public static DocumentMatchDecision needsReview(String note) {
        return new DocumentMatchDecision(ApplicationDocumentStatus.NEEDS_REVIEW, requireNote(note));
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new InvalidApplicationDocumentException("불일치·리뷰 판정에는 근거가 필수입니다");
        }
        return note;
    }
}
