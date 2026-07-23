package github.lms.lemuel.recon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨슈머측 REST 계약 테스트 — order {@code /internal/recon} 응답의 정본 샘플
 * (shared-common testFixtures {@code contracts/internal-rest/recon/})을 {@link OrderReconClient}
 * 의 record 로 역직렬화할 수 있어야 한다.
 *
 * <p>ADR 0024(이벤트 계약-as-code)의 REST 경계 확장 — 이 REST 계약은 공유 모듈이 없어 양측
 * record ↔ JSON 매칭에만 의존해 왔다(Seed recon KI-1). 정본 샘플을 단일 출처로 두고
 * 프로듀서(order 측 동명 테스트)·컨슈머(이 테스트)가 같은 샘플을 각자의 record 로 읽어,
 * 필드 개명·제거 드리프트를 빌드 시점에 잡는다.
 * (FAIL_ON_UNKNOWN_PROPERTIES 기본값이 켜져 있어 샘플에 있는 필드가 record 에 없으면 실패한다.)
 */
@DisplayName("/internal/recon REST 계약 — 컨슈머(OrderReconClient record)측")
class InternalReconRestContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private static <T> T read(String sample, Class<T> type) {
        try (InputStream in = InternalReconRestContractTest.class.getResourceAsStream(
                "/contracts/internal-rest/recon/" + sample)) {
            assertThat(in).as("정본 샘플 %s 존재", sample).isNotNull();
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new AssertionError("계약 샘플 역직렬화 실패: " + sample, e);
        }
    }

    private static <T> T read(String sample, TypeReference<T> type) {
        try (InputStream in = InternalReconRestContractTest.class.getResourceAsStream(
                "/contracts/internal-rest/recon/" + sample)) {
            assertThat(in).as("정본 샘플 %s 존재", sample).isNotNull();
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new AssertionError("계약 샘플 역직렬화 실패: " + sample, e);
        }
    }

    @Test
    @DisplayName("daily-totals 샘플 → DailyTotals")
    void dailyTotals() {
        var v = read("daily-totals.sample.json", OrderReconClient.DailyTotals.class);
        assertThat(v.capturedPayments()).isEqualByComparingTo("1250000.00");
        assertThat(v.completedRefunds()).isEqualByComparingTo("50000.00");
        assertThat(v.refundedAgainstCaptures()).isEqualByComparingTo("30000.00");
    }

    @Test
    @DisplayName("period-totals 샘플 → PeriodTotals")
    void periodTotals() {
        var v = read("period-totals.sample.json", OrderReconClient.PeriodTotals.class);
        assertThat(v.capturedPayments()).isEqualByComparingTo("5000000.00");
        assertThat(v.completedRefunds()).isEqualByComparingTo("120000.00");
        assertThat(v.paymentCapturedPublishedCount()).isEqualTo(42L);
    }

    @Test
    @DisplayName("daily-counts 샘플 → DailyCounts (INV-9 건수 축)")
    void dailyCounts() {
        var v = read("daily-counts.sample.json", OrderReconClient.DailyCounts.class);
        assertThat(v.capturedCount()).isEqualTo(37L);
        assertThat(v.completedRefundsCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("refunds-completed 샘플 → CompletedRefundRow[] (INV-8)")
    void refundsCompleted() {
        List<OrderReconClient.CompletedRefundRow> rows =
                read("refunds-completed.sample.json", new TypeReference<>() { });
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).refundId()).isEqualTo(9001L);
        assertThat(rows.get(0).paymentId()).isEqualTo(5001L);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("15000.00");
        assertThat(rows.get(0).completedDate()).isEqualTo(LocalDate.of(2026, 7, 18));
    }

    @Test
    @DisplayName("refunds-completed-sum 샘플 → RefundIdsRequest/AmountResponse")
    void refundsCompletedSum() {
        var req = read("refunds-completed-sum-request.sample.json", OrderReconClient.RefundIdsRequest.class);
        assertThat(req.refundIds()).containsExactly(9001L, 9002L);
        var res = read("refunds-completed-sum-response.sample.json", OrderReconClient.AmountResponse.class);
        assertThat(res.amount()).isEqualByComparingTo("27000.00");
    }

    @Test
    @DisplayName("captured-payments 샘플 → ReconPaymentRow[] (PG 대사)")
    void capturedPayments() {
        List<OrderReconClient.ReconPaymentRow> rows =
                read("captured-payments.sample.json", new TypeReference<>() { });
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).paymentId()).isEqualTo(5001L);
        assertThat(rows.get(0).pgTransactionId()).isEqualTo("PG-20260718-0001");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("45000.00");
        assertThat(rows.get(0).refundedAmount()).isEqualByComparingTo("0.00");
        assertThat(rows.get(0).capturedDate()).isEqualTo(LocalDate.of(2026, 7, 18));
    }

    @Test
    @DisplayName("payment-keys-checksum 샘플 → PaymentKeyChecksum (INV-12 1차 스크리닝)")
    void paymentKeysChecksum() {
        var v = read("payment-keys-checksum.sample.json", OrderReconClient.PaymentKeyChecksum.class);
        assertThat(v.count()).isEqualTo(37L);
        assertThat(v.amountSum()).isEqualByComparingTo("1250000.00");
        assertThat(v.idChecksum()).isEqualTo("9e107d9d372bb6826bd81d3542a419d6");
    }

    @Test
    @DisplayName("payment-keys 샘플 → PaymentKeyRow[] (INV-12 diff)")
    void paymentKeys() {
        List<OrderReconClient.PaymentKeyRow> rows =
                read("payment-keys.sample.json", new TypeReference<>() { });
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).paymentId()).isEqualTo(5001L);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("45000.00");
    }
}
