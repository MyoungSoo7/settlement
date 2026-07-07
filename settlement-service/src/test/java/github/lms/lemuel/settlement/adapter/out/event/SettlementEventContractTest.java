package github.lms.lemuel.settlement.adapter.out.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — settlement 가 loan 으로 발행하는 정산 생성/확정 이벤트가
 * shared-common 의 계약 스키마를 통과해야 한다. loan 의 상환 saga 가 이 계약에 의존한다.
 */
@ExtendWith(MockitoExtension.class)
class SettlementEventContractTest {

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    SettlementKafkaEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new SettlementKafkaEventPublisherAdapter(saveOutboxEventPort, new ObjectMapper());
    }

    private String savedPayload() {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        return outboxCaptor.getValue().getPayload();
    }

    @Test
    @DisplayName("SettlementCreated 페이로드는 lemuel.settlement.created 계약을 만족한다")
    void settlementCreated_satisfiesContract() {
        publisher.publishSettlementCreated(9001L, 777L, new BigDecimal("43425"),
                LocalDate.of(2026, 7, 10));

        EventContractValidator.assertValid("lemuel.settlement.created", savedPayload());
    }

    @Test
    @DisplayName("SettlementCreated(dueDate null) 페이로드도 계약을 만족한다 — null 허용 필드")
    void settlementCreated_nullDueDate_satisfiesContract() {
        publisher.publishSettlementCreated(9001L, 777L, new BigDecimal("43425"), null);

        EventContractValidator.assertValid("lemuel.settlement.created", savedPayload());
    }

    @Test
    @DisplayName("SettlementConfirmed 페이로드는 lemuel.settlement.confirmed 계약을 만족한다")
    void settlementConfirmed_satisfiesContract() {
        publisher.publishSettlementConfirmed(9001L, 777L, new BigDecimal("43425"));

        EventContractValidator.assertValid("lemuel.settlement.confirmed", savedPayload());
    }
}
