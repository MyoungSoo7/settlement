package github.lms.lemuel.integrity.application.service;

import github.lms.lemuel.integrity.application.port.out.IntegrityQueryPort;
import github.lms.lemuel.integrity.application.port.out.KeyChecksum;
import github.lms.lemuel.integrity.application.port.out.LoadOrderPaymentKeysPort;
import github.lms.lemuel.integrity.application.port.out.PaymentKey;
import github.lms.lemuel.integrity.domain.ProjectionDiffReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INV-12 프로젝션 행 diff 하이브리드 로직 검증 (Phase C) — 포트를 스텁해 판정 로직만 본다.
 * 완료 기준: "프로젝션 뷰에서 행 1건이 빠졌을 때 누락 id 를 특정해 보고".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectionReconciliationService — INV-12 프로젝션 행 diff")
class ProjectionReconciliationServiceTest {

    @Mock LoadOrderPaymentKeysPort orderKeysPort;
    @Mock IntegrityQueryPort queryPort;

    private final LocalDate date = LocalDate.of(2026, 6, 17);

    private ProjectionReconciliationService service() {
        return new ProjectionReconciliationService(orderKeysPort, queryPort);
    }

    @Test
    @DisplayName("체크섬 일치 — 행 diff 없이 통과하고 키 목록을 당기지 않는다")
    void checksumMatched_skipsKeyFetch() {
        KeyChecksum same = new KeyChecksum(3L, new BigDecimal("3000.00"), "abc");
        when(orderKeysPort.checksum(date)).thenReturn(same);
        when(queryPort.projectionPaymentChecksum(date)).thenReturn(same);

        ProjectionDiffReport report = service().reconcileProjection(date, "payment", null);

        assertThat(report.ok()).isTrue();
        assertThat(report.checksumMatched()).isTrue();
        assertThat(report.orderCount()).isEqualTo(3L);
        verify(orderKeysPort, never()).keys(eq(date), anyLong(), anyInt());
        verify(queryPort, never()).projectionPaymentKeys(eq(date), anyLong(), anyInt());
    }

    @Test
    @DisplayName("행 1건 누락 — order 엔 있고 프로젝션엔 없는 payment_id 를 특정한다")
    void missingRow_isPinpointed() {
        // 체크섬 불일치(건수/체크섬 다름) → 키 diff 진입
        when(orderKeysPort.checksum(date)).thenReturn(new KeyChecksum(3L, new BigDecimal("3000.00"), "order-hash"));
        when(queryPort.projectionPaymentChecksum(date)).thenReturn(new KeyChecksum(2L, new BigDecimal("2000.00"), "proj-hash"));
        when(orderKeysPort.keys(eq(date), anyLong(), anyInt())).thenReturn(List.of(
                new PaymentKey(1L, new BigDecimal("1000.00")),
                new PaymentKey(2L, new BigDecimal("1000.00")),
                new PaymentKey(3L, new BigDecimal("1000.00"))));
        when(queryPort.projectionPaymentKeys(eq(date), anyLong(), anyInt())).thenReturn(List.of(
                new PaymentKey(1L, new BigDecimal("1000.00")),
                new PaymentKey(2L, new BigDecimal("1000.00"))));

        ProjectionDiffReport report = service().reconcileProjection(date, "payment", null);

        assertThat(report.ok()).isFalse();
        assertThat(report.checksumMatched()).isFalse();
        assertThat(report.missingInProjectionCount()).isEqualTo(1L);
        assertThat(report.missingInProjectionIds()).containsExactly(3L);
        assertThat(report.missingInProjectionAmount()).isEqualByComparingTo("1000.00");
        assertThat(report.orphanInProjectionCount()).isZero();
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("INV-12"));
    }

    @Test
    @DisplayName("고아 행 — 프로젝션엔 있고 order 엔 없는 payment_id 를 특정한다")
    void orphanRow_isPinpointed() {
        when(orderKeysPort.checksum(date)).thenReturn(new KeyChecksum(1L, new BigDecimal("1000.00"), "o"));
        when(queryPort.projectionPaymentChecksum(date)).thenReturn(new KeyChecksum(2L, new BigDecimal("2000.00"), "p"));
        when(orderKeysPort.keys(eq(date), anyLong(), anyInt())).thenReturn(List.of(
                new PaymentKey(1L, new BigDecimal("1000.00"))));
        when(queryPort.projectionPaymentKeys(eq(date), anyLong(), anyInt())).thenReturn(List.of(
                new PaymentKey(1L, new BigDecimal("1000.00")),
                new PaymentKey(9L, new BigDecimal("1000.00"))));

        ProjectionDiffReport report = service().reconcileProjection(date, "payment", null);

        assertThat(report.ok()).isFalse();
        assertThat(report.orphanInProjectionCount()).isEqualTo(1L);
        assertThat(report.orphanInProjectionIds()).containsExactly(9L);
        assertThat(report.missingInProjectionCount()).isZero();
    }

    @Test
    @DisplayName("금액 불일치 — 같은 id 의 order/프로젝션 금액 차이를 병기해 보고한다")
    void amountMismatch_reportsBothValues() {
        when(orderKeysPort.checksum(date)).thenReturn(new KeyChecksum(1L, new BigDecimal("1000.00"), "o"));
        when(queryPort.projectionPaymentChecksum(date)).thenReturn(new KeyChecksum(1L, new BigDecimal("900.00"), "p"));
        when(orderKeysPort.keys(eq(date), anyLong(), anyInt())).thenReturn(List.of(
                new PaymentKey(1L, new BigDecimal("1000.00"))));
        when(queryPort.projectionPaymentKeys(eq(date), anyLong(), anyInt())).thenReturn(List.of(
                new PaymentKey(1L, new BigDecimal("900.00"))));

        ProjectionDiffReport report = service().reconcileProjection(date, "payment", null);

        assertThat(report.ok()).isFalse();
        assertThat(report.amountMismatchCount()).isEqualTo(1L);
        assertThat(report.amountMismatches()).hasSize(1);
        assertThat(report.amountMismatches().get(0).paymentId()).isEqualTo(1L);
        assertThat(report.amountMismatches().get(0).orderAmount()).isEqualByComparingTo("1000.00");
        assertThat(report.amountMismatches().get(0).projectionAmount()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("잘못된 entity·null date 는 IllegalArgumentException")
    void invalidArguments() {
        assertThatThrownBy(() -> service().reconcileProjection(date, "user", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().reconcileProjection(null, "payment", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
