package github.lms.lemuel.tax.domain;

import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.LedgerStatus;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-07-24 정정 — TaxJournal 은 이제 VAT 전표만 전기한다(원천징수는 account-service GL 로 이관).
 */
class TaxJournalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 부가세가_있으면_VAT_전표_1건_Dr_COMMISSION_REVENUE_Cr_VAT_PAYABLE() {
        TaxCalculation calc = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.INDIVIDUAL);

        List<LedgerEntry> entries = TaxJournal.postingsFor(100L, calc, DATE);

        assertThat(entries).hasSize(1);
        LedgerEntry vat = entries.get(0);
        assertThat(vat.getDebitAccount()).isEqualTo(AccountType.COMMISSION_REVENUE);
        assertThat(vat.getCreditAccount()).isEqualTo(AccountType.VAT_PAYABLE);
        assertThat(vat.getAmount()).isEqualByComparingTo("318");
        assertThat(vat.getEntryType()).isEqualTo(LedgerEntryType.VAT_ACCRUED);
        assertThat(vat.getReferenceType()).isEqualTo(ReferenceType.SETTLEMENT_TAX);
        assertThat(vat.getReferenceId()).isEqualTo(100L);
        assertThat(vat.getStatus()).isEqualTo(LedgerStatus.POSTED);
    }

    @Test
    void 세무유형과_무관하게_VAT_전표만_생성된다() {
        // 원천징수는 이 원장에 전기되지 않으므로, 개인/사업자 모두 결과가 같다(VAT 는 taxType 무관 계산).
        TaxCalculation individual = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.INDIVIDUAL);
        TaxCalculation business = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.BUSINESS);

        List<LedgerEntry> fromIndividual = TaxJournal.postingsFor(100L, individual, DATE);
        List<LedgerEntry> fromBusiness = TaxJournal.postingsFor(100L, business, DATE);

        assertThat(fromIndividual).hasSize(1);
        assertThat(fromBusiness).hasSize(1);
        assertThat(fromIndividual.get(0).getAmount()).isEqualByComparingTo(fromBusiness.get(0).getAmount());
    }

    @Test
    void 부가세_0이면_전표_없음() {
        TaxCalculation calc = TaxCalculation.of(bd("0.00"), bd("0.00"), TaxType.INDIVIDUAL);
        assertThat(TaxJournal.postingsFor(100L, calc, DATE)).isEmpty();
    }

    @Test
    void settlementId_비양수_예외() {
        TaxCalculation calc = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.BUSINESS);
        assertThatThrownBy(() -> TaxJournal.postingsFor(0L, calc, DATE))
                .isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> TaxJournal.postingsFor(null, calc, DATE))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void calc_null_예외() {
        assertThatThrownBy(() -> TaxJournal.postingsFor(100L, null, DATE))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void settlementDate_null_예외() {
        TaxCalculation calc = TaxCalculation.of(bd("3500.00"), bd("96500.00"), TaxType.BUSINESS);
        assertThatThrownBy(() -> TaxJournal.postingsFor(100L, calc, null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }
}
