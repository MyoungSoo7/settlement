package github.lms.lemuel.tax.application;

import github.lms.lemuel.tax.domain.TaxCalculation;
import github.lms.lemuel.tax.domain.TaxType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TaxPostingResultTest {

    @Test
    void 팩토리별_아웃컴() {
        TaxCalculation calc = TaxCalculation.of(new BigDecimal("3500.00"), new BigDecimal("96500.00"), TaxType.INDIVIDUAL);
        assertThat(TaxPostingResult.posted(2, calc).outcome()).isEqualTo(TaxPostingResult.Outcome.POSTED);
        assertThat(TaxPostingResult.posted(2, calc).entriesPosted()).isEqualTo(2);
        assertThat(TaxPostingResult.posted(2, calc).calculation()).isEqualTo(calc);
        assertThat(TaxPostingResult.alreadyPosted().outcome()).isEqualTo(TaxPostingResult.Outcome.ALREADY_POSTED);
        assertThat(TaxPostingResult.pendingNoProfile().outcome()).isEqualTo(TaxPostingResult.Outcome.PENDING_NO_PROFILE);
        assertThat(TaxPostingResult.skippedNotDone().outcome()).isEqualTo(TaxPostingResult.Outcome.SKIPPED_NOT_DONE);
        assertThat(TaxPostingResult.alreadyPosted().calculation()).isNull();
    }
}
