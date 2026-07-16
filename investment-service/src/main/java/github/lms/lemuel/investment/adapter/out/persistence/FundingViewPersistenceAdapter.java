package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.application.port.out.LoadFundingViewPort;
import github.lms.lemuel.investment.application.port.out.SaveFundingViewPort;
import github.lms.lemuel.investment.domain.FundingViewStatus;
import github.lms.lemuel.investment.domain.SellerFundingView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
public class FundingViewPersistenceAdapter implements SaveFundingViewPort, LoadFundingViewPort {

    private final SellerFundingViewRepository repository;

    public FundingViewPersistenceAdapter(SellerFundingViewRepository repository) {
        this.repository = repository;
    }

    @Override
    public void upsert(SellerFundingView view) {
        // settlementId 가 할당 식별자이므로 save() 는 merge(존재 시 UPDATE / 없으면 INSERT) = 멱등 UPSERT.
        repository.save(new SellerFundingViewJpaEntity(
                view.getSettlementId(),
                view.getSellerId(),
                view.getAmount(),
                view.getStatus(),
                LocalDateTime.now()));
    }

    @Override
    public BigDecimal sumConfirmedBySeller(long sellerId) {
        return repository.sumBySellerAndStatus(sellerId, FundingViewStatus.CONFIRMED);
    }

    @Override
    public BigDecimal sumConfirmedBySellerForUpdate(long sellerId) {
        // 재원 행을 FOR UPDATE 로 잡아 동시 집행을 직렬화한 뒤 합계를 계산한다(loan 패턴 동형).
        return repository.findBySellerAndStatusForUpdate(sellerId, FundingViewStatus.CONFIRMED).stream()
                .map(SellerFundingViewJpaEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
