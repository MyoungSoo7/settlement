package github.lms.lemuel.card.domain;

/**
 * 영수증 대사 상태 (ADR 0036).
 *
 * <pre>
 * EXTRACTED ──자동 대사──▶ MATCHED | MISMATCHED | NEEDS_REVIEW
 * NEEDS_REVIEW ──관리자 리뷰──▶ MATCHED | MISMATCHED
 * </pre>
 *
 * <p>MATCHED/MISMATCHED 는 종결 — 번복은 새 영수증 첨부로만 한다. 승인 게이트는 보고서의 최신
 * 영수증이 MATCHED 일 때만 통과시킨다.
 */
public enum ExpenseReceiptStatus {

    /** OCR 추출 완료, 대사 전 (과도 상태 — 자동 대사가 같은 트랜잭션에서 즉시 판정한다) */
    EXTRACTED,
    /** 매입과 대사 일치 — 승인 게이트 통과 근거 */
    MATCHED,
    /** 매입과 불일치 — 이 영수증으로는 승인 불가 */
    MISMATCHED,
    /** 신뢰도 미달·거래일 판독 불가 — 관리자 육안 리뷰 대기 */
    NEEDS_REVIEW;

    public boolean canTransitionTo(ExpenseReceiptStatus next) {
        return switch (this) {
            case EXTRACTED -> next == MATCHED || next == MISMATCHED || next == NEEDS_REVIEW;
            case NEEDS_REVIEW -> next == MATCHED || next == MISMATCHED;
            case MATCHED, MISMATCHED -> false;
        };
    }

    public boolean isTerminal() {
        return this == MATCHED || this == MISMATCHED;
    }
}
