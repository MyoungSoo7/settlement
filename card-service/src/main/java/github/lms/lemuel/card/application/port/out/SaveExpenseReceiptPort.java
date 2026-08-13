package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ExpenseReceipt;

/**
 * 영수증 저장 포트.
 */
public interface SaveExpenseReceiptPort {

    /** 신규 영수증 + 파일 본문 저장. (reportId, fileHash) UNIQUE 가 멱등 최후 방어선이다. */
    ExpenseReceipt saveNew(ExpenseReceipt receipt, byte[] content);

    /** 상태 변경(리뷰 종결) 반영 — 파일 본문은 불변이라 다시 받지 않는다. */
    ExpenseReceipt update(ExpenseReceipt receipt);
}
