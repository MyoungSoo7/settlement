package github.lms.lemuel.tax.adapter.out.persistence;

import github.lms.lemuel.tax.application.port.out.LoadTaxInvoiceScanPort;
import github.lms.lemuel.tax.application.port.out.SaveTaxInvoiceScanPort;
import github.lms.lemuel.tax.domain.scan.ExtractedTaxInvoice;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 스캔 영속 어댑터 — 도메인 ↔ 엔티티 변환. 사업자등록번호는 도메인 VO 의 정규화 숫자열로 저장하고
 * (컨버터가 암호화), 복원 시 다시 VO 로 만든다.
 */
@Component
public class TaxInvoiceScanPersistenceAdapter implements SaveTaxInvoiceScanPort, LoadTaxInvoiceScanPort {

    private final SpringDataTaxInvoiceScanRepository repository;

    public TaxInvoiceScanPersistenceAdapter(SpringDataTaxInvoiceScanRepository repository) {
        this.repository = repository;
    }

    @Override
    public TaxInvoiceScan save(TaxInvoiceScan scan) {
        ExtractedTaxInvoice e = scan.getExtracted();
        TaxInvoiceScanJpaEntity entity = new TaxInvoiceScanJpaEntity(
                scan.getId(), scan.getSellerId(), scan.getFileName(), scan.getContentType(),
                scan.getFileHash(), scan.getSizeBytes(), scan.getStatus(),
                e.supplier().digits(), e.buyer().digits(), e.writtenDate(),
                e.supplyAmount(), e.taxAmount(), e.totalAmount(), e.approvalNumber(), e.amountConfidence(), e.approvalNumberConfidence(),
                scan.getOcrModel(), scan.getLinkedTaxInvoiceId(), scan.getReviewNote(),
                scan.getCreatedAt(), scan.getUpdatedAt());
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<TaxInvoiceScan> findById(Long id) {
        return id == null ? Optional.empty() : repository.findById(id).map(TaxInvoiceScanPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<TaxInvoiceScan> findBySellerIdAndFileHash(Long sellerId, String fileHash) {
        if (sellerId == null || fileHash == null) {
            return Optional.empty();
        }
        return repository.findBySellerIdAndFileHash(sellerId, fileHash)
                .map(TaxInvoiceScanPersistenceAdapter::toDomain);
    }

    @Override
    public List<TaxInvoiceScan> findByStatus(TaxInvoiceScanStatus status, int limit) {
        return repository.findByStatusOrderByIdDesc(status, PageRequest.of(0, limit)).stream()
                .map(TaxInvoiceScanPersistenceAdapter::toDomain)
                .toList();
    }

    private static TaxInvoiceScan toDomain(TaxInvoiceScanJpaEntity e) {
        ExtractedTaxInvoice extracted = ExtractedTaxInvoice.of(
                e.getSupplierBusinessNo(), e.getBuyerBusinessNo(), e.getWrittenDate(),
                e.getSupplyAmount(), e.getTaxAmount(), e.getTotalAmount(),
                e.getApprovalNumber(), e.getAmountConfidence(), e.getApprovalNumberConfidence());
        return TaxInvoiceScan.rehydrate(e.getId(), e.getSellerId(), e.getFileName(), e.getContentType(),
                e.getFileHash(), e.getSizeBytes(), extracted, e.getOcrModel(), e.getStatus(),
                e.getLinkedTaxInvoiceId(), e.getReviewNote(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
