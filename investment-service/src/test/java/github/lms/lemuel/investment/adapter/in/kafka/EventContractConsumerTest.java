package github.lms.lemuel.investment.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase;
import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase.IngestConfirmedSettlementCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — settlement 가 발행하는 정산 확정 이벤트의 정본 샘플을
 * investment 의 실제 컨슈머 파싱 코드에 통과시켜, 재원 프로젝션 적재 커맨드가 계약 값 그대로
 * 채워지는지 검증한다(loan {@code EventContractConsumerTest} 동형). 프로듀서/컨슈머 양방향
 * 계약 테스트로 필드명·타입 드리프트를 빌드 시점에 차단한다.
 */
@ExtendWith(MockitoExtension.class)
class EventContractConsumerTest {

    @Mock IngestConfirmedSettlementUseCase ingestConfirmedSettlementUseCase;
    @Mock ProcessedEventRepository processedEventRepository;

    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("settlement.confirmed 정본 샘플 → 재원 프로젝션 적재 커맨드에 계약 값 그대로 전달된다")
    void settlementConfirmedSample_flowsIntoIngestCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementConfirmedConsumer consumer = new SettlementConfirmedConsumer(
                ingestConfirmedSettlementUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.settlement.confirmed");
        consumer.onSettlementConfirmed(recordOf("lemuel.settlement.confirmed", sample), mock(Acknowledgment.class));

        verify(ingestConfirmedSettlementUseCase).ingest(new IngestConfirmedSettlementCommand(
                9001L, 777L, new BigDecimal("43425")));
    }
}
