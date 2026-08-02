package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.MemberCommand;
import github.lms.lemuel.card.application.port.in.IngestOrgProjectionUseCase.OrgCommand;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase.ReputationCommand;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * card-service 컨슈머 5종의 계약 테스트 — 정본 샘플(shared-common testFixtures)이 실제 컨슈머
 * 파싱 코드를 그대로 통과해 올바른 커맨드로 매핑되는지 검증한다(ADR 0024 컨슈머 계약 테스트).
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

    // ── OrganizationCreatedConsumer ──

    @Test
    @DisplayName("organization.created(SELLER) 정본 샘플 → 조직 생성 + 오너를 OWNER 멤버로 등록")
    void organizationCreatedSample_sellerType_flowsIntoCreateOrgAndOwnerMember() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationCreatedConsumer consumer = new OrganizationCreatedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.created");
        consumer.onOrganizationCreated(
                recordOf("lemuel.organization.created", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase)
                .createOrg(new OrgCommand(3001L, "무신사 스토어", "SELLER", "SELLER-777"));
        verify(ingestOrgProjectionUseCase).upsertMember(new MemberCommand(3001L, 777L, "OWNER", null));
    }

    @Test
    @DisplayName("organization.created(CORPORATE) → 무시(예외 없이 useCase 미호출)")
    void organizationCreatedSample_corporateType_isIgnored() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationCreatedConsumer consumer = new OrganizationCreatedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String corporateSample = "{\"organizationId\":4001,\"name\":\"어떤 상장사\","
                + "\"type\":\"CORPORATE\",\"externalRef\":\"005930\",\"ownerUserId\":999}";
        consumer.onOrganizationCreated(
                recordOf("lemuel.organization.created", corporateSample), mock(Acknowledgment.class));

        verifyNoInteractions(ingestOrgProjectionUseCase);
    }

    // ── OrganizationMemberJoinedConsumer ──

    @Test
    @DisplayName("member_joined 정본 샘플 → 역할과 함께 멤버 등록 커맨드로 전달된다")
    void memberJoinedSample_flowsIntoUpsertCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberJoinedConsumer consumer = new OrganizationMemberJoinedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_joined");
        consumer.onMemberJoined(
                recordOf("lemuel.organization.member_joined", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).upsertMember(new MemberCommand(3001L, 888L, "MANAGER", 9001L));
    }

    // ── OrganizationMemberRoleChangedConsumer ──

    @Test
    @DisplayName("member_role_changed 정본 샘플 → 역할 갱신 커맨드로 전달된다")
    void memberRoleChangedSample_flowsIntoUpdateCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRoleChangedConsumer consumer = new OrganizationMemberRoleChangedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_role_changed");
        consumer.onMemberRoleChanged(
                recordOf("lemuel.organization.member_role_changed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).upsertMember(new MemberCommand(3001L, 888L, "MANAGER", 9001L));
    }

    // ── OrganizationMemberRemovedConsumer ──

    @Test
    @DisplayName("member_removed 정본 샘플 → 멤버 제거 커맨드로 전달된다")
    void memberRemovedSample_flowsIntoRemoveCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        OrganizationMemberRemovedConsumer consumer = new OrganizationMemberRemovedConsumer(
                ingestOrgProjectionUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.organization.member_removed");
        consumer.onMemberRemoved(
                recordOf("lemuel.organization.member_removed", sample), mock(Acknowledgment.class));

        verify(ingestOrgProjectionUseCase).removeMember(3001L, 888L, 9001L);
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

        verify(ingestOrgProjectionUseCase, never()).removeMember(any(Long.class), any(Long.class), any(Long.class));
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

        verify(ingestOrgProjectionUseCase, never()).removeMember(any(Long.class), any(Long.class), any(Long.class));
    }

    // ── CompanyReputationChangedConsumer ──

    @Test
    @DisplayName("company.reputation_changed 정본 샘플 → sellerIds 배열 그대로 커맨드에 담겨 전달된다")
    void reputationChangedSample_flowsIntoIngestCommand() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        CompanyReputationChangedConsumer consumer = new CompanyReputationChangedConsumer(
                ingestReputationUseCase, processedEventRepository, objectMapper);

        String sample = EventContractValidator.canonicalSample("lemuel.company.reputation_changed");
        consumer.onReputationChanged(
                recordOf("lemuel.company.reputation_changed", sample), mock(Acknowledgment.class));

        verify(ingestReputationUseCase).ingest(new ReputationCommand(List.of(777L, 1001L), "C"));
    }

    @Test
    @DisplayName("company.reputation_changed — grade 필드 누락 → IllegalArgumentException")
    void reputationChanged_missingGrade_throws() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        CompanyReputationChangedConsumer consumer = new CompanyReputationChangedConsumer(
                ingestReputationUseCase, processedEventRepository, objectMapper);

        assertThatThrownBy(() -> consumer.onReputationChanged(
                recordOf("lemuel.company.reputation_changed",
                        "{\"stockCode\":\"005930\",\"sellerIds\":[777]}"),
                mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grade");

        verify(ingestReputationUseCase, never()).ingest(any());
    }
}
