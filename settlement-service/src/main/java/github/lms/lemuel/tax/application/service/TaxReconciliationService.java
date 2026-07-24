package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.tax.application.port.in.GetTaxReconciliationUseCase;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.TaxReconciliation;
import github.lms.lemuel.tax.domain.exception.SellerTaxProfileNotRegisteredException;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 세무 3자(+실효) 대사 서비스 (Seed B2 핵심) — read-only. 세무 계산·세금계산서·세무 원장 전표·실제 IMMEDIATE
 * payout 금액을 로드해 {@link TaxReconciliation} 으로 정합을 검증한다. 쓰기 로직은 섞지 않는다
 * (ledger-invariants: 조회는 read-only).
 *
 * <p>실제 payout 조회는 {@link LoadPayoutPort}(payout 기능의 기존 출력 포트)를 그대로 재사용한다 —
 * settlement-service 내부의 다른 feature 포트 재사용은 이미 확립된 패턴(SettlementConfirmItemWriter 등)과
 * 동형이며, MSA 경계(cross-service DB 접근 금지)와는 무관하다(같은 서비스 내부 어댑터 재사용).
 */
@Service
@Transactional(readOnly = true)
public class TaxReconciliationService implements GetTaxReconciliationUseCase {

    private final TaxContextResolver resolver;
    private final LoadTaxInvoicePort loadInvoicePort;
    private final LoadLedgerEntryPort loadLedgerPort;
    private final LoadPayoutPort loadPayoutPort;

    public TaxReconciliationService(TaxContextResolver resolver,
                                    LoadTaxInvoicePort loadInvoicePort,
                                    LoadLedgerEntryPort loadLedgerPort,
                                    LoadPayoutPort loadPayoutPort) {
        this.resolver = resolver;
        this.loadInvoicePort = loadInvoicePort;
        this.loadLedgerPort = loadLedgerPort;
        this.loadPayoutPort = loadPayoutPort;
    }

    @Override
    public TaxReconciliation reconcile(Long settlementId, Long sellerId) {
        TaxContextResolver.Resolved resolved = resolver.resolve(settlementId, sellerId);
        switch (resolved.status()) {
            case SETTLEMENT_NOT_FOUND ->
                throw new TaxInvariantViolationException("정산을 찾을 수 없습니다: settlementId=" + settlementId);
            case NO_PROFILE -> throw new SellerTaxProfileNotRegisteredException(sellerId);
            case NOT_DONE ->
                throw new TaxInvariantViolationException("정산이 확정(DONE) 전이라 세무 대사를 수행할 수 없습니다");
            default -> { /* OK — 진행 */ }
        }

        TaxInvoice invoice = loadInvoicePort.findBySettlementId(settlementId)
                .orElseThrow(() -> new TaxInvariantViolationException(
                        "세금계산서가 아직 발행되지 않았습니다: settlementId=" + settlementId));

        List<LedgerEntry> taxEntries = loadLedgerPort.findByReference(settlementId, ReferenceType.SETTLEMENT_TAX);

        // ★ 실효 대사 — 자기참조(TaxCalculation 파생값끼리 비교) 탈피: 실제 IMMEDIATE Payout 금액과 대조한다.
        BigDecimal immediatePayoutAmount = resolved.view().immediatePayoutAmount();
        BigDecimal actualImmediatePayoutAmount = loadPayoutPort
                .findBySettlementIdAndType(settlementId, PayoutType.IMMEDIATE)
                .map(Payout::getAmount)
                .orElse(null);

        return TaxReconciliation.reconcile(resolved.calculation(), invoice, taxEntries,
                immediatePayoutAmount, actualImmediatePayoutAmount);
    }
}
