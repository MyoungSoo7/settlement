package github.lms.lemuel.insurance.adapter.out.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.insurance.domain.CommissionConstants;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.CommissionScheduleFactory;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 보험 이벤트 발행 어댑터 단위 테스트.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>aggregateType = "Insurance" 고정 (KafkaOutboxPublisher 토픽 라우팅 기반)</li>
 *   <li>aggregateId = policyNumber (일관된 파티션 키 — 한 계약의 이벤트가 같은 파티션에)</li>
 *   <li>금액이 JSON string(DATA-STANDARD N5) 으로 직렬화되는지</li>
 *   <li>계약 발행/상태변경/수수료 확정 각 이벤트가 올바른 eventType 으로 저장되는지</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InsurancePolicyEventPublisherTest {

    @Mock
    SaveOutboxEventPort saveOutboxEventPort;

    @Captor
    ArgumentCaptor<OutboxEvent> eventCaptor;

    InsurancePolicyEventPublisherAdapter publisher;

    final ObjectMapper mapper = OutboxJson.mapper();

    @BeforeEach
    void setUp() {
        publisher = new InsurancePolicyEventPublisherAdapter(saveOutboxEventPort, mapper);
    }

    // ── 테스트 픽스처 ──────────────────────────────────────────────────────────

    private static Policy activePolicy() {
        return Policy.builder()
                .policyNumber("INS-2026-000001")
                .status(PolicyStatus.ACTIVE)
                .effectiveDate(LocalDate.of(2026, 1, 1))
                .maturityDate(LocalDate.of(2036, 1, 1))
                .premiumAmount(new BigDecimal("150000.00"))
                .fcId("FC-001")
                .consecutivePremiumFailures(0)
                .build();
    }

    private static List<CommissionSchedule> buildSchedules(Policy policy) {
        return CommissionScheduleFactory.createFirstYearSchedule(
                policy.getPolicyNumber(),
                policy.getFcId(),
                new BigDecimal("1200000.00"),
                new BigDecimal("0.12")   // 수수료율 12%
        );
    }

    // ── PolicyIssued ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("계약 발행 이벤트의 aggregateType 은 'Insurance' 이고 aggregateId 는 policyNumber 이다")
    void policyIssued_aggregateTypeAndId_areCorrect() {
        Policy policy = activePolicy();

        publisher.publishPolicyIssued(policy, new BigDecimal("50000000.00"));

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("Insurance");
        assertThat(event.getAggregateId()).isEqualTo("INS-2026-000001");
        assertThat(event.getEventType()).isEqualTo("InsurancePolicyIssued");
    }

    @Test
    @DisplayName("계약 발행 이벤트의 보험료·보장금액이 JSON string(N5)으로 직렬화된다")
    void policyIssued_amountsAreJsonStrings() throws Exception {
        publisher.publishPolicyIssued(activePolicy(), new BigDecimal("50000000.00"));

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        String payload = eventCaptor.getValue().getPayload();
        JsonNode node = new ObjectMapper().readTree(payload);

        assertThat(node.get("premiumAmount").isTextual())
                .as("premiumAmount 는 JSON string 이어야 한다(N5)").isTrue();
        assertThat(node.get("coverageAmount").isTextual())
                .as("coverageAmount 는 JSON string 이어야 한다(N5)").isTrue();
        assertThat(node.get("premiumAmount").asText()).isEqualTo("150000.00");
        assertThat(node.get("coverageAmount").asText()).isEqualTo("50000000.00");
    }

    @Test
    @DisplayName("계약 발행 이벤트 페이로드에 policyNumber·fcId·status·effectiveDate 가 모두 포함된다")
    void policyIssued_payloadContainsRequiredFields() throws Exception {
        publisher.publishPolicyIssued(activePolicy(), new BigDecimal("50000000.00"));

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        JsonNode node = new ObjectMapper().readTree(eventCaptor.getValue().getPayload());

        assertThat(node.get("policyNumber").asText()).isEqualTo("INS-2026-000001");
        assertThat(node.get("fcId").asText()).isEqualTo("FC-001");
        assertThat(node.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(node.get("effectiveDate").asText()).isEqualTo("2026-01-01");
        assertThat(node.get("maturityDate").asText()).isEqualTo("2036-01-01");
    }

    // ── PolicyStatusChanged ──────────────────────────────────────────────────

    @Test
    @DisplayName("상태 변경 이벤트의 eventType 이 'InsurancePolicyStatusChanged' 이고 파티션 키는 policyNumber 이다")
    void policyStatusChanged_eventTypeAndPartitionKey() {
        Policy policy = activePolicy();
        policy.recordPremiumFailure(LocalDate.of(2026, 2, 1));
        policy.recordPremiumFailure(LocalDate.of(2026, 3, 1));  // 2회 → LAPSED

        publisher.publishPolicyStatusChanged(policy, PolicyStatus.ACTIVE);

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("InsurancePolicyStatusChanged");
        assertThat(event.getAggregateId()).isEqualTo("INS-2026-000001");
    }

    @Test
    @DisplayName("상태 변경 이벤트 페이로드에 previousStatus·newStatus 가 포함된다")
    void policyStatusChanged_payloadHasBothStatuses() throws Exception {
        Policy policy = activePolicy();
        policy.recordPremiumFailure(LocalDate.of(2026, 2, 1));
        policy.recordPremiumFailure(LocalDate.of(2026, 3, 1));  // ACTIVE → LAPSED

        publisher.publishPolicyStatusChanged(policy, PolicyStatus.ACTIVE);

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        JsonNode node = new ObjectMapper().readTree(eventCaptor.getValue().getPayload());
        assertThat(node.get("previousStatus").asText()).isEqualTo("ACTIVE");
        assertThat(node.get("newStatus").asText()).isEqualTo("LAPSED");
        assertThat(node.get("lapsedAt").asText()).isEqualTo("2026-03-01");
    }

    @Test
    @DisplayName("상태 변경 이벤트의 aggregateType 은 'Insurance' 이다")
    void policyStatusChanged_aggregateTypeIsInsurance() {
        publisher.publishPolicyStatusChanged(activePolicy(), PolicyStatus.ACTIVE);

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getAggregateType()).isEqualTo("Insurance");
    }

    // ── CommissionConfirmed ──────────────────────────────────────────────────

    @Test
    @DisplayName("수수료 확정 이벤트의 eventType 이 'InsuranceCommissionConfirmed' 이고 파티션 키는 policyNumber 이다")
    void commissionConfirmed_eventTypeAndPartitionKey() {
        Policy policy = activePolicy();
        List<CommissionSchedule> schedules = buildSchedules(policy);

        publisher.publishCommissionConfirmed(policy.getPolicyNumber(), schedules);

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("InsuranceCommissionConfirmed");
        assertThat(event.getAggregateId()).isEqualTo("INS-2026-000001");
    }

    @Test
    @DisplayName("수수료 확정 이벤트의 schedules 배열 크기가 D4 기준 12개이다")
    void commissionConfirmed_schedulesHas12Items() throws Exception {
        Policy policy = activePolicy();
        List<CommissionSchedule> schedules = buildSchedules(policy);

        publisher.publishCommissionConfirmed(policy.getPolicyNumber(), schedules);

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        JsonNode node = new ObjectMapper().readTree(eventCaptor.getValue().getPayload());
        assertThat(node.get("schedules").size()).isEqualTo(CommissionConstants.INSTALLMENT_COUNT);
    }

    @Test
    @DisplayName("수수료 확정 이벤트의 installmentAmount 가 JSON string(N5)으로 직렬화된다")
    void commissionConfirmed_amountIsJsonString() throws Exception {
        Policy policy = activePolicy();
        List<CommissionSchedule> schedules = buildSchedules(policy);

        publisher.publishCommissionConfirmed(policy.getPolicyNumber(), schedules);

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        JsonNode node = new ObjectMapper().readTree(eventCaptor.getValue().getPayload());
        JsonNode firstSchedule = node.get("schedules").get(0);
        assertThat(firstSchedule.get("installmentAmount").isTextual())
                .as("installmentAmount 는 JSON string 이어야 한다(N5)").isTrue();
        assertThat(firstSchedule.get("firstYearTotal").isTextual())
                .as("firstYearTotal 는 JSON string 이어야 한다(N5)").isTrue();
    }

    @Test
    @DisplayName("수수료 확정 이벤트에서 aggregateType 은 'Insurance' 이다")
    void commissionConfirmed_aggregateTypeIsInsurance() {
        Policy policy = activePolicy();
        publisher.publishCommissionConfirmed(policy.getPolicyNumber(), buildSchedules(policy));

        verify(saveOutboxEventPort).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getAggregateType()).isEqualTo("Insurance");
    }
}
