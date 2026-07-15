package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.application.port.out.LoadStockRecommendationPort;
import github.lms.lemuel.investment.application.port.out.SaveStockRecommendationPort;
import github.lms.lemuel.investment.domain.StockRecommendation;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Component
public class StockRecommendationPersistenceAdapter
        implements LoadStockRecommendationPort, SaveStockRecommendationPort {

    private final StockRecommendationRepository repository;

    public StockRecommendationPersistenceAdapter(StockRecommendationRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<StockRecommendation> loadLatest() {
        return repository.findLatestRecommendedDate()
                .map(repository::findByRecommendedDateOrderByDisplayOrderAsc)
                .orElseGet(List::of)
                .stream()
                .map(e -> StockRecommendation.rehydrate(
                        e.getStockCode(), e.getStockName(), e.getSector(), e.getReason(),
                        e.getRecommendedDate(), e.getEntryPrice(), e.getStopLossPrice(),
                        e.getTakeProfitPrice(), e.getDisplayOrder()))
                .toList();
    }

    @Override
    @Transactional
    public void replaceForDate(LocalDate recommendedDate, List<StockRecommendation> recommendations) {
        repository.deleteByRecommendedDate(recommendedDate);
        if (recommendations.isEmpty()) {
            return;
        }
        repository.saveAll(recommendations.stream()
                .map(StockRecommendationJpaEntity::fromDomain)
                .toList());
    }
}
