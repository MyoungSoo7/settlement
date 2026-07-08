package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.LoadSellerLinkPort;
import github.lms.lemuel.company.application.port.out.SaveSellerLinkPort;
import github.lms.lemuel.company.application.port.out.SaveSellerPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class SellerPersistenceAdapter implements SaveSellerPort, SaveSellerLinkPort, LoadSellerLinkPort {

    private final CompanySellerRepository sellerRepository;
    private final CompanySellerLinkRepository linkRepository;

    public SellerPersistenceAdapter(CompanySellerRepository sellerRepository,
                                    CompanySellerLinkRepository linkRepository) {
        this.sellerRepository = sellerRepository;
        this.linkRepository = linkRepository;
    }

    @Override
    @Transactional
    public void record(Long sellerId, String email) {
        sellerRepository.save(new CompanySellerJpaEntity(sellerId, email, Instant.now()));
    }

    @Override
    @Transactional
    public void link(Long sellerId, String stockCode) {
        linkRepository.save(new CompanySellerLinkJpaEntity(sellerId, stockCode, Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> sellersOf(String stockCode) {
        return linkRepository.findByStockCode(stockCode).stream()
                .map(CompanySellerLinkJpaEntity::getSellerId)
                .toList();
    }
}
