package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 — shared-common testFixtures 의 정본 샘플이 컨슈머 파싱을 그대로 통과해
 * 유스케이스 커맨드로 흘러야 한다 (ADR 0024 소비측 절반, loan EventContractConsumerTest 동형).
 */
@ExtendWith(MockitoExtension.class)
class EventContractConsumerTest {

    @Mock IngestOrgProjectionUseCase ingestOrgProjectionUseCase;
    @Mock ProcessedEventRepository processedEventRepository;

    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    @Test
    @DisplayName("member_removed 정본 샘플 → 카드 정지 커맨드로 전달된다")
    void memberRemovedSample_flowsIntoSuspendCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRemovedConsumer consumer = new OrganizationMemberRemovedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_removed");
        consumer.onMemberRemoved(
                recordOf("lemuel.organization.member_removed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).removeMember(3001L, 888L);
    }

    @Test
    @DisplayName("member_role_changed 정본 샘플 → 역할 갱신 커맨드로 전달된다")
    void memberRoleChangedSample_flowsIntoUpdateCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRoleChangedConsumer consumer = new OrganizationMemberRoleChangedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_role_changed");
        consumer.onMemberRoleChanged(
                recordOf("lemuel.organization.member_role_changed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).upsertMember(new MemberCommand(3001L, 888L, "MANAGER"));
    }

    @Test
    @DisplayName("이미 처리한 event_id 는 재수신해도 커맨드를 호출하지 않는다 — 멱등")
    void duplicateEventIsSkipped() {
        when(processedEventRepository.existsById(any())).thenReturn(true);
        OrganizationMemberRemovedConsumer consumer = new OrganizationMemberRemovedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_removed");
        consumer.onMemberRemoved(
                recordOf("lemuel.organization.member_removed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase, never()).removeMember(any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("필수 필드 누락 payload → IllegalArgumentException(즉시 DLT) + 커맨드 미호출")
    void missingRequiredFieldThrows() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRemovedConsumer consumer = new OrganizationMemberRemovedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        assertThatThrownBy(() -> consumer.onMemberRemoved(
                recordOf("lemuel.organization.member_removed", "{\"organizationId\":3001}"),
                mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        verify(ingestOrgProjectionUseCase, never()).removeMember(any(Long.class), any(Long.class));
    }
}
