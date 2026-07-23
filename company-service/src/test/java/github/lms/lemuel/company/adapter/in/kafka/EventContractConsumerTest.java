package github.lms.lemuel.company.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.company.application.port.out.SaveSellerPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — order 가 발행하는 user.registered 이벤트의 정본 샘플을
 * 실제 컨슈머 파싱 코드에 통과시켜, 셀러 적재가 계약 값 그대로 동작하는지 검증한다.
 * 계약상 email 은 null 허용({@code ["string","null"]}) — null 분기도 함께 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class EventContractConsumerTest {

    private static final String TOPIC = "lemuel.user.registered";

    @Mock SaveSellerPort saveSellerPort;
    @Mock ProcessedEventRepository processedEventRepository;

    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private UserRegisteredEventConsumer consumer() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        return new UserRegisteredEventConsumer(saveSellerPort, processedEventRepository, objectMapper);
    }

    @Test
    @DisplayName("user.registered 정본 샘플 → 셀러 적재에 계약 값 그대로 전달된다")
    void userRegisteredSample_flowsIntoSellerRecord() {
        String sample = EventContractValidator.canonicalSample(TOPIC);

        consumer().onUserRegistered(recordOf(sample), mock(Acknowledgment.class));

        verify(saveSellerPort).record(301L, "seller777@lemuel.io");
    }

    @Test
    @DisplayName("계약상 email null 페이로드도 셀러 적재가 깨지지 않는다")
    void userRegistered_nullEmail_stillRecordsSeller() {
        String payload = "{\"userId\":301,\"email\":null}";
        EventContractValidator.assertValid(TOPIC, payload);

        consumer().onUserRegistered(recordOf(payload), mock(Acknowledgment.class));

        verify(saveSellerPort).record(org.mockito.ArgumentMatchers.eq(301L), isNull());
    }
}
