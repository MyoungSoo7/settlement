package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.port.in.IssueTaxInvoiceUseCase;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.application.port.out.SaveTaxInvoicePort;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 세금계산서 발행 서비스 (Phase 4) — 정산 1건에 플랫폼→셀러 수수료 세금계산서를 발행한다(멱등).
 *
 * <p>발행번호가 정산에서 결정적으로 파생하고 영속 UNIQUE(settlement_id·issue_number)가 걸려 있어, 재호출은
 * 기존 계산서를 그대로 반환한다. 동시 발행 경합(UNIQUE 위반)은 재조회로 수렴한다. 미등록/미확정은 빈 결과.
 */
@Service
@Transactional
public class IssueTaxInvoiceService implements IssueTaxInvoiceUseCase {

    private static final Logger log = LoggerFactory.getLogger(IssueTaxInvoiceService.class);

    private final TaxContextResolver resolver;
    private final LoadTaxInvoicePort loadInvoicePort;
    private final SaveTaxInvoicePort saveInvoicePort;

    public IssueTaxInvoiceService(TaxContextResolver resolver,
                                  LoadTaxInvoicePort loadInvoicePort,
                                  SaveTaxInvoicePort saveInvoicePort) {
        this.resolver = resolver;
        this.loadInvoicePort = loadInvoicePort;
        this.saveInvoicePort = saveInvoicePort;
    }

    @Override
    public Optional<TaxInvoice> issueForSettlement(Long settlementId, Long sellerId) {
        if (settlementId == null || sellerId == null) {
            throw new TaxInvariantViolationException("settlementId·sellerId 는 필수입니다");
        }

        Optional<TaxInvoice> existing = loadInvoicePort.findBySettlementId(settlementId);
        if (existing.isPresent()) {
            return existing;
        }

        TaxContextResolver.Resolved resolved = resolver.resolve(settlementId, sellerId);
        if (!resolved.isOk()) {
            log.info("[TaxInvoice] 발행 보류 — status={}, settlementId={}", resolved.status(), settlementId);
            return Optional.empty();
        }

        TaxInvoice invoice = TaxInvoice.issue(
                settlementId, sellerId, resolved.calculation(), resolved.view().settlementDate());
        try {
            TaxInvoice saved = saveInvoicePort.save(invoice);
            log.info("[TaxInvoice] 발행 완료: settlementId={}, number={}, supply={}, tax={}",
                    settlementId, saved.getIssueNumber(), saved.getSupplyAmount(), saved.getTaxAmount());
            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // 동시 발행 경합 — 승자의 계산서가 이미 존재. 재조회로 수렴.
            log.warn("[TaxInvoice] concurrent issue race — return persisted. settlementId={}", settlementId);
            return Optional.of(loadInvoicePort.findBySettlementId(settlementId).orElseThrow(() -> e));
        }
    }
}
