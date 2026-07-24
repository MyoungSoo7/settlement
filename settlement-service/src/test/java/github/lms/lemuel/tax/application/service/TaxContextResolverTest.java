package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        return view(status, 7L);
    }

    private TaxSettlementView view(String status, Long sellerId) {
        return new TaxSettlementView(100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, status,
                new BigDecimal("96500.00"), sellerId);
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

    @Test
    void 소유권_불일치면_AccessDeniedException을_던지고_프로필조회는_하지_않는다() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(view("DONE", 999L)));

        assertThatThrownBy(() -> resolver.resolve(100L, 7L))
                .isInstanceOf(AccessDeniedException.class);

        verify(profilePort, never()).findBySellerId(any());
    }

    @Test
    void 소유권_일치면_정상_해석() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(view("DONE", 7L)));
        when(profilePort.findBySellerId(7L)).thenReturn(
                Optional.of(SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));

        TaxContextResolver.Resolved r = resolver.resolve(100L, 7L);

        assertThat(r.isOk()).isTrue();
    }

    @Test
    void 실제_소유_셀러_미해석이면_경고만_남기고_요청sellerId를_신뢰해_진행() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(view("DONE", null)));
        when(profilePort.findBySellerId(7L)).thenReturn(
                Optional.of(SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));

        TaxContextResolver.Resolved r = resolver.resolve(100L, 7L);

        assertThat(r.isOk()).isTrue();
    }
}
