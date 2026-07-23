package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.PayoutBackfillQueryPort;
import github.lms.lemuel.payout.application.port.out.PayoutBackfillQueryPort.SettlementForPayout;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutBackfillReport;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillMissingPayoutsService — 멱등 백필 오케스트레이션")
class BackfillMissingPayoutsServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 1, 31);

    @Mock PayoutBackfillQueryPort queryPort;
    @Mock RequestPayoutUseCase requestPayoutUseCase;

    BackfillMissingPayoutsService service;

    @BeforeEach
    void setUp() {
        service = new BackfillMissingPayoutsService(queryPort, requestPayoutUseCase, 100);
    }

    // ── status ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status: IMMEDIATE + HOLDBACK_RELEASE 잔여 합산")
    void status_sumsBothTypes() {
        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(3L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(2L);

        PayoutBackfillReport report = service.status(FROM, TO);

        assertThat(report.remaining()).isEqualTo(5);
        assertThat(report.complete()).isFalse();
        assertThat(report.created()).isZero();
    }

    @Test
    @DisplayName("status: 잔여 0 이면 complete=true")
    void status_zeroRemaining_complete() {
        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(0L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        assertThat(service.status(FROM, TO).complete()).isTrue();
    }

    @Test
    @DisplayName("status: from=null 이면 예외")
    void status_nullFrom_throws() {
        assertThatThrownBy(() -> service.status(null, TO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("status: from > to 이면 예외")
    void status_fromAfterTo_throws() {
        assertThatThrownBy(() -> service.status(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── backfill: IMMEDIATE ──────────────────────────────────────────────────

    @Test
    @DisplayName("backfill: IMMEDIATE 정산 1건 — sellerId/amount 있으면 created=1")
    void backfill_immediateCreated() {
        SettlementForPayout settlement = new SettlementForPayout(
                10L, 100L, 7L,
                new BigDecimal("97000"),
                new BigDecimal("97000"),
                BigDecimal.ZERO,
                false);

        // IMMEDIATE 페이지: 첫 호출 1건, 두 번째 호출 빈 리스트
        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(0L), anyInt()))
                .thenReturn(List.of(settlement));
        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(10L), anyInt()))
                .thenReturn(List.of());
        // HOLDBACK_RELEASE 페이지: 빈 리스트
        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(eq(FROM), eq(TO), eq(0L), anyInt()))
                .thenReturn(List.of());

        Payout savedPayout = dummyPayout(10L, PayoutType.IMMEDIATE);
        when(requestPayoutUseCase.requestPayoutOfType(eq(10L), eq(7L), eq(new BigDecimal("97000")), eq(PayoutType.IMMEDIATE)))
                .thenReturn(Optional.of(savedPayout));

        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(0L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        PayoutBackfillReport report = service.backfill(FROM, TO, null);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.complete()).isTrue();
    }

    @Test
    @DisplayName("backfill: sellerId=null 이면 failed 카운트, Payout 미생성")
    void backfill_nullSellerId_countsFailed() {
        SettlementForPayout settlement = new SettlementForPayout(
                10L, 100L, null, // sellerId=null
                new BigDecimal("97000"),
                new BigDecimal("97000"),
                BigDecimal.ZERO, false);

        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(0L), anyInt()))
                .thenReturn(List.of(settlement));
        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(10L), anyInt()))
                .thenReturn(List.of());
        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(1L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        PayoutBackfillReport report = service.backfill(FROM, TO, null);

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.created()).isZero();
        verify(requestPayoutUseCase, never()).requestPayoutOfType(any(), any(), any(), any());
    }

    @Test
    @DisplayName("backfill: amount=0 이면 skipped 카운트")
    void backfill_zeroAmount_countsSkipped() {
        SettlementForPayout settlement = new SettlementForPayout(
                10L, 100L, 7L,
                BigDecimal.ZERO,
                BigDecimal.ZERO, // immediatePayoutAmount=0
                BigDecimal.ZERO, false);

        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(0L), anyInt()))
                .thenReturn(List.of(settlement));
        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(10L), anyInt()))
                .thenReturn(List.of());
        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(0L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        PayoutBackfillReport report = service.backfill(FROM, TO, null);

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.created()).isZero();
        verify(requestPayoutUseCase, never()).requestPayoutOfType(any(), any(), any(), any());
    }

    @Test
    @DisplayName("backfill: DataIntegrityViolationException — skipped 처리 (2차 멱등)")
    void backfill_uniqueViolation_countsSkipped() {
        SettlementForPayout settlement = new SettlementForPayout(
                10L, 100L, 7L,
                new BigDecimal("97000"),
                new BigDecimal("97000"),
                BigDecimal.ZERO, false);

        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(0L), anyInt()))
                .thenReturn(List.of(settlement));
        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(10L), anyInt()))
                .thenReturn(List.of());
        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());

        when(requestPayoutUseCase.requestPayoutOfType(any(), any(), any(), eq(PayoutType.IMMEDIATE)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(0L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        PayoutBackfillReport report = service.backfill(FROM, TO, null);

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.created()).isZero();
        assertThat(report.failed()).isZero();
    }

    @Test
    @DisplayName("backfill: HOLDBACK_RELEASE 백필 — holdback 해제 완료 정산에 대해 생성")
    void backfill_holdbackRelease_created() {
        // IMMEDIATE 없음
        when(queryPort.findDoneWithoutImmediatePayoutPage(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());

        SettlementForPayout hbSettlement = new SettlementForPayout(
                20L, 200L, 7L,
                new BigDecimal("100000"),
                BigDecimal.ZERO, // immediatePayoutAmount (이미 있음 — 여기서는 무관)
                new BigDecimal("30000"), // holdbackAmount
                true); // holdbackReleased=true

        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(eq(FROM), eq(TO), eq(0L), anyInt()))
                .thenReturn(List.of(hbSettlement));
        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(eq(FROM), eq(TO), eq(20L), anyInt()))
                .thenReturn(List.of());

        Payout savedPayout = dummyPayout(20L, PayoutType.HOLDBACK_RELEASE);
        when(requestPayoutUseCase.requestPayoutOfType(eq(20L), eq(7L), eq(new BigDecimal("30000")), eq(PayoutType.HOLDBACK_RELEASE)))
                .thenReturn(Optional.of(savedPayout));

        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(0L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        PayoutBackfillReport report = service.backfill(FROM, TO, null);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.complete()).isTrue();
    }

    @Test
    @DisplayName("backfill: 다건 페이지 — 페이지 수 커밋 카운트")
    void backfill_multiplePages_pagesCommitted() {
        SettlementForPayout s1 = settlement(10L, "97000");
        SettlementForPayout s2 = settlement(20L, "50000");

        // 페이지 크기 1 설정으로 2페이지 실행
        service = new BackfillMissingPayoutsService(queryPort, requestPayoutUseCase, 1);

        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(0L), eq(1)))
                .thenReturn(List.of(s1));
        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(10L), eq(1)))
                .thenReturn(List.of(s2));
        when(queryPort.findDoneWithoutImmediatePayoutPage(eq(FROM), eq(TO), eq(20L), eq(1)))
                .thenReturn(List.of());
        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());

        when(requestPayoutUseCase.requestPayoutOfType(any(), any(), any(), eq(PayoutType.IMMEDIATE)))
                .thenReturn(Optional.of(dummyPayout(null, PayoutType.IMMEDIATE)));

        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(0L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        PayoutBackfillReport report = service.backfill(FROM, TO, 1);

        assertThat(report.created()).isEqualTo(2);
        // IMMEDIATE 두 페이지 + HOLDBACK_RELEASE 빈 페이지(카운트 안 됨 — 빈 첫 페이지로 break)
        assertThat(report.pagesCommitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("backfill: 대상 정산 없으면 created=0, complete=true")
    void backfill_noSettlements_completeTrue() {
        when(queryPort.findDoneWithoutImmediatePayoutPage(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(queryPort.findDoneWithoutHoldbackReleasePayoutPage(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());
        when(queryPort.countDoneWithoutImmediatePayout(FROM, TO)).thenReturn(0L);
        when(queryPort.countDoneWithoutHoldbackReleasePayout(FROM, TO)).thenReturn(0L);

        PayoutBackfillReport report = service.backfill(FROM, TO, null);

        assertThat(report.created()).isZero();
        assertThat(report.complete()).isTrue();
        verify(requestPayoutUseCase, never()).requestPayoutOfType(any(), any(), any(), any());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private static SettlementForPayout settlement(long id, String netAmount) {
        return new SettlementForPayout(id, id * 10, 7L,
                new BigDecimal(netAmount), new BigDecimal(netAmount),
                BigDecimal.ZERO, false);
    }

    private static Payout dummyPayout(Long settlementId, PayoutType type) {
        SellerBankAccount account = new SellerBankAccount("KB", "1234567890", "홍길동");
        LocalDateTime now = LocalDateTime.now();
        return Payout.rehydrate(99L, settlementId, type, 7L, new BigDecimal("97000"),
                account, PayoutStatus.REQUESTED, null, null, 0, null,
                now, null, null, null, now, now);
    }
}
