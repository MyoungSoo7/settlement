package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerSettlementCyclePort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
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
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock PublishSettlementDomainEventPort publishSettlementDomainEventPort;
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

    @Test @DisplayName("이벤트 동봉 메타(tier/cycle/sellerId) 사용 시 order DB fallback 포트를 호출하지 않는다 (ADR 0020 Phase 1)")
    void create_usesEventCarriedMeta_noFallback() {
        when(loadSettlementPort.findByPaymentId(7L)).thenReturn(Optional.empty());
        // 발행부가 settlementId(primitive long)를 사용하므로 영속 id 를 부여해 언박싱 NPE 회피
        when(saveSettlementPort.save(any())).thenAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.assignId(700L);
            return s;
        });

        Settlement result = service.createSettlementFromPayment(
                7L, 70L, new BigDecimal("100000"), 99L, "VIP", "DAILY");

        // 이벤트값으로 VIP(2.5%) + DAILY(T+1) 적용
        assertThat(result.getCommission()).isEqualByComparingTo("2500.00");
        assertThat(result.getSettlementDate()).isEqualTo(LocalDate.now().plusDays(1));
        // ★ 핵심: order DB 조인 포트는 한 번도 호출되지 않는다 (opslab-free 생성)
        verify(loadSellerTierPort, never()).findTierByPaymentId(any());
        verify(loadSellerSettlementCyclePort, never()).findCycleByPaymentId(any());
        verify(loadSellerIdPort, never()).findSellerIdByPaymentId(any());
        // 이벤트 sellerId 로 SettlementCreated 발행 (port 는 primitive long 시그니처 → anyLong)
        verify(publishSettlementDomainEventPort).publishSettlementCreated(anyLong(), eq(99L), any(), any());
    }

    @Test @DisplayName("판매자 매핑 없으면 NORMAL + T+7 default cycle 로 fallback") void create_fallback() {
        when(loadSettlementPort.findByPaymentId(3L)).thenReturn(Optional.empty());
        when(loadSellerTierPort.findTierByPaymentId(3L)).thenReturn(Optional.empty());
        when(loadSellerSettlementCyclePort.findCycleByPaymentId(3L)).thenReturn(Optional.empty());
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Settlement result = service.createSettlementFromPayment(3L, 30L, new BigDecimal("10000"));
        // NORMAL fallback 3.5% → 10000 * 0.035 = 350
        assertThat(result.getCommission()).isEqualByComparingTo("350.00");
        // NORMAL.defaultCycle() = T_PLUS_7 → 오늘로부터 7 영업일 후
        assertThat(result.getSettlementDate())
                .isEqualTo(SettlementCycle.T_PLUS_7.resolveSettlementDate(LocalDate.now()));
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

    @Test @DisplayName("판매자 해석되면 SettlementCreated 를 netAmount·정산일로 발행") void create_publishesSettlementCreated() {
        when(loadSettlementPort.findByPaymentId(5L)).thenReturn(Optional.empty());
        when(loadSellerTierPort.findTierByPaymentId(5L)).thenReturn(Optional.of(SellerTier.NORMAL));
        when(loadSellerSettlementCyclePort.findCycleByPaymentId(5L)).thenReturn(Optional.of(SettlementCycle.DAILY));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            s.assignId(500L); // DB 가 PK 를 부여하는 것을 흉내 (발행은 savedSettlement.getId() 사용)
            return s;
        });
        when(loadSellerIdPort.findSellerIdByPaymentId(5L)).thenReturn(Optional.of(77L));

        service.createSettlementFromPayment(5L, 50L, new BigDecimal("10000"));

        // netAmount = 10000 - 3.5% = 9650, 정산일 = 오늘+1(DAILY)
        verify(publishSettlementDomainEventPort).publishSettlementCreated(
                eq(500L), eq(77L),
                argThat(a -> a.compareTo(new BigDecimal("9650.00")) == 0),
                eq(LocalDate.now().plusDays(1)));
    }

    @Test @DisplayName("판매자 미해석이면 발행 생략") void create_skipPublishWhenNoSeller() {
        when(loadSettlementPort.findByPaymentId(6L)).thenReturn(Optional.empty());
        when(loadSellerTierPort.findTierByPaymentId(6L)).thenReturn(Optional.of(SellerTier.NORMAL));
        when(loadSellerSettlementCyclePort.findCycleByPaymentId(6L)).thenReturn(Optional.of(SettlementCycle.DAILY));
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(loadSellerIdPort.findSellerIdByPaymentId(6L)).thenReturn(Optional.empty());

        service.createSettlementFromPayment(6L, 60L, new BigDecimal("10000"));

        verifyNoInteractions(publishSettlementDomainEventPort);
    }

    @Test @DisplayName("중복 생성 시 기존 반환 (멱등성)") void create_idempotent() {
        Settlement existing = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(existing));
        Settlement result = service.createSettlementFromPayment(1L, 10L, new BigDecimal("50000"));
        assertThat(result).isSameAs(existing);
        verify(saveSettlementPort, never()).save(any());
    }
}
