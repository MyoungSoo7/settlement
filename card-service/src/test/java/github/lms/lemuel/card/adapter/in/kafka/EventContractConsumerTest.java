package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.OrgCommand;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase.ReputationCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
    @Mock IngestReputationUseCase ingestReputationUseCase;
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
    @DisplayName("created 정본 샘플(SELLER) → 조직 등록 커맨드로 전달된다 — 소유자 OWNER 적재는 유스케이스 책임")
    void createdSample_flowsIntoRegisterCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationCreatedConsumer consumer = new OrganizationCreatedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.created");
        consumer.onCreated(recordOf("lemuel.organization.created", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).registerOrg(
                new OrgCommand(3001L, "무신사 스토어", "SELLER", "SELLER-777", 777L));
    }

    @Test
    @DisplayName("created 가 SELLER 가 아니면 커맨드를 호출하지 않고 정상 종료한다 — 멱등 마커는 남는다")
    void createdNonSeller_isIgnored() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationCreatedConsumer consumer = new OrganizationCreatedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        consumer.onCreated(recordOf("lemuel.organization.created",
                        "{\"organizationId\":4001,\"name\":\"상장법인\",\"type\":\"CORPORATE\",\"ownerUserId\":9}"),
                mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase, never()).registerOrg(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("member_joined 정본 샘플 → 멤버 upsert 커맨드로 전달된다")
    void memberJoinedSample_flowsIntoUpsertCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberJoinedConsumer consumer = new OrganizationMemberJoinedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_joined");
        consumer.onMemberJoined(
                recordOf("lemuel.organization.member_joined", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).upsertMember(new MemberCommand(3001L, 888L, "MANAGER"));
    }

    @Test
    @DisplayName("reputation_changed 정본 샘플 → 등급·sellerIds 팬아웃 커맨드로 전달된다")
    void reputationChangedSample_flowsIntoIngestCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        CompanyReputationChangedConsumer consumer = new CompanyReputationChangedConsumer(
                ingestReputationUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.company.reputation_changed");
        consumer.onReputationChanged(
                recordOf("lemuel.company.reputation_changed", sample), mock(Acknowledgment.class));

        verify(ingestReputationUseCase).ingest(new ReputationCommand("C", List.of(777L, 1001L)));
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
