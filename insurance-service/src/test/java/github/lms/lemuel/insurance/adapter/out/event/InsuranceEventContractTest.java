package github.lms.lemuel.insurance.adapter.out.event;

import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.insurance.domain.BancaRuleEvaluator.BancaRuleViolation;
import github.lms.lemuel.insurance.domain.CommissionClosing;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import github.lms.lemuel.insurance.domain.InsurerSector;
import github.lms.lemuel.insurance.domain.GeneralPayoutCalculator.PayoutQuote;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import github.lms.lemuel.insurance.domain.SalesChannel;
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
import java.time.Year;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 프로듀서 계약 테스트 (ADR 0024) — insurance 가 발행하는 9 종이 shared-common 의 계약 스키마를
 * 만족해야 한다.
 *
 * <p><b>왜 지금 붙이나</b>: 이 9 종은 토픽 카탈로그에는 등록돼 있는데 계약 스키마가 하나도 없었다
 * (전체 47 개 중 insurance 0 개). {@code topic-consumer-gate} 가 "발행 전용 — 계약 스키마 미편입"
 * 이라는 사유로 통과시켜 온 상태였다. 소비자가 아직 없어 당장 깨지는 것은 없지만, 소비자가 생기는
 * 순간 그때의 페이로드가 곧 계약이 된다 — 그 전에 못박아 두는 것이 ADR 0024 의 취지다.
 *
 * <p>두 축을 함께 고정한다.
 * <ol>
 *   <li><b>페이로드 ↔ 스키마</b> — 필수 필드 삭제·타입 변경이 빌드 시점에 깨진다.
 *   <li><b>라우팅 문자열</b> — 토픽은 {@code aggregateType + eventType} 에서 파생되므로
 *       (KafkaOutboxPublisher.resolveTopic) 오타 한 글자가 소비자 없는 토픽으로 조용히 샌다.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class InsuranceEventContractTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 8, 1);

    @Mock SaveOutboxEventPort saveOutboxEventPort;
    @Captor ArgumentCaptor<OutboxEvent> outboxCaptor;

    InsurancePolicyEventPublisherAdapter publisher;

    @BeforeEach
    void setUp() {
        publisher = new InsurancePolicyEventPublisherAdapter(saveOutboxEventPort, OutboxJson.mapper());
    }

    private static Policy policy() {
        return Policy.issue(EFFECTIVE, EFFECTIVE.plusYears(20),
                new BigDecimal("120000.00"), "FC-0007", SalesChannel.FC, null);
    }

    private static Policy bancaPolicy() {
        return Policy.issue(EFFECTIVE, EFFECTIVE.plusYears(20),
                new BigDecimal("120000.00"), "FC-0007", SalesChannel.BANCA, "004");
    }

    private static CommissionSchedule schedule(int installmentNo) {
        return CommissionSchedule.builder()
                .commissionId("C-2026-000123-" + String.format("%02d", installmentNo))
                .policyId("PID-1")
                .fcId("FC-0007")
                .recipientType("FC")
                .installmentNo(installmentNo)
                .installmentAmount(new BigDecimal("50000.00"))
                .firstYearTotal(new BigDecimal("600000.00"))
                .dueDate(EFFECTIVE.plusMonths(installmentNo))
                .build();
    }

    /** 발행된 outbox 페이로드를 스키마로 검증하고, 라우팅 문자열까지 함께 고정한다. */
    private void assertContract(String topic, String expectedEventType) {
        verify(saveOutboxEventPort).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();

        EventContractValidator.assertValid(topic, event.getPayload());
        assertThat(event.getAggregateType()).isEqualTo("Insurance");
        assertThat(event.getEventType()).isEqualTo(expectedEventType);
        assertThat(event.getAggregateId()).isNotBlank();
    }

    @Test
    @DisplayName("policy_issued — 계약을 만족하고 금액이 plain string 이다")
    void policyIssued() {
        publisher.publishPolicyIssued(policy(), new BigDecimal("100000000.00"));

        assertContract("lemuel.insurance.policy_issued", "InsurancePolicyIssued");
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"coverageAmount\":\"100000000.00\"");
    }

    /** 방카는 채널·은행코드가 실려야 25% 룰 집계가 성립한다 — 빠지면 소비 측이 재구성할 수 없다. */
    @Test
    @DisplayName("policy_issued(방카) — 채널과 제휴은행이 보존된다")
    void policyIssuedBanca() {
        publisher.publishPolicyIssued(bancaPolicy(), new BigDecimal("100000000.00"));

        assertContract("lemuel.insurance.policy_issued", "InsurancePolicyIssued");
        assertThat(outboxCaptor.getValue().getPayload())
                .contains("\"salesChannel\":\"BANCA\"")
                .contains("\"partnerBankCode\":\"004\"");
    }

    @Test
    @DisplayName("policy_status_changed — 이전 상태를 함께 실어 소비자가 전이를 재구성할 수 있다")
    void policyStatusChanged() {
        publisher.publishPolicyStatusChanged(policy(), PolicyStatus.ACTIVE);

        assertContract("lemuel.insurance.policy_status_changed", "InsurancePolicyStatusChanged");
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"previousStatus\":\"ACTIVE\"");
    }

    @Test
    @DisplayName("commission_confirmed — 스케줄 배열이 통째로 실린다(회차별로 쪼개지 않는다)")
    void commissionConfirmed() {
        publisher.publishCommissionConfirmed("P-2026-000123", List.of(schedule(1), schedule(2)));

        assertContract("lemuel.insurance.commission_confirmed", "InsuranceCommissionConfirmed");
        assertThat(outboxCaptor.getValue().getPayload())
                .contains("\"installmentAmount\":\"50000.00\"");
    }

    @Test
    @DisplayName("commission_paid — 회차 번호가 실려 멱등 판정 축이 된다")
    void commissionPaid() {
        CommissionSchedule paid = schedule(1);
        paid.markPaid(EFFECTIVE.plusMonths(1));

        publisher.publishCommissionPaid(policy(), paid);

        assertContract("lemuel.insurance.commission_paid", "InsuranceCommissionPaid");
        assertThat(outboxCaptor.getValue().getPayload()).contains("\"installmentNo\":1");
    }

    /** 환수액만 오면 "전액인가 일부인가"를 알 수 없다 — 기지급 총액을 함께 싣는다. */
    @Test
    @DisplayName("commission_clawback_triggered — 기지급 총액과 환수액이 함께 실린다")
    void commissionClawbackTriggered() {
        publisher.publishCommissionClawbackTriggered(
                policy(), new BigDecimal("300000.00"), new BigDecimal("150000.00"));

        assertContract("lemuel.insurance.commission_clawback_triggered",
                "InsuranceCommissionClawbackTriggered");
        assertThat(outboxCaptor.getValue().getPayload())
                .contains("\"paidTotal\":\"300000.00\"")
                .contains("\"clawbackAmount\":\"150000.00\"");
    }

    @Test
    @DisplayName("commission_monthly_closed — FC 별 월 마감")
    void commissionMonthlyClosed() {
        publisher.publishCommissionMonthlyClosed(CommissionClosing.close(
                "FC-0007", YearMonth.of(2026, 9), new BigDecimal("1200000.00"), 24L));

        assertContract("lemuel.insurance.commission_monthly_closed",
                "InsuranceCommissionMonthlyClosed");
    }

    /** 비율만 오면 "무엇 때문에 넘었나"를 다시 물어야 한다 — 분자·분모를 함께 싣는다. */
    @Test
    @DisplayName("banca_rule_violated — 판정을 재현할 분자·분모가 함께 실린다")
    void bancaRuleViolated() {
        publisher.publishBancaRuleViolated(Year.of(2026), new BancaRuleViolation(
                "004", InsurerSector.LIFE, "INS-11",
                new BigDecimal("0.2731"), new BigDecimal("273100000.00"),
                new BigDecimal("1000000000.00")));

        assertContract("lemuel.insurance.banca_rule_violated", "InsuranceBancaRuleViolated");
        assertThat(outboxCaptor.getValue().getPayload())
                .contains("\"insurerPremiumSum\":\"273100000.00\"")
                .contains("\"sectorPremiumTotal\":\"1000000000.00\"");
    }

    @Test
    @DisplayName("general_payout_requested — 산출 근거(경과월·적용률·납입합계)가 함께 실린다")
    void generalPayoutRequested() {
        GeneralPayout payout = GeneralPayout.request("PID-1", "P-2026-000123",
                GeneralPayoutType.SURRENDER_REFUND,
                new PayoutQuote(new BigDecimal("850000.00"), new BigDecimal("1200000.00"),
                        new BigDecimal("0.7083"), 10, 10),
                EFFECTIVE.plusMonths(10));

        publisher.publishGeneralPayoutRequested(policy(), payout);

        assertContract("lemuel.insurance.general_payout_requested",
                "InsuranceGeneralPayoutRequested");
        assertThat(outboxCaptor.getValue().getPayload())
                .contains("\"appliedRate\":\"0.7083\"")
                .contains("\"elapsedMonths\":10");
    }

    @Test
    @DisplayName("general_payout_paid — 지급 완료. payoutId 가 멱등 축이다")
    void generalPayoutPaid() {
        GeneralPayout payout = GeneralPayout.request("PID-1", "P-2026-000123",
                GeneralPayoutType.SURRENDER_REFUND,
                new PayoutQuote(new BigDecimal("850000.00"), new BigDecimal("1200000.00"),
                        new BigDecimal("0.7083"), 10, 10),
                EFFECTIVE.plusMonths(10));
        payout.markPaid(EFFECTIVE.plusMonths(10).plusDays(5));

        publisher.publishGeneralPayoutPaid(payout);

        assertContract("lemuel.insurance.general_payout_paid", "InsuranceGeneralPayoutPaid");
    }

    /**
     * 정본 샘플이 스키마를 실제로 통과하는지 — 스키마만 고치고 샘플을 안 고치는 것이
     * 이 계약 체계의 대표적 함정이다(event-contract-change 스킬). 샘플은 컨슈머 계약 테스트의
     * 입력이 되므로, 통과하지 않는 샘플은 그 자체로 잘못된 정본이다.
     */
    @Test
    @DisplayName("정본 샘플 9종이 각자의 스키마를 통과한다")
    void canonicalSamplesSatisfyTheirSchemas() {
        List.of("lemuel.insurance.policy_issued",
                "lemuel.insurance.policy_status_changed",
                "lemuel.insurance.commission_confirmed",
                "lemuel.insurance.commission_paid",
                "lemuel.insurance.commission_clawback_triggered",
                "lemuel.insurance.commission_monthly_closed",
                "lemuel.insurance.banca_rule_violated",
                "lemuel.insurance.general_payout_requested",
                "lemuel.insurance.general_payout_paid")
                .forEach(topic -> EventContractValidator.assertValid(
                        topic, EventContractValidator.canonicalSample(topic)));
    }
}
