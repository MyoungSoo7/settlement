package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.application.port.out.LoadStockRecommendationPort;
import github.lms.lemuel.investment.domain.StockRecommendation;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StockRecommendationPersistenceAdapter implements LoadStockRecommendationPort {

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
}
