package github.lms.lemuel.account.banking.pension.domain;

import github.lms.lemuel.account.banking.pension.domain.exception.InvalidInvestmentInstructionException;
import github.lms.lemuel.account.banking.pension.domain.exception.InvalidPensionRateException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 운용지시(원리금보장 상품 1개) 값 객체 — 상품명 정규화와 이율 범위 검증. */
class PrincipalGuaranteedProductTest {

    @Test
    void 상품명은_앞뒤_공백을_제거하고_이율은_6자리로_정규화한다() {
        PrincipalGuaranteedProduct product =
                new PrincipalGuaranteedProduct("  원리금보장 정기예금  ", new BigDecimal("0.0285"));

        assertThat(product.productName()).isEqualTo("원리금보장 정기예금");
        assertThat(product.rate()).isEqualByComparingTo("0.0285");
        assertThat(product.rate().scale()).isEqualTo(PrincipalGuaranteedProduct.RATE_SCALE);
    }

    @Test
    void 상품명이_null이면_운용지시가_성립하지_않는다() {
        assertThatThrownBy(() -> new PrincipalGuaranteedProduct(null, new BigDecimal("0.02")))
                .isInstanceOfSatisfying(InvalidInvestmentInstructionException.class,
                        e -> assertThat(e.getProductName()).isNull());
    }

    @Test
    void 상품명이_공백뿐이면_운용지시가_성립하지_않는다() {
        assertThatThrownBy(() -> new PrincipalGuaranteedProduct("   ", new BigDecimal("0.02")))
                .isInstanceOf(InvalidInvestmentInstructionException.class);
    }

    @Test
    void 이율이_null이면_거절한다() {
        assertThatThrownBy(() -> PrincipalGuaranteedProduct.normalizeRate(null))
                .isInstanceOfSatisfying(InvalidPensionRateException.class,
                        e -> assertThat(e.getRate()).isNull());
    }

    @Test
    void 이율_0은_허용된다() {
        assertThat(PrincipalGuaranteedProduct.normalizeRate(BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void 이율이_1_이상이면_거절한다() {
        assertThatThrownBy(() -> PrincipalGuaranteedProduct.normalizeRate(new BigDecimal("1.000001")))
                .isInstanceOfSatisfying(InvalidPensionRateException.class,
                        e -> assertThat(e.getRate()).isEqualByComparingTo("1.000001"));
    }

    @Test
    void 이율이_음수면_거절한다() {
        assertThatThrownBy(() -> PrincipalGuaranteedProduct.normalizeRate(new BigDecimal("-0.000001")))
                .isInstanceOf(InvalidPensionRateException.class);
    }
}
