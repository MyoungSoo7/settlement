package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaxContextResolverTest {

    private LoadSettlementForTaxPort settlementPort;
    private LoadSellerTaxProfilePort profilePort;
    private TaxContextResolver resolver;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    @BeforeEach
    void setUp() {
        settlementPort = mock(LoadSettlementForTaxPort.class);
        profilePort = mock(LoadSellerTaxProfilePort.class);
        resolver = new TaxContextResolver(settlementPort, profilePort);
    }

    private TaxSettlementView view(String status) {
        return new TaxSettlementView(100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, status,
                new BigDecimal("96500.00"));
    }

    @Test
    void 정산없음() {
        when(settlementPort.findById(100L)).thenReturn(Optional.empty());
        TaxContextResolver.Resolved r = resolver.resolve(100L, 7L);
        assertThat(r.status()).isEqualTo(TaxContextResolver.Status.SETTLEMENT_NOT_FOUND);
        assertThat(r.isOk()).isFalse();
    }

    @Test
    void 프로필_미등록() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(view("DONE")));
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.empty());
        TaxContextResolver.Resolved r = resolver.resolve(100L, 7L);
        assertThat(r.status()).isEqualTo(TaxContextResolver.Status.NO_PROFILE);
    }

    @Test
    void 정산_미확정() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(view("PROCESSING")));
        when(profilePort.findBySellerId(7L)).thenReturn(
                Optional.of(SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));
        TaxContextResolver.Resolved r = resolver.resolve(100L, 7L);
        assertThat(r.status()).isEqualTo(TaxContextResolver.Status.NOT_DONE);
    }

    @Test
    void OK이면_계산_조립() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(view("DONE")));
        when(profilePort.findBySellerId(7L)).thenReturn(
                Optional.of(SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));
        TaxContextResolver.Resolved r = resolver.resolve(100L, 7L);
        assertThat(r.isOk()).isTrue();
        assertThat(r.calculation().vatAmount()).isEqualByComparingTo("318"); // floor(3500*10/110)
        assertThat(r.calculation().withholdingAmount()).isEqualByComparingTo("3184");
    }
}
