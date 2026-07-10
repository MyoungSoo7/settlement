package github.lms.lemuel.settlement.adapter.out.search;

import github.lms.lemuel.settlement.adapter.out.persistence.SettlementIndexQueueJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementIndexQueueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementIndexQueueAdapterTest {

    @Mock SpringDataSettlementIndexQueueRepository queueRepository;

    SettlementIndexQueueAdapter adapter;

    @Test
    @DisplayName("enqueueForRetry — settlementId/operation 으로 재시도 큐 엔티티를 저장한다")
    void enqueueForRetry_savesQueueEntity() {
        adapter = new SettlementIndexQueueAdapter(queueRepository);

        adapter.enqueueForRetry(7L, "INDEX");

        ArgumentCaptor<SettlementIndexQueueJpaEntity> captor =
                ArgumentCaptor.forClass(SettlementIndexQueueJpaEntity.class);
        verify(queueRepository).save(captor.capture());
        SettlementIndexQueueJpaEntity saved = captor.getValue();
        assertThat(saved.getSettlementId()).isEqualTo(7L);
        assertThat(saved.getOperation()).isEqualTo("INDEX");
        assertThat(saved.getNextRetryAt()).isNotNull();
    }
}
