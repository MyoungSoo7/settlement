package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataExpenseReceiptRepository extends JpaRepository<ExpenseReceiptJpaEntity, Long> {

    Optional<ExpenseReceiptJpaEntity> findByReportIdAndFileHash(String reportId, String fileHash);

    Optional<ExpenseReceiptJpaEntity> findFirstByReportIdOrderByCreatedAtDescIdDesc(String reportId);

    /** 리뷰 큐 — 최신 우선 (settlement tax 스캔 큐 선례). */
    List<ExpenseReceiptJpaEntity> findByStatusOrderByIdDesc(ExpenseReceiptStatus status, Pageable pageable);
}
