package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.domain.FundingViewStatus;
import github.lms.lemuel.investment.domain.SellerFundingView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FundingViewPersistenceAdapterTest {

    @Mock SellerFundingViewRepository repository;

    private FundingViewPersistenceAdapter adapter() {
        return new FundingViewPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("upsert 는 뷰를 엔티티로 매핑해 save(멱등 UPSERT)한다")
    void upsertMapsAndSaves() {
        SellerFundingView view = SellerFundingView.confirmed(9001L, 777L, new BigDecimal("43425"));

        adapter().upsert(view);

        ArgumentCaptor<SellerFundingViewJpaEntity> captor =
                ArgumentCaptor.forClass(SellerFundingViewJpaEntity.class);
        verify(repository).save(captor.capture());
        SellerFundingViewJpaEntity saved = captor.getValue();
        assertThat(saved.getSettlementId()).isEqualTo(9001L);
        assertThat(saved.getSellerId()).isEqualTo(777L);
        assertThat(saved.getAmount()).isEqualByComparingTo("43425");
        assertThat(saved.getStatus()).isEqualTo(FundingViewStatus.CONFIRMED);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("sumConfirmedBySeller 는 CONFIRMED 합계를 위임한다")
    void sumConfirmed() {
        when(repository.sumBySellerAndStatus(777L, FundingViewStatus.CONFIRMED))
                .thenReturn(new BigDecimal("2000000"));

        assertThat(adapter().sumConfirmedBySeller(777L)).isEqualByComparingTo("2000000");
    }
}
