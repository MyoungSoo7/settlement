package github.lms.lemuel.card.application.port.in;

import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;

import java.util.List;
import java.util.Optional;

/**
 * 영수증 조회 유스케이스.
 */
public interface GetExpenseReceiptUseCase {

    Optional<ExpenseReceipt> byId(Long receiptId);

    /** 보고서의 최신 영수증 — 승인 게이트가 보는 것과 같은 기준. */
    Optional<ExpenseReceipt> latestForReport(String reportId);

    /** 상태별 목록(최신 우선) — 리뷰 큐 화면용. */
    List<ExpenseReceipt> byStatus(ExpenseReceiptStatus status, int limit);
}
