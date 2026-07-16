package github.lms.lemuel.investment.adapter.out.persistence;

import github.lms.lemuel.investment.application.exception.InvestmentNotFoundException;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestmentOrderPersistenceAdapterTest {

    @Mock InvestmentOrderRepository repository;

    private InvestmentOrderPersistenceAdapter adapter() {
        return new InvestmentOrderPersistenceAdapter(repository);
    }

    private static InvestmentOrderJpaEntity entity(long id, InvestmentOrderStatus status, LocalDateTime createdAt) {
        return new InvestmentOrderJpaEntity(id, 7L, "005930", new BigDecimal("1000000"),
                82, "AA", status, createdAt, 0L);
    }

    @Test
    @DisplayName("save 는 도메인→엔티티 매핑 후 저장하고 도메인으로 되돌린다")
    void savesAndMapsBack() {
        LocalDateTime now = LocalDateTime.now();
        InvestmentOrder order = InvestmentOrder.reconstitute(null, 7L, "005930",
                new BigDecimal("1000000"), 82, "AA", InvestmentOrderStatus.REQUESTED, now);
        when(repository.save(any())).thenReturn(entity(100L, InvestmentOrderStatus.REQUESTED, now));

        InvestmentOrder result = adapter().save(order);

        ArgumentCaptor<InvestmentOrderJpaEntity> captor = ArgumentCaptor.forClass(InvestmentOrderJpaEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        InvestmentOrderJpaEntity saved = captor.getValue();
        assertThat(saved.getSellerId()).isEqualTo(7L);
        assertThat(saved.getStockCode()).isEqualTo("005930");
        assertThat(saved.getAmount()).isEqualByComparingTo("1000000");
        assertThat(saved.getScoreAtOrder()).isEqualTo(82);
        assertThat(saved.getGradeAtOrder()).isEqualTo("AA");
        assertThat(saved.getStatus()).isEqualTo(InvestmentOrderStatus.REQUESTED);
        assertThat(saved.getCreatedAt()).isEqualTo(now);

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getStatus()).isEqualTo(InvestmentOrderStatus.REQUESTED);
    }

    @Test
    @DisplayName("createdAt 이 null 이면 now() 로 채워 저장한다")
    void nullCreatedAtDefaultsToNow() {
        InvestmentOrder order = InvestmentOrder.reconstitute(null, 7L, "005930",
                new BigDecimal("500000"), 70, "A", InvestmentOrderStatus.REQUESTED, null);
        when(repository.save(any())).thenReturn(entity(1L, InvestmentOrderStatus.REQUESTED, LocalDateTime.now()));

        adapter().save(order);

        ArgumentCaptor<InvestmentOrderJpaEntity> captor = ArgumentCaptor.forClass(InvestmentOrderJpaEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("load 는 존재하면 도메인으로 반환한다")
    void loadFound() {
        LocalDateTime now = LocalDateTime.now();
        when(repository.findById(5L)).thenReturn(Optional.of(entity(5L, InvestmentOrderStatus.EXECUTED, now)));

        InvestmentOrder order = adapter().load(5L);

        assertThat(order.getId()).isEqualTo(5L);
        assertThat(order.getStatus()).isEqualTo(InvestmentOrderStatus.EXECUTED);
        assertThat(order.getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("load 는 없으면 InvestmentNotFoundException")
    void loadNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter().load(404L))
                .isInstanceOf(InvestmentNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("findBySeller 는 셀러 주문들을 도메인 리스트로 반환한다")
    void findBySeller() {
        when(repository.findBySellerIdOrderByIdAsc(7L)).thenReturn(List.of(
                entity(1L, InvestmentOrderStatus.REQUESTED, LocalDateTime.now()),
                entity(2L, InvestmentOrderStatus.EXECUTED, LocalDateTime.now())));

        List<InvestmentOrder> orders = adapter().findBySeller(7L);

        assertThat(orders).hasSize(2);
        assertThat(orders).extracting(InvestmentOrder::getId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("save 는 load 시점 낙관적 락 version 을 엔티티에 되싣는다(merge 충돌 판정용)")
    void carriesVersionForOptimisticLock() {
        LocalDateTime now = LocalDateTime.now();
        InvestmentOrder loaded = InvestmentOrder.reconstitute(5L, 7L, "005930",
                new BigDecimal("1000000"), 82, "AA", InvestmentOrderStatus.APPROVED, now, 3L);
        when(repository.save(any())).thenReturn(entity(5L, InvestmentOrderStatus.APPROVED, now));

        adapter().save(loaded);

        ArgumentCaptor<InvestmentOrderJpaEntity> captor = ArgumentCaptor.forClass(InvestmentOrderJpaEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("sumExecutedAmountBySeller 는 리포지토리 합계를 위임한다")
    void sumExecuted() {
        when(repository.sumBySellerAndStatus(7L, InvestmentOrderStatus.EXECUTED))
                .thenReturn(new BigDecimal("1500000"));

        assertThat(adapter().sumExecutedAmountBySeller(7L)).isEqualByComparingTo("1500000");
    }
}
