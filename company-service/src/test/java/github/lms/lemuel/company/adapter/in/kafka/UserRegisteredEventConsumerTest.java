package github.lms.lemuel.company.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.company.application.port.out.SaveSellerPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRegisteredEventConsumerTest {

    private final SaveSellerPort saveSellerPort = mock(SaveSellerPort.class);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserRegisteredEventConsumer consumer =
            new UserRegisteredEventConsumer(saveSellerPort, processedEventRepository, objectMapper);

    private ConsumerRecord<String, String> record(String payload) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.user.registered", 0, 0L, "key", payload);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("userId+email 페이로드 → 셀러 적재 후 멱등 마커 저장·ack")
    void recordsSeller() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onUserRegistered(record("{\"userId\":7,\"email\":\"a@b.com\"}"), ack);

        verify(saveSellerPort).record(7L, "a@b.com");
        verify(processedEventRepository).save(any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("email 이 없으면 null 로 적재")
    void recordsSellerWithoutEmail() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onUserRegistered(record("{\"userId\":9}"), ack);

        verify(saveSellerPort).record(9L, null);
    }

    @Test
    @DisplayName("userId 누락 페이로드는 IllegalArgumentException")
    void rejectsMissingUserId() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThrows(IllegalArgumentException.class,
                () -> consumer.onUserRegistered(record("{\"email\":\"a@b.com\"}"), ack));
    }

    @Test
    @DisplayName("이미 처리된 이벤트는 셀러 적재 없이 ack (멱등 스킵)")
    void skipsAlreadyProcessed() {
        when(processedEventRepository.existsById(any())).thenReturn(true);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.onUserRegistered(record("{\"userId\":7,\"email\":\"a@b.com\"}"), ack);

        verify(ack).acknowledge();
        verify(saveSellerPort, org.mockito.Mockito.never()).record(any(), any());
    }

    @Test
    @DisplayName("consumerGroup·eventType 훅 값")
    void hooks() {
        assertEquals("lemuel-company", consumer.consumerGroup());
        assertEquals("UserRegistered", consumer.eventType());
    }
}
