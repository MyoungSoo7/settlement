package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.StockRecommendation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StockRecommendationPersistenceAdapterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 16);

    @Mock
    StockRecommendationRepository repository;

    private StockRecommendationPersistenceAdapter adapter() {
        return new StockRecommendationPersistenceAdapter(repository);
    }

    private static StockRecommendation reco(String code, String name, int order) {
        return StockRecommendation.rehydrate(code, name, "반도체", "규칙 5종 통과", DATE,
                new BigDecimal("50000"), new BigDecimal("43900"), new BigDecimal("56700"), order);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("replaceForDate — 그날치 삭제 후 새 세트 삽입(삭제가 먼저), 도메인→엔티티 매핑 검증")
    void replacesDeletingFirst() {
        adapter().replaceForDate(DATE, List.of(reco("005930", "삼성전자", 1), reco("000660", "SK하이닉스", 2)));

        InOrder order = inOrder(repository);
        order.verify(repository).deleteByRecommendedDate(DATE);
        ArgumentCaptor<List<StockRecommendationJpaEntity>> captor = ArgumentCaptor.forClass(List.class);
        order.verify(repository).saveAll(captor.capture());

        List<StockRecommendationJpaEntity> saved = captor.getValue();
        assertThat(saved).extracting(StockRecommendationJpaEntity::getStockCode)
                .containsExactly("005930", "000660");
        assertThat(saved).allSatisfy(e -> {
            assertThat(e.getRecommendedDate()).isEqualTo(DATE);
            assertThat(e.getSector()).isEqualTo("반도체");
            assertThat(e.getEntryPrice()).isEqualByComparingTo("50000");
            assertThat(e.getStopLossPrice()).isEqualByComparingTo("43900");
            assertThat(e.getTakeProfitPrice()).isEqualByComparingTo("56700");
        });
    }

    @Test
    @DisplayName("빈 세트 — 삭제만 하고 saveAll 은 호출하지 않음(그날치 비움)")
    void emptySetDeletesOnly() {
        adapter().replaceForDate(DATE, List.of());

        verify(repository).deleteByRecommendedDate(DATE);
        verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
