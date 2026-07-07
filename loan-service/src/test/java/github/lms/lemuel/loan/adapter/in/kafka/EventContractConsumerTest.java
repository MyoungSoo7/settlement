package github.lms.lemuel.loan.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase.ApplyRepaymentCommand;
import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase;
import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase.IngestSettlementCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — settlement 가 발행하는 정산 생성/확정 이벤트의 정본 샘플을
 * 실제 컨슈머 파싱 코드에 통과시켜, 담보 뷰 적재·상환 saga 진입점이 계약 값 그대로 동작하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class EventContractConsumerTest {

    @Mock IngestSettlementUseCase ingestSettlementUseCase;
    @Mock ApplyRepaymentUseCase applyRepaymentUseCase;
    @Mock ProcessedEventRepository processedEventRepository;

    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("settlement.created 정본 샘플 → 담보 뷰 적재 커맨드에 계약 값 그대로 전달된다")
    void settlementCreatedSample_flowsIntoIngestCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementCreatedConsumer consumer = new SettlementCreatedConsumer(
                ingestSettlementUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.settlement.created");
        consumer.onSettlementCreated(recordOf("lemuel.settlement.created", sample), mock(Acknowledgment.class));

        verify(ingestSettlementUseCase).ingest(new IngestSettlementCommand(
                9001L, 777L, new BigDecimal("43425"), LocalDate.of(2026, 7, 10)));
    }

    @Test
    @DisplayName("settlement.confirmed 정본 샘플 → 상환 차감 커맨드에 계약 값 그대로 전달된다")
    void settlementConfirmedSample_flowsIntoRepaymentCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        SettlementConfirmedConsumer consumer = new SettlementConfirmedConsumer(
                applyRepaymentUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.settlement.confirmed");
        consumer.onSettlementConfirmed(recordOf("lemuel.settlement.confirmed", sample), mock(Acknowledgment.class));

        verify(applyRepaymentUseCase).apply(new ApplyRepaymentCommand(
                9001L, 777L, new BigDecimal("43425")));
    }
}
