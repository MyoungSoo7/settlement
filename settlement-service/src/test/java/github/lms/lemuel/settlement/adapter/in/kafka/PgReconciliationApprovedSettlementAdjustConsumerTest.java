package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.application.port.in.ApplyReconciliationAdjustmentUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link PgReconciliationApprovedSettlementAdjustConsumer} 타입별 clawback 판정 검증.
 *
 * <p>5개 분기: AMOUNT_MISMATCH diff&lt;0 적용 / AMOUNT_MISMATCH diff&gt;0 스킵 / MISSING_PG 적용 /
 * MISSING_INTERNAL 스킵 / DUPLICATE 스킵. 스킵은 skipped 메트릭 증가 + 정상 반환(ack)해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class PgReconciliationApprovedSettlementAdjustConsumerTest {

    @Mock ApplyReconciliationAdjustmentUseCase useCase;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock Acknowledgment ack;

    SimpleMeterRegistry meterRegistry;
    PgReconciliationApprovedSettlementAdjustConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new PgReconciliationApprovedSettlementAdjustConsumer(
                useCase, processedEventRepository, new ObjectMapper(), meterRegistry);
        when(processedEventRepository.existsById(any())).thenReturn(false);
    }

    private void deliver(String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "lemuel.pgreconciliation.discrepancy_approved", 0, 0L, "key", json);
        record.headers().add(new RecordHeader("event_id",
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        consumer.onDiscrepancyApproved(record, ack);
    }

    private double skipped(String reason) {
        return meterRegistry.counter("pg.reconciliation.adjustments.skipped", "reason", reason).count();
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH diff<0 (셀러 과다 정산): clawback = |difference|")
    void amountMismatch_negativeDiff_applies() {
        // internal 10,000 / pg 9,000 → difference -1,000 → clawback 1,000
        deliver("""
                {"discrepancyId":55,"type":"AMOUNT_MISMATCH","paymentId":1,
                 "internalAmount":"10000","pgAmount":"9000","difference":"-1000"}""");

        verify(useCase).applyClawback(eq(1L), eq(55L), argThatEq("1000"));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH diff>0 (과소 정산): 조정 없음 + skipped")
    void amountMismatch_positiveDiff_skips() {
        deliver("""
                {"discrepancyId":55,"type":"AMOUNT_MISMATCH","paymentId":1,
                 "internalAmount":"9000","pgAmount":"10000","difference":"1000"}""");

        verify(useCase, never()).applyClawback(any(), any(), any());
        assertThat(skipped("amount_mismatch_under_settlement")).isEqualTo(1.0);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("MISSING_PG (PG 미송금): clawback = internalAmount")
    void missingPg_applies() {
        deliver("""
                {"discrepancyId":55,"type":"MISSING_PG","paymentId":1,
                 "internalAmount":"10000","difference":"-10000"}""");

        verify(useCase).applyClawback(eq(1L), eq(55L), argThatEq("10000"));
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("MISSING_INTERNAL (paymentId 없음): 조정 없음 + skipped")
    void missingInternal_skips() {
        deliver("""
                {"discrepancyId":55,"type":"MISSING_INTERNAL","pgAmount":"10000","difference":"10000"}""");

        verify(useCase, never()).applyClawback(any(), any(), any());
        assertThat(skipped("missing_internal_no_settlement")).isEqualTo(1.0);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("DUPLICATE (PG 이중청구): 조정 없음 + skipped")
    void duplicate_skips() {
        deliver("""
                {"discrepancyId":55,"type":"DUPLICATE","paymentId":1,
                 "internalAmount":"10000","pgAmount":"20000","difference":"10000"}""");

        verify(useCase, never()).applyClawback(any(), any(), any());
        assertThat(skipped("duplicate_pg_side")).isEqualTo(1.0);
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("ROUNDING_DIFF: 조정 없음 + skipped")
    void roundingDiff_skips() {
        deliver("""
                {"discrepancyId":55,"type":"ROUNDING_DIFF","paymentId":1,
                 "internalAmount":"10000","pgAmount":"10000.4","difference":"0.4"}""");

        verify(useCase, never()).applyClawback(any(), any(), any());
        assertThat(skipped("rounding_diff")).isEqualTo(1.0);
        verify(ack).acknowledge();
    }

    private static BigDecimal argThatEq(String expected) {
        return org.mockito.ArgumentMatchers.argThat(
                v -> v != null && v.compareTo(new BigDecimal(expected)) == 0);
    }
}
