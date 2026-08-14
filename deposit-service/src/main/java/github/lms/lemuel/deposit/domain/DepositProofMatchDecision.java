package github.lms.lemuel.deposit.domain;

import github.lms.lemuel.deposit.domain.exception.InvalidDepositProofException;

/**
 * 예치금 증빙 대사 판정 결과 — {@link DepositProofMatcher} 가 만들고
 * {@link DepositProof#applyDecision} 이 적용한다.
 *
 * @param status 도달할 상태 (MATCHED / MISMATCHED / NEEDS_REVIEW)
 * @param note   판정 근거 (MATCHED 는 null — 일치에 사유가 필요 없다)
 */
public record DepositProofMatchDecision(DepositProofStatus status, String note) {

    public static DepositProofMatchDecision matched() {
        return new DepositProofMatchDecision(DepositProofStatus.MATCHED, null);
    }

    public static DepositProofMatchDecision mismatched(String note) {
        return new DepositProofMatchDecision(DepositProofStatus.MISMATCHED, requireNote(note));
    }

    public static DepositProofMatchDecision needsReview(String note) {
        return new DepositProofMatchDecision(DepositProofStatus.NEEDS_REVIEW, requireNote(note));
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new InvalidDepositProofException("불일치·리뷰 판정에는 근거가 필수입니다");
        }
        return note;
    }
}
