package github.lms.lemuel.investment.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * publishExecuted 의 직렬화 실패 분기 검증 — ObjectMapper 가 JsonProcessingException 을 던지면
 * IllegalStateException 으로 감싸고 Outbox 저장을 시도하지 않는다.
 */
class InvestmentEventPublisherAdapterTest {

    private static InvestmentOrder order() {
        return InvestmentOrder.reconstitute(5001L, 777L, "005930", new BigDecimal("1000000"),
                82, "AA", InvestmentOrderStatus.EXECUTED, LocalDateTime.now());
    }

    @Test
    @DisplayName("직렬화 실패는 IllegalStateException 으로 감싸고 저장하지 않는다")
    void serializationFailureWrapped() throws JsonProcessingException {
        SaveOutboxEventPort save = mock(SaveOutboxEventPort.class);
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(anyMap()))
                .thenThrow(new JsonProcessingException("boom") { });

        InvestmentEventPublisherAdapter adapter = new InvestmentEventPublisherAdapter(save, mapper);

        assertThatThrownBy(() -> adapter.publishExecuted(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("직렬화 실패");
        verifyNoInteractions(save);
    }
}
