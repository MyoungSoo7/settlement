package github.lms.lemuel.company.application.service;

import github.lms.lemuel.company.application.port.in.LinkSellerUseCase;
import github.lms.lemuel.company.application.port.out.LoadCompanyPort;
import github.lms.lemuel.company.application.port.out.SaveSellerLinkPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * 셀러↔기업 명시적 링크. 링크 대상 기업(종목코드)이 실재하는지 검증 후 멱등 UPSERT.
 * (셀러 존재 검증은 하지 않는다 — user.registered 수신 지연/미수신에도 링크를 허용, fail-open.)
 */
@Service
public class SellerLinkService implements LinkSellerUseCase {

    private final LoadCompanyPort loadCompanyPort;
    private final SaveSellerLinkPort saveSellerLinkPort;

    public SellerLinkService(LoadCompanyPort loadCompanyPort, SaveSellerLinkPort saveSellerLinkPort) {
        this.loadCompanyPort = loadCompanyPort;
        this.saveSellerLinkPort = saveSellerLinkPort;
    }

    @Override
    @Transactional
    public void link(Long sellerId, String stockCode) {
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId 는 필수입니다");
        }
        loadCompanyPort.findByStockCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("기업을 찾을 수 없습니다: " + stockCode));
        saveSellerLinkPort.link(sellerId, stockCode);
    }
}
