package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.SaveSellerLinkPort;
import github.lms.lemuel.company.domain.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerLinkServiceTest {

    private final LoadCompanyPort loadCompanyPort = mock(LoadCompanyPort.class);
    private final SaveSellerLinkPort saveSellerLinkPort = mock(SaveSellerLinkPort.class);
    private final SellerLinkService service = new SellerLinkService(loadCompanyPort, saveSellerLinkPort);

    @Test
    @DisplayName("실재하는 기업이면 링크를 UPSERT 한다")
    void linksWhenCompanyExists() {
        when(loadCompanyPort.findByStockCode("005930"))
                .thenReturn(Optional.of(new Company("005930", null, "삼성전자", null)));

        service.link(7L, "005930");

        verify(saveSellerLinkPort).link(7L, "005930");
    }

    @Test
    @DisplayName("없는 기업 종목코드는 거부하고 링크하지 않는다")
    void rejectsUnknownCompany() {
        when(loadCompanyPort.findByStockCode("999999")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.link(7L, "999999"));
        verify(saveSellerLinkPort, never()).link(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("sellerId 누락은 거부")
    void rejectsNullSeller() {
        assertThrows(IllegalArgumentException.class, () -> service.link(null, "005930"));
    }
}
