package github.lms.lemuel.loan.domain;

/**
 * 담보서류 대사 상태 (ADR 0036 확산 — card 영수증·insurance 청약서류 상태머신과 동형).
 *
 * <pre>
 * EXTRACTED ──자동 대사──▶ MATCHED | MISMATCHED | NEEDS_REVIEW
 * NEEDS_REVIEW ──운영자 리뷰──▶ MATCHED | MISMATCHED
 * </pre>
 *
 * <p>MATCHED/MISMATCHED 는 종결 — 번복은 새 서류 첨부로만 한다. 승인 게이트는 대출의 최신 서류가
 * MATCHED 일 때만 통과시킨다.
 */
public enum CollateralDocumentStatus {

    /** OCR 추출 완료, 대사 전 (과도 상태 — 자동 대사가 같은 트랜잭션에서 즉시 판정한다) */
    EXTRACTED,
    /** 담보 설정값과 대사 일치 — 승인 게이트 통과 근거 */
    MATCHED,
    /** 담보 설정값과 불일치 — 이 서류로는 승인 불가 */
    MISMATCHED,
    /** 신뢰도 미달·선순위/평가기준일 판독 불가 — 운영자 육안 리뷰 대기 */
    NEEDS_REVIEW;

    public boolean canTransitionTo(CollateralDocumentStatus next) {
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
