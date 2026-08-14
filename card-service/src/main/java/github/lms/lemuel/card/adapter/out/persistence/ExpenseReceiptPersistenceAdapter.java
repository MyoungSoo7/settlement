package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadExpenseReceiptPort;
import github.lms.lemuel.card.application.port.out.SaveExpenseReceiptPort;
import github.lms.lemuel.card.domain.ExpenseReceipt;
import github.lms.lemuel.card.domain.ExpenseReceiptStatus;
import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 영수증 영속 어댑터 — expense_receipts (V10).
 */
@Component
public class ExpenseReceiptPersistenceAdapter implements SaveExpenseReceiptPort, LoadExpenseReceiptPort {

    private final SpringDataExpenseReceiptRepository repository;

    public ExpenseReceiptPersistenceAdapter(SpringDataExpenseReceiptRepository repository) {
        this.repository = repository;
    }

    @Override
    public ExpenseReceipt saveNew(ExpenseReceipt receipt, byte[] content) {
        return repository.save(ExpenseReceiptJpaEntity.fromDomain(receipt, content)).toDomain();
    }

    @Override
    public ExpenseReceipt update(ExpenseReceipt receipt) {
        ExpenseReceiptJpaEntity entity = repository.findById(receipt.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARD_RECEIPT_NOT_FOUND,
                        "영수증을 찾을 수 없습니다: " + receipt.getId()));
        entity.applyStateFrom(receipt);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<ExpenseReceipt> findById(Long id) {
        return repository.findById(id).map(ExpenseReceiptJpaEntity::toDomain);
    }

    @Override
    public List<ExpenseReceipt> findByStatus(ExpenseReceiptStatus status, int limit) {
        return repository.findByStatusOrderByIdDesc(status, PageRequest.of(0, limit)).stream()
                .map(ExpenseReceiptJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<ExpenseReceipt> findByReportIdAndFileHash(String reportId, String fileHash) {
        return repository.findByReportIdAndFileHash(reportId, fileHash)
                .map(ExpenseReceiptJpaEntity::toDomain);
    }

    @Override
    public Optional<ExpenseReceipt> findLatestByReportId(String reportId) {
        return repository.findFirstByReportIdOrderByCreatedAtDescIdDesc(reportId)
                .map(ExpenseReceiptJpaEntity::toDomain);
    }
}
