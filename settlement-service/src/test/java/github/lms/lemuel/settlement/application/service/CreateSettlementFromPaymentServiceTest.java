package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.settlement.application.port.out.BackfillChargebackSettlementLinkPort;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    @Mock BackfillChargebackSettlementLinkPort backfillChargebackPort;
    @Mock AuditLogger auditLogger;
    CreateSettlementFromPaymentService service;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @BeforeEach
    void setUp() {
        // 프로덕션 TimeConfig 와 동일한 KST 시계 — 폴백(capturedAt 부재) 경로가 KST '오늘'을 쓰게 한다.
        service = serviceWith(Clock.system(KST));
    }

    private CreateSettlementFromPaymentService serviceWith(Clock clock) {
        return new CreateSettlementFromPaymentService(
                loadSettlementPort, saveSettlementPort, loadSellerTierPort,
                loadSellerSettlementCyclePort, loadSellerIdPort, publishSettlementDomainEventPort,
                backfillChargebackPort, auditLogger, clock);
    }

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
        // 이벤트드리븐 정산 생성 감사 — 정산금 발생 지점을 actor=system 으로 남긴다.
        verify(auditLogger).record(eq(AuditAction.SETTLEMENT_CREATED), eq("Settlement"), any(),
                contains("\"paymentId\":1"));
        // 사전분쟁 백필 훅 — 정산 생성 트랜잭션 안에서 미연결 chargeback 을 연결한다.
        verify(backfillChargebackPort).backfillChargebacks(eq(1L), any());
    }

    @Test @DisplayName("멱등 반환(기존 정산 존재) 경로는 사전분쟁 백필을 다시 호출하지 않는다")
    void idempotentReturn_skipsChargebackBackfill() {
        Settlement existing = Settlement.createFromPayment(
                1L, 10L, new BigDecimal("50000"), LocalDate.now().plusDays(1), SellerTier.NORMAL.rate());
        when(loadSettlementPort.findByPaymentId(1L)).thenReturn(Optional.of(existing));

        Settlement result = service.createSettlementFromPayment(1L, 10L, new BigDecimal("50000"));

        assertThat(result).isSameAs(existing);
        verify(backfillChargebackPort, never()).backfillChargebacks(any(), any());
        verify(saveSettlementPort, never()).save(any());
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
        verify(publishSettlementDomainEventPort).publishSettlementCreated(anyLong(), eq(99L), any(), any(), any());
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

        // netAmount = 10000 - 3.5% = 9650, 정산일 = 오늘+1(DAILY), holdback 30% = 2895.00
        verify(publishSettlementDomainEventPort).publishSettlementCreated(
                eq(500L), eq(77L),
                argThat(a -> a.compareTo(new BigDecimal("9650.00")) == 0),
                eq(LocalDate.now().plusDays(1)),
                argThat(h -> h.compareTo(new BigDecimal("2895.00")) == 0));
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
        // 멱등 반환(기존 정산)은 생성 사건이 아니므로 감사 기록도 남기지 않는다.
        verify(auditLogger, never()).record(any(), any(), any(), any());
        verify(saveSettlementPort, never()).save(any());
    }

    // ── UTC-JVM 경계·결제일 기준 정산일 계산 (고정 Clock) ─────────────────────────

    @Test @DisplayName("정산일은 소비 시각(now)이 아니라 결제 시각(capturedAt)의 날짜 기준으로 계산된다")
    void settlementDate_basedOnCapturedAt_notNow() {
        // 소비 시각은 07-16 이지만 결제는 07-10 — 지연/백필/재처리와 무관하게 정산일은 07-10 기준이어야 한다.
        Clock now0716 = Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), KST);
        when(loadSettlementPort.findByPaymentId(11L)).thenReturn(Optional.empty());
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Settlement result = serviceWith(now0716).createSettlementFromPayment(
                11L, 110L, new BigDecimal("50000"), null, "VIP", "DAILY",
                LocalDateTime.of(2026, 7, 10, 10, 0));

        assertThat(result.getSettlementDate())
                .isEqualTo(SettlementCycle.DAILY.resolveSettlementDate(LocalDate.of(2026, 7, 10)))
                .isNotEqualTo(SettlementCycle.DAILY.resolveSettlementDate(LocalDate.of(2026, 7, 16)));
    }

    @Test @DisplayName("capturedAt 부재 시 폴백은 UTC 가 아니라 KST 자정 기준일을 쓴다 (off-by-one 방지)")
    void fallback_usesKstMidnight_notUtc() {
        // 이 순간 UTC 날짜는 07-15, KST 날짜는 07-16. 폴백 정산 기준일은 KST(07-16) 여야 한다.
        Clock kstJustAfterMidnight = Clock.fixed(Instant.parse("2026-07-15T15:30:00Z"), KST);
        when(loadSettlementPort.findByPaymentId(12L)).thenReturn(Optional.empty());
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Settlement result = serviceWith(kstJustAfterMidnight).createSettlementFromPayment(
                12L, 120L, new BigDecimal("50000"), null, "VIP", "DAILY", null);

        assertThat(result.getSettlementDate())
                .isEqualTo(SettlementCycle.DAILY.resolveSettlementDate(LocalDate.of(2026, 7, 16)));
    }

    @Test @DisplayName("월말 정산(MONTHLY_LAST)은 결제월의 말일로 계산된다")
    void monthEnd_resolvesToLastDayOfPaymentMonth() {
        Clock anyClock = Clock.fixed(Instant.parse("2026-07-16T01:00:00Z"), KST);
        when(loadSettlementPort.findByPaymentId(13L)).thenReturn(Optional.empty());
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Settlement result = serviceWith(anyClock).createSettlementFromPayment(
                13L, 130L, new BigDecimal("50000"), null, "NORMAL", "MONTHLY_LAST",
                LocalDateTime.of(2026, 1, 15, 9, 0));

        assertThat(result.getSettlementDate()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test @DisplayName("윤년 2월 말일도 정확히 처리된다 (2028-02-29)")
    void leapYear_februaryLastDay() {
        Clock anyClock = Clock.fixed(Instant.parse("2028-02-10T01:00:00Z"), KST);
        when(loadSettlementPort.findByPaymentId(14L)).thenReturn(Optional.empty());
        when(saveSettlementPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Settlement result = serviceWith(anyClock).createSettlementFromPayment(
                14L, 140L, new BigDecimal("50000"), null, "NORMAL", "MONTHLY_LAST",
                LocalDateTime.of(2028, 2, 10, 9, 0));

        assertThat(result.getSettlementDate()).isEqualTo(LocalDate.of(2028, 2, 29));
    }
}
