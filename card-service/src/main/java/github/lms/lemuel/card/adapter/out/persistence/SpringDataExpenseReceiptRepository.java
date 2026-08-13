package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataExpenseReceiptRepository extends JpaRepository<ExpenseReceiptJpaEntity, Long> {

    Optional<ExpenseReceiptJpaEntity> findByReportIdAndFileHash(String reportId, String fileHash);

    Optional<ExpenseReceiptJpaEntity> findFirstByReportIdOrderByCreatedAtDescIdDesc(String reportId);
}
