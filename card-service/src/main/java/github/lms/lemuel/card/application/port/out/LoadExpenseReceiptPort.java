package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;

import java.util.List;
import java.util.Optional;

/**
 * 영수증 조회 포트.
 */
public interface LoadExpenseReceiptPort {

    Optional<ExpenseReceipt> findById(Long id);

    /** 상태별 목록(최신 우선) — 리뷰 큐 화면용. */
    List<ExpenseReceipt> findByStatus(ExpenseReceiptStatus status, int limit);

    /** 멱등 선조회 — 같은 파일 재업로드를 OCR 호출 전에 잡는다. */
    Optional<ExpenseReceipt> findByReportIdAndFileHash(String reportId, String fileHash);

    /** 보고서의 최신 영수증(업로드 시각 기준) — 승인 게이트의 판정 대상. */
    Optional<ExpenseReceipt> findLatestByReportId(String reportId);
}
