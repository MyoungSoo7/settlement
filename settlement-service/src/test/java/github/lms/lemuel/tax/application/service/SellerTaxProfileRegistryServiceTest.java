package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.SaveSellerTaxProfilePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerTaxProfileRegistryServiceTest {

    private LoadSellerTaxProfilePort loadPort;
    private SaveSellerTaxProfilePort savePort;
    private SellerTaxProfileRegistryService service;

    @BeforeEach
    void setUp() {
        loadPort = mock(LoadSellerTaxProfilePort.class);
        savePort = mock(SaveSellerTaxProfilePort.class);
        service = new SellerTaxProfileRegistryService(loadPort, savePort);
        when(savePort.save(any(SellerTaxProfile.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void 신규_등록() {
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.empty());

        SellerTaxProfile saved = service.register(7L, TaxType.INDIVIDUAL, null);

        assertThat(saved.getTaxType()).isEqualTo(TaxType.INDIVIDUAL);
        verify(savePort).save(any(SellerTaxProfile.class));
    }

    @Test
    void 기존_있으면_정정() {
        SellerTaxProfile existing = SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null);
        when(loadPort.findBySellerId(7L)).thenReturn(Optional.of(existing));

        SellerTaxProfile saved = service.register(7L, TaxType.BUSINESS, "1234567890");

        assertThat(saved.getTaxType()).isEqualTo(TaxType.BUSINESS);
        assertThat(saved.getBusinessRegNo()).isEqualTo("1234567890");
    }

    @Test
    void 최초등록_경합은_재조회_정정으로_수렴() {
        SellerTaxProfile winner = SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null);
        when(loadPort.findBySellerId(7L))
                .thenReturn(Optional.empty())          // 최초 관찰: 미등록
                .thenReturn(Optional.of(winner));      // 경합 후 재조회: 승자 존재
        when(savePort.save(any(SellerTaxProfile.class)))
                .thenThrow(new DataIntegrityViolationException("dup"))  // 신규 INSERT 경합
                .thenAnswer(inv -> inv.getArgument(0));                 // 정정 저장 성공

        SellerTaxProfile saved = service.register(7L, TaxType.BUSINESS, "1234567890");

        assertThat(saved.getTaxType()).isEqualTo(TaxType.BUSINESS);
        verify(savePort, times(2)).save(any(SellerTaxProfile.class));
    }
}
