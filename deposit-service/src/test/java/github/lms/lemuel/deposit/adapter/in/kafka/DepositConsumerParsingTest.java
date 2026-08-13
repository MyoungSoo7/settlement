package github.lms.lemuel.deposit.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.deposit.application.port.in.CreditDepositUseCase;
import github.lms.lemuel.deposit.application.port.in.DebitDepositUseCase;
import github.lms.lemuel.deposit.domain.exception.InsufficientDepositException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 컨슈머 계약 테스트 (ADR 0024) — 각 토픽의 <b>정본 샘플</b>(shared-common contracts 픽스처)을 실제
 * 컨슈머에 통과시켜 이벤트→예치금 연산 매핑이 계약 값 그대로 위임되는지 검증한다.
 * 프로듀서가 계약 필드를 바꾸면 이 테스트가 빌드 시점에 깨진다.
 *
 * <p>인라인 JSON 을 쓰는 경계 케이스는 {@code EventContractValidator.assertValid} 로 스키마 유효성을
 * 함께 고정한다 — 그러지 않으면 "계약에 없는 페이로드로만 통과하는 테스트"가 되어 무의미해진다.
 */
@ExtendWith(MockitoExtension.class)
class DepositConsumerParsingTest {

    @Mock CreditDepositUseCase creditDepositUseCase;
    @Mock DebitDepositUseCase debitDepositUseCase;
    @Mock ProcessedEventRepository processedEventRepository;
    final ObjectMapper objectMapper = new ObjectMapper();

    private static ConsumerRecord<String, String> recordOf(String topic, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, null, json);
        record.headers().add("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static ConsumerRecord<String, String> canonicalRecordOf(String topic) {
        return recordOf(topic, EventContractValidator.canonicalSample(topic));
    }

    /** Mockito 의 정적 {@code eq} 와 겹치지 않도록 이름을 분리한다. */
    private static boolean isAmount(BigDecimal actual, String expected) {
        return actual.compareTo(new BigDecimal(expected)) == 0;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // settlement.confirmed → credit
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("settlement.confirmed 정본 샘플 → credit(sellerId, amount, refId=settlementId, refType=SETTLEMENT)")
    void settlementConfirmedCredits() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        var consumer = new SettlementConfirmedConsumer(
                processedEventRepository, objectMapper, creditDepositUseCase);

        consumer.onSettlementConfirmed(canonicalRecordOf("lemuel.settlement.confirmed"), mock(Acknowledgment.class));

        verify(creditDepositUseCase).credit(
                eq(777L),
                argThat(a -> isAmount(a, "43425")),
                eq("9001"),                       // refId = settlementId — 원장 L3 멱등 자연키
                eq(SettlementConfirmedConsumer.REFERENCE_TYPE));
    }

    @Test
    @DisplayName("settlement.confirmed 금액이 JSON number(구 wire 표현) 여도 파싱된다 — 프로듀서 선행 배포 안전성")
    void settlementConfirmedNumericAmount_stillParses() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        var consumer = new SettlementConfirmedConsumer(
                processedEventRepository, objectMapper, creditDepositUseCase);
        // 정본 계약은 문자열 금액이지만 소비측은 asText() 파싱이라 number 도 받아야 한다.
        // 이 관용성이 깨지면 프로듀서 선행 배포 중 미전환 이벤트가 통째로 DLT 로 샌다.
        String payload = "{\"settlementId\":9001,\"sellerId\":777,\"amount\":43425}";

        consumer.onSettlementConfirmed(recordOf("lemuel.settlement.confirmed", payload), mock(Acknowledgment.class));

        verify(creditDepositUseCase).credit(eq(777L), argThat(a -> isAmount(a, "43425")), eq("9001"), any());
    }

    @Test
    @DisplayName("settlement.confirmed 필수 필드(amount) 누락 → IllegalArgumentException(비재시도 DLT) + 입금 미실행")
    void settlementConfirmedMissingAmount_throwsWithoutCrediting() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        var consumer = new SettlementConfirmedConsumer(
                processedEventRepository, objectMapper, creditDepositUseCase);

        assertThatThrownBy(() -> consumer.onSettlementConfirmed(
                recordOf("lemuel.settlement.confirmed", "{\"settlementId\":9001,\"sellerId\":777}"),
                mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");

        verify(creditDepositUseCase, never()).credit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 처리된 eventId → 골격이 스킵하고 입금하지 않는다 (L2 멱등)")
    void alreadyProcessedEvent_isSkipped() {
        when(processedEventRepository.existsById(any())).thenReturn(true);
        var consumer = new SettlementConfirmedConsumer(
                processedEventRepository, objectMapper, creditDepositUseCase);

        consumer.onSettlementConfirmed(canonicalRecordOf("lemuel.settlement.confirmed"), mock(Acknowledgment.class));

        verify(creditDepositUseCase, never()).credit(any(), any(), any(), any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // payout.completed → debit
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("payout.completed 정본 샘플 → debit(sellerId, amount, refId=payoutId, refType=PAYOUT)")
    void payoutCompletedDebits() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        var consumer = new PayoutCompletedConsumer(
                processedEventRepository, objectMapper, debitDepositUseCase);

        consumer.onPayoutCompleted(canonicalRecordOf("lemuel.payout.completed"), mock(Acknowledgment.class));

        verify(debitDepositUseCase).debit(
                eq(777L),
                argThat(a -> isAmount(a, "43425")),
                eq("7001"),                       // refId = payoutId (settlementId 가 아니다 — nullable 이라 멱등키가 못 된다)
                eq(PayoutCompletedConsumer.REFERENCE_TYPE));
    }

    @Test
    @DisplayName("payout.completed 잔고 부족 → 예외를 삼키지 않고 전파한다(DLT 로 남겨 운영자가 본다)")
    void payoutCompletedInsufficientDeposit_propagates() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        var consumer = new PayoutCompletedConsumer(
                processedEventRepository, objectMapper, debitDepositUseCase);
        doThrow(new InsufficientDepositException("잔고 부족", "777", "debit"))
                .when(debitDepositUseCase).debit(any(), any(), any(), any());

        // "지급은 나갔는데 차감할 예치금이 없다"는 정상 결과가 아니라 원장 불일치 신호다.
        // 조용히 ack 하면 그 불일치가 흔적 없이 사라진다 — 상계 부족분(shortfall) 과 달리 설계된 결과가 아니다.
        assertThatThrownBy(() -> consumer.onPayoutCompleted(
                canonicalRecordOf("lemuel.payout.completed"), mock(Acknowledgment.class)))
                .isInstanceOf(InsufficientDepositException.class);
    }

    @Test
    @DisplayName("payout.completed settlementId=null 이어도 처리된다 — 계약상 nullable(정산 없는 지급)")
    void payoutCompletedNullSettlementId_stillDebits() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        var consumer = new PayoutCompletedConsumer(
                processedEventRepository, objectMapper, debitDepositUseCase);
        String payload = "{\"payoutId\":7001,\"settlementId\":null,\"sellerId\":777,\"amount\":\"43425\"}";
        EventContractValidator.assertValid("lemuel.payout.completed", payload);

        consumer.onPayoutCompleted(recordOf("lemuel.payout.completed", payload), mock(Acknowledgment.class));

        verify(debitDepositUseCase).debit(eq(777L), argThat(a -> isAmount(a, "43425")), eq("7001"), any());
    }
}
