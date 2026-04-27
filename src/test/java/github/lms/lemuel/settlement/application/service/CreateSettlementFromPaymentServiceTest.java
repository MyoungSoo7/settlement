package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadSellerSettlementCyclePort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.SellerTier;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementCycle;
import github.lms.lemuel.settlement.domain.SettlementStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateSettlementFromPaymentServiceTest {

    @Mock LoadSettlementPort loadSettlementPort;
    @Mock SaveSettlementPort saveSettlementPort;
    @Mock LoadSellerTierPort loadSellerTierPort;
    @Mock LoadSellerSettlementCyclePort loadSellerSettlementCyclePort;
    @InjectMocks CreateSettlementFromPaymentService service;

    @Test @DisplayName("정산 생성 성공 — NORMAL 판매자 (기본 3.5%)") void create() {
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.empty());
        when(loadSellerTierPort.findTierByPaymentId(1L)).thenReturn(Optional.of(SellerTier.NORMAL));
        when(loadSellerSettlementCyclePort.findCycleByPaymentId(1L)).thenReturn(Optional.of(SettlementCycle.DAILY));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Settlement result = service.createSettlementFromPayment(1L, 10L, new BigDecimal("50000"));
        assertThat(result.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
        assertThat(result.getPaymentAmount()).isEqualByComparingTo("50000");
        // NORMAL rate 3.5% → 50000 * 0.035 = 1750
        assertThat(result.getCommission()).isEqualByComparingTo("1750.00");
        assertThat(result.getNetAmount()).isEqualByComparingTo("48250.00");
        // DAILY — 정산일은 오늘 + 1
        assertThat(result.getSettlementDate()).isEqualTo(LocalDate.now().plusDays(1));
        verify(saveSettlementPort).save(any());
    }

    @Test @DisplayName("VIP 판매자는 2.5% 차등 수수료 적용") void create_vipTier() {
        when(loadSettlementPort.findByPaymentId(2L)).thenReturn(Optional.empty());
        when(loadSellerTierPort.findTierByPaymentId(2L)).thenReturn(Optional.of(SellerTier.VIP));
        when(loadSellerSettlementCyclePort.findCycleByPaymentId(2L)).thenReturn(Optional.of(SettlementCycle.DAILY));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Settlement result = service.createSettlementFromPayment(2L, 20L, new BigDecimal("100000"));
        // VIP rate 2.5% → 100000 * 0.025 = 2500
        assertThat(result.getCommission()).isEqualByComparingTo("2500.00");
        assertThat(result.getNetAmount()).isEqualByComparingTo("97500.00");
    }

    @Test @DisplayName("판매자 매핑 없으면 NORMAL + DAILY 로 fallback") void create_fallback() {
        when(loadSettlementPort.findByPaymentId(3L)).thenReturn(Optional.empty());
        when(loadSellerTierPort.findTierByPaymentId(3L)).thenReturn(Optional.empty());
        when(loadSellerSettlementCyclePort.findCycleByPaymentId(3L)).thenReturn(Optional.empty());
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Settlement result = service.createSettlementFromPayment(3L, 30L, new BigDecimal("10000"));
        // NORMAL fallback 3.5% → 10000 * 0.035 = 350
        assertThat(result.getCommission()).isEqualByComparingTo("350.00");
        // DAILY fallback → 오늘 + 1
        assertThat(result.getSettlementDate()).isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test @DisplayName("MONTHLY_LAST 판매자는 말일로 정산일 세팅") void create_monthlyCycle() {
        when(loadSettlementPort.findByPaymentId(4L)).thenReturn(Optional.empty());
        when(loadSellerTierPort.findTierByPaymentId(4L)).thenReturn(Optional.of(SellerTier.NORMAL));
        when(loadSellerSettlementCyclePort.findCycleByPaymentId(4L)).thenReturn(Optional.of(SettlementCycle.MONTHLY_LAST));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Settlement result = service.createSettlementFromPayment(4L, 40L, new BigDecimal("10000"));
        // 오늘이 속한 월의 말일 — 오늘의 달 마지막 날
        assertThat(result.getSettlementDate()).isEqualTo(
                LocalDate.now().with(java.time.temporal.TemporalAdjusters.lastDayOfMonth()));
    }

    @Test @DisplayName("중복 생성 시 기존 반환 (멱등성)") void create_idempotent() {
        Settlement existing = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(existing));
        Settlement result = service.createSettlementFromPayment(1L, 10L, new BigDecimal("50000"));
        assertThat(result).isSameAs(existing);
        verify(saveSettlementPort, never()).save(any());
    }
}
