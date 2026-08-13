package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.ExpenseReceipt;

/**
 * 영수증 관리자 리뷰 유스케이스 — NEEDS_REVIEW(신뢰도 미달·거래일 판독 불가) 를 육안 대조로 종결한다.
 */
public interface ReviewExpenseReceiptUseCase {

    ExpenseReceipt review(ReviewReceiptCommand command);

    /**
     * @param receiptId  영수증 식별자
     * @param reviewerId 리뷰어 userId
     * @param matched    true=대사 확정(MATCHED) / false=반려(MISMATCHED)
     * @param note       육안 대조 근거
     */
    record ReviewReceiptCommand(Long receiptId, Long reviewerId, boolean matched, String note) {
    }
}
