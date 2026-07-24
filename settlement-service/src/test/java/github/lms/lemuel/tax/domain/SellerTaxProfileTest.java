package github.lms.lemuel.tax.domain;

import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerTaxProfileTest {

    @Test
    void 개인_등록_사업자번호_미보유() {
        SellerTaxProfile profile = SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null);
        assertThat(profile.getSellerId()).isEqualTo(7L);
        assertThat(profile.getTaxType()).isEqualTo(TaxType.INDIVIDUAL);
        assertThat(profile.getBusinessRegNo()).isNull();
        assertThat(profile.maskedBusinessRegNo()).isEmpty();
        assertThat(profile.getUpdatedAt()).isNotNull();
    }

    @Test
    void 사업자_등록_10자리_정규화_하이픈제거() {
        SellerTaxProfile profile = SellerTaxProfile.register(7L, TaxType.BUSINESS, "123-45-67890");
        assertThat(profile.getBusinessRegNo()).isEqualTo("1234567890");
        assertThat(profile.maskedBusinessRegNo()).isEqualTo("123*******");
    }

    @Test
    void 사업자인데_사업자번호_없으면_예외() {
        assertThatThrownBy(() -> SellerTaxProfile.register(7L, TaxType.BUSINESS, null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 사업자번호_형식_틀리면_예외() {
        assertThatThrownBy(() -> SellerTaxProfile.register(7L, TaxType.BUSINESS, "12345"))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void taxType_null_예외() {
        assertThatThrownBy(() -> SellerTaxProfile.register(7L, null, null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void sellerId_비양수_예외() {
        assertThatThrownBy(() -> SellerTaxProfile.register(0L, TaxType.INDIVIDUAL, null))
                .isInstanceOf(TaxInvariantViolationException.class);
        assertThatThrownBy(() -> SellerTaxProfile.register(null, TaxType.INDIVIDUAL, null))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 정정_개인에서_사업자로() {
        SellerTaxProfile profile = SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null);
        LocalDateTime before = profile.getUpdatedAt();
        profile.changeProfile(TaxType.BUSINESS, "1234567890");
        assertThat(profile.getTaxType()).isEqualTo(TaxType.BUSINESS);
        assertThat(profile.getBusinessRegNo()).isEqualTo("1234567890");
        assertThat(profile.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void 정정_사업자에서_개인으로_사업자번호_제거() {
        SellerTaxProfile profile = SellerTaxProfile.register(7L, TaxType.BUSINESS, "1234567890");
        profile.changeProfile(TaxType.INDIVIDUAL, null);
        assertThat(profile.getTaxType()).isEqualTo(TaxType.INDIVIDUAL);
        assertThat(profile.getBusinessRegNo()).isNull();
    }

    @Test
    void rehydrate는_저장값_그대로() {
        LocalDateTime ts = LocalDateTime.of(2026, 7, 23, 10, 0);
        SellerTaxProfile profile = SellerTaxProfile.rehydrate(9L, TaxType.BUSINESS, "1234567890", ts);
        assertThat(profile.getSellerId()).isEqualTo(9L);
        assertThat(profile.getUpdatedAt()).isEqualTo(ts);
    }
}
