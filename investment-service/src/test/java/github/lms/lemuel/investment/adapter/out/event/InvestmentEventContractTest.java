package github.lms.lemuel.investment.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import github.lms.lemuel.investment.domain.InvestmentOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — investment 가 발행하는 InvestmentExecuted 이벤트가
 * shared-common 의 계약 스키마(lemuel.investment.executed)를 통과해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    InvestmentEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new InvestmentEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    @Test
    @DisplayName("InvestmentExecuted 페이로드는 lemuel.investment.executed 계약을 만족한다")
    void investmentExecuted_satisfiesContract() {
        InvestmentOrder order = InvestmentOrder.reconstitute(5001L, 777L, "005930",
                new BigDecimal("1000000"), 82, "AA", InvestmentOrderStatus.EXECUTED, LocalDateTime.now());

        publisher.publishExecuted(order);

        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        EventContractValidator.assertValid("lemuel.investment.executed", outboxCaptor.getValue().getPayload());
    }
}
