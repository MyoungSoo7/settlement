package github.lms.lemuel.card.domain;

/**
 * 대사 판정 결과 — {@link ExpenseReceiptMatcher} 가 만들고 {@link ExpenseReceipt#applyDecision} 이 적용한다.
 *
 * @param status 도달할 상태 (MATCHED / MISMATCHED / NEEDS_REVIEW)
 * @param note   판정 근거 (MATCHED 는 null — 일치에 사유가 필요 없다)
 */
public record ReceiptMatchDecision(ExpenseReceiptStatus status, String note) {

    public static ReceiptMatchDecision matched() {
        return new ReceiptMatchDecision(ExpenseReceiptStatus.MATCHED, null);
    }

    public static ReceiptMatchDecision mismatched(String note) {
        return new ReceiptMatchDecision(ExpenseReceiptStatus.MISMATCHED, requireNote(note));
    }

    public static ReceiptMatchDecision needsReview(String note) {
        return new ReceiptMatchDecision(ExpenseReceiptStatus.NEEDS_REVIEW, requireNote(note));
    }

    private static String requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("불일치·리뷰 판정에는 근거가 필수입니다");
        }
        return note;
    }
}
