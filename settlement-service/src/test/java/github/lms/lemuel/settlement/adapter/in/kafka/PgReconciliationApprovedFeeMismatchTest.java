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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FEE_MISMATCH 승인의 정산 영향 — <b>셀러에게서 회수하지 않는다</b>.
 *
 * <p>실입금 불일치는 PG 수수료 구조 오인이나 PG 파일 오류에서 난다. 셀러가 과다 정산을 받은 것이
 * 아니므로 셀러 정산을 깎으면 애먼 사람에게 손실을 전가하는 셈이다. 자동 조정 대상이 아니라는
 * 사실을 테스트로 못 박아, 나중에 "모든 승인 건은 조정한다" 식 변경이 조용히 들어오는 것을 막는다.
 */
@ExtendWith(MockitoExtension.class)
class PgReconciliationApprovedFeeMismatchTest {

    @Mock ApplyReconciliationAdjustmentUseCase useCase;
    @Mock ProcessedEventRepository processedEventRepository;
    @Mock Acknowledgment ack;

    SimpleMeterRegistry meterRegistry;
    PgReconciliationApprovedSettlementAdjustConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new PgReconciliationApprovedSettlementAdjustConsumer(
                useCase, processedEventRepository, new ObjectMapper(), meterRegistry, null);
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
    @DisplayName("FEE_MISMATCH 승인은 셀러 clawback 을 만들지 않는다 — 수수료 오차는 셀러 책임이 아니다")
    void feeMismatchNeverClawsBackFromSeller() {
        // 계산 실입금 9,700 / PG 신고 9,500 → difference -200 (자금 부족 방향)
        deliver("""
                {"discrepancyId":77,"type":"FEE_MISMATCH","paymentId":1,
                 "internalAmount":"9700","pgAmount":"9500","difference":"-200"}""");

        verify(useCase, never()).applyClawback(anyLong(), anyLong(), any());
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("스킵 사유가 fee_mismatch 로 기록된다 — unknown_type 으로 찍히면 미처리 버그로 오인된다")
    void skipReasonIsExplicitNotUnknown() {
        deliver("""
                {"discrepancyId":77,"type":"FEE_MISMATCH","paymentId":1,
                 "internalAmount":"9700","pgAmount":"9500","difference":"-200"}""");

        assertThat(skipped("fee_mismatch_pg_side")).isEqualTo(1.0);
        assertThat(skipped("unknown_type")).isZero();
    }

    @Test
    @DisplayName("과다 입금 방향(difference>0)도 동일하게 조정 없음")
    void excessDepositAlsoSkips() {
        deliver("""
                {"discrepancyId":78,"type":"FEE_MISMATCH","paymentId":1,
                 "internalAmount":"9700","pgAmount":"9800","difference":"100"}""");

        verify(useCase, never()).applyClawback(anyLong(), anyLong(), any());
        assertThat(skipped("fee_mismatch_pg_side")).isEqualTo(1.0);
    }
}
