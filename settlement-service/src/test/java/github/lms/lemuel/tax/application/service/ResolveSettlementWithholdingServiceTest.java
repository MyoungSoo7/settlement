package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.WithholdingResolution;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResolveSettlementWithholdingServiceTest {

    private LoadSellerTaxProfilePort profilePort;
    private ResolveSettlementWithholdingService service;

    @BeforeEach
    void setUp() {
        profilePort = mock(LoadSellerTaxProfilePort.class);
        service = new ResolveSettlementWithholdingService(profilePort);
    }

    @Test
    void 개인_셀러는_원천징수_산출() {
        when(profilePort.findBySellerId(7L)).thenReturn(
                Optional.of(SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));

        WithholdingResolution result = service.resolveForPayout(7L, new BigDecimal("96500.00"));

        assertThat(result.profileRegistered()).isTrue();
        assertThat(result.taxType()).isEqualTo(TaxType.INDIVIDUAL);
        assertThat(result.withholdingAmount()).isEqualByComparingTo("3184");
        assertThat(result.hasWithholding()).isTrue();
    }

    @Test
    void 사업자_셀러는_원천징수_0() {
        when(profilePort.findBySellerId(7L)).thenReturn(
                Optional.of(SellerTaxProfile.register(7L, TaxType.BUSINESS, "1234567890")));

        WithholdingResolution result = service.resolveForPayout(7L, new BigDecimal("96500.00"));

        assertThat(result.profileRegistered()).isTrue();
        assertThat(result.withholdingAmount()).isEqualByComparingTo("0");
        assertThat(result.hasWithholding()).isFalse();
    }

    @Test
    void 미등록_셀러는_사업자_취급_원천징수_0() {
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.empty());

        WithholdingResolution result = service.resolveForPayout(7L, new BigDecimal("96500.00"));

        assertThat(result.profileRegistered()).isFalse();
        assertThat(result.taxType()).isNull();
        assertThat(result.withholdingAmount()).isEqualByComparingTo("0");
    }

    @Test
    void sellerId_null_예외() {
        assertThatThrownBy(() -> service.resolveForPayout(null, new BigDecimal("100")))
                .isInstanceOf(TaxInvariantViolationException.class);
    }
}
