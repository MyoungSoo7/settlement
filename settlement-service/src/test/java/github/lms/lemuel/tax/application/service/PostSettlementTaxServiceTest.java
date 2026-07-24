package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerEntryPort;
import github.lms.lemuel.ledger.application.service.LedgerPeriodGuard;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.tax.application.TaxPostingResult;
import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostSettlementTaxServiceTest {

    private LoadSettlementForTaxPort settlementPort;
    private LoadSellerTaxProfilePort profilePort;
    private LoadLedgerEntryPort loadLedgerPort;
    private SaveLedgerEntryPort saveLedgerPort;
    private LedgerPeriodGuard periodGuard;
    private PostSettlementTaxService service;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    @BeforeEach
    void setUp() {
        settlementPort = mock(LoadSettlementForTaxPort.class);
        profilePort = mock(LoadSellerTaxProfilePort.class);
        loadLedgerPort = mock(LoadLedgerEntryPort.class);
        saveLedgerPort = mock(SaveLedgerEntryPort.class);
        periodGuard = mock(LedgerPeriodGuard.class);
        service = new PostSettlementTaxService(
                new TaxContextResolver(settlementPort, profilePort), loadLedgerPort, saveLedgerPort, periodGuard);
        when(saveLedgerPort.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void settlement(String status) {
        settlement(status, 7L);
    }

    private void settlement(String status, Long sellerId) {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(new TaxSettlementView(
                100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, status,
                new BigDecimal("96500.00"), sellerId)));
    }

    private void profile(TaxType type) {
        SellerTaxProfile p = type == TaxType.BUSINESS
                ? SellerTaxProfile.register(7L, TaxType.BUSINESS, "1234567890")
                : SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null);
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.of(p));
    }

    @Test
    void 개인_셀러_확정정산_VAT_한_전표만_전기_원천징수는_settlement_원장에_없음() {
        // 2026-07-24 정정 — 원천징수는 실지급 통합으로 account-service GL 로 이관, settlement 자체원장은 VAT 만.
        settlement("DONE");
        profile(TaxType.INDIVIDUAL);
        when(loadLedgerPort.existsByReference(100L, ReferenceType.SETTLEMENT_TAX)).thenReturn(false);

        TaxPostingResult result = service.postForSettlement(100L, 7L);

        assertThat(result.outcome()).isEqualTo(TaxPostingResult.Outcome.POSTED);
        assertThat(result.entriesPosted()).isEqualTo(1);
        verify(saveLedgerPort, times(1)).save(any(LedgerEntry.class));
        verify(periodGuard).assertOpenForNewEntry(DATE);
    }

    @Test
    void 사업자_셀러는_부가세_한_전표() {
        settlement("DONE");
        profile(TaxType.BUSINESS);
        when(loadLedgerPort.existsByReference(100L, ReferenceType.SETTLEMENT_TAX)).thenReturn(false);

        TaxPostingResult result = service.postForSettlement(100L, 7L);

        assertThat(result.entriesPosted()).isEqualTo(1);
        verify(saveLedgerPort, times(1)).save(any(LedgerEntry.class));
    }

    @Test
    void 이미_전기됐으면_멱등_skip() {
        settlement("DONE");
        profile(TaxType.INDIVIDUAL);
        when(loadLedgerPort.existsByReference(100L, ReferenceType.SETTLEMENT_TAX)).thenReturn(true);

        TaxPostingResult result = service.postForSettlement(100L, 7L);

        assertThat(result.outcome()).isEqualTo(TaxPostingResult.Outcome.ALREADY_POSTED);
        verify(saveLedgerPort, never()).save(any());
    }

    @Test
    void 미등록_셀러는_보류() {
        settlement("DONE");
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.empty());

        TaxPostingResult result = service.postForSettlement(100L, 7L);

        assertThat(result.outcome()).isEqualTo(TaxPostingResult.Outcome.PENDING_NO_PROFILE);
        verify(saveLedgerPort, never()).save(any());
    }

    @Test
    void 미확정_정산은_보류() {
        settlement("PROCESSING");
        profile(TaxType.INDIVIDUAL);

        TaxPostingResult result = service.postForSettlement(100L, 7L);

        assertThat(result.outcome()).isEqualTo(TaxPostingResult.Outcome.SKIPPED_NOT_DONE);
    }

    @Test
    void 소유권_불일치_셀러ID면_예외_전파되고_전기되지_않는다() {
        settlement("DONE", 999L); // 실제 소유 셀러(999) != 요청 sellerId(7)
        profile(TaxType.INDIVIDUAL);

        assertThatThrownBy(() -> service.postForSettlement(100L, 7L))
                .isInstanceOf(AccessDeniedException.class);

        verify(saveLedgerPort, never()).save(any());
    }

    @Test
    void 정산_없으면_예외() {
        when(settlementPort.findById(100L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.postForSettlement(100L, 7L))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 인자_null_예외() {
        assertThatThrownBy(() -> service.postForSettlement(null, 7L))
                .isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> service.postForSettlement(100L, null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 마감기간이면_전기_보류_전_예외를_전파() {
        settlement("DONE");
        profile(TaxType.INDIVIDUAL);
        when(loadLedgerPort.existsByReference(eq(100L), any())).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("closed"))
                .when(periodGuard).assertOpenForNewEntry(DATE);

        assertThatThrownBy(() -> service.postForSettlement(100L, 7L))
                .isInstanceOf(IllegalStateException.class);
        verify(saveLedgerPort, never()).save(any());
    }
}
