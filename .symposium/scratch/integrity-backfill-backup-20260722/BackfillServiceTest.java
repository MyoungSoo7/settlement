package github.lms.lemuel.integrity.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.integrity.application.port.out.BackfillTargetPort;
import github.lms.lemuel.integrity.application.port.out.BackfillTargetPort.AdjustmentReversalTarget;
import github.lms.lemuel.integrity.application.port.out.IntegrityQueryPort;
import github.lms.lemuel.integrity.domain.BackfillReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.ledger.application.port.in.ReverseEntryUseCase;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 시드 P0-4 — 과거 데이터 멱등 백필의 페이지 루프·종결·dry run·감사 기록 규약.
 *
 * <p>탐지는 {@link IntegrityQueryPort} 재사용이 전제라, 정정된 건이 다음 탐지에서 사라지는
 * 단조 감소를 루프 종결 조건으로 삼는다(고칠 수 없는 후보만 남으면 created=0 페이지에서 중단).
 */
@ExtendWith(MockitoExtension.class)
class BackfillServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 1);

    @Mock IntegrityQueryPort queryPort;
    @Mock LoadSettlementPort loadSettlementPort;
    @Mock LoadSellerIdPort loadSellerIdPort;
    @Mock RequestPayoutUseCase requestPayoutUseCase;
    @Mock ReverseEntryUseCase reverseEntryUseCase;
    @Mock BackfillTargetPort targetPort;
    @Mock AuditLogger auditLogger;

    private BackfillService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new BackfillService(queryPort, loadSettlementPort, loadSellerIdPort,
                requestPayoutUseCase, reverseEntryUseCase, targetPort, auditLogger, clock);
    }

    // ───────────────────────── payout 백필 (INV-6) ─────────────────────────

    @Test
    @DisplayName("payout 백필 — 탐지된 정산마다 확정 배선과 동일한 즉시지급 Payout 을 생성하고 재탐지로 수렴한다")
    void createsPayoutsForDetectedSettlements() {
        when(queryPort.payoutRecon(DATE)).thenReturn(
                payoutReport(List.of(10L, 11L)),
                payoutReport(List.of()));
        stubPayoutTarget(10L, 100L, 7L, new BigDecimal("900"));
        stubPayoutTarget(11L, 101L, 7L, new BigDecimal("500"));
        when(requestPayoutUseCase.requestPayoutOfType(anyLong(), anyLong(), any(), eq(PayoutType.IMMEDIATE)))
                .thenReturn(Optional.of(mock(Payout.class)));

        BackfillReport report = service.backfillPayouts(DATE, DATE, 20, false);

        assertThat(report.created()).isEqualTo(2);
        assertThat(report.skipped()).isZero();
        assertThat(report.remaining()).isZero();
        assertThat(report.complete()).isTrue();
        assertThat(report.pagesProcessed()).isEqualTo(1);
        verify(requestPayoutUseCase).requestPayoutOfType(10L, 7L, new BigDecimal("900"), PayoutType.IMMEDIATE);
        verify(requestPayoutUseCase).requestPayoutOfType(11L, 7L, new BigDecimal("500"), PayoutType.IMMEDIATE);
    }

    @Test
    @DisplayName("payout 백필 — 판매자 미해석 후보만 남으면 created=0 페이지에서 종결한다(무한 루프 금지)")
    void terminatesWhenOnlyUnfixableCandidatesRemain() {
        when(queryPort.payoutRecon(DATE)).thenReturn(payoutReport(List.of(10L)));
        Settlement settlement = mock(Settlement.class);
        when(settlement.getPaymentId()).thenReturn(100L);
        when(loadSettlementPort.findById(10L)).thenReturn(Optional.of(settlement));
        when(loadSellerIdPort.findSellerIdByPaymentId(100L)).thenReturn(Optional.empty());

        BackfillReport report = service.backfillPayouts(DATE, DATE, 20, false);

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.remaining()).isEqualTo(1);
        assertThat(report.complete()).isFalse();
        verify(queryPort, times(1)).payoutRecon(DATE);
        verify(requestPayoutUseCase, never()).requestPayoutOfType(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("payout 백필 — 유스케이스가 거절(0원 등)하면 skipped 로 센다")
    void countsDeclinedPayoutAsSkipped() {
        when(queryPort.payoutRecon(DATE)).thenReturn(payoutReport(List.of(10L)));
        stubPayoutTarget(10L, 100L, 7L, BigDecimal.ZERO);
        when(requestPayoutUseCase.requestPayoutOfType(10L, 7L, BigDecimal.ZERO, PayoutType.IMMEDIATE))
                .thenReturn(Optional.empty());

        BackfillReport report = service.backfillPayouts(DATE, DATE, 20, false);

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("payout 백필 dry run — 후보 건수만 세고 쓰기·감사가 없다")
    void dryRunCountsCandidatesWithoutWriting() {
        when(queryPort.payoutRecon(DATE)).thenReturn(payoutReport(List.of(10L, 11L)));

        BackfillReport report = service.backfillPayouts(DATE, DATE, 20, true);

        assertThat(report.created()).isZero();
        assertThat(report.remaining()).isEqualTo(2);
        assertThat(report.pagesProcessed()).isZero();
        verifyNoInteractions(requestPayoutUseCase, loadSettlementPort, auditLogger);
    }

    @Test
    @DisplayName("payout 백필 — pageSize 가 페이지당 처리 건수를 제한한다")
    void honorsPageSize() {
        when(queryPort.payoutRecon(DATE)).thenReturn(
                payoutReport(List.of(10L, 11L)),
                payoutReport(List.of(11L)),
                payoutReport(List.of()));
        stubPayoutTarget(10L, 100L, 7L, new BigDecimal("900"));
        stubPayoutTarget(11L, 101L, 7L, new BigDecimal("500"));
        when(requestPayoutUseCase.requestPayoutOfType(anyLong(), anyLong(), any(), eq(PayoutType.IMMEDIATE)))
                .thenReturn(Optional.of(mock(Payout.class)));

        BackfillReport report = service.backfillPayouts(DATE, DATE, 1, false);

        assertThat(report.created()).isEqualTo(2);
        assertThat(report.pagesProcessed()).isEqualTo(2);
        assertThat(report.remaining()).isZero();
    }

    @Test
    @DisplayName("payout 백필 — 날짜 구간을 하루씩 순회한다")
    void iteratesDateRange() {
        LocalDate day2 = DATE.plusDays(1);
        when(queryPort.payoutRecon(DATE)).thenReturn(payoutReport(List.of(10L)));
        when(queryPort.payoutRecon(day2)).thenReturn(payoutReport(List.of(11L, 12L)));

        BackfillReport report = service.backfillPayouts(DATE, day2, 20, true);

        assertThat(report.remaining()).isEqualTo(3);
        verify(queryPort).payoutRecon(DATE);
        verify(queryPort).payoutRecon(day2);
    }

    @Test
    @DisplayName("payout 백필 실행은 감사 로그를 남긴다 (dry run 은 남기지 않는다)")
    void recordsAuditOnExecution() {
        when(queryPort.payoutRecon(DATE)).thenReturn(
                payoutReport(List.of(10L)),
                payoutReport(List.of()));
        stubPayoutTarget(10L, 100L, 7L, new BigDecimal("900"));
        when(requestPayoutUseCase.requestPayoutOfType(anyLong(), anyLong(), any(), eq(PayoutType.IMMEDIATE)))
                .thenReturn(Optional.of(mock(Payout.class)));

        service.backfillPayouts(DATE, DATE, 20, false);

        verify(auditLogger).record(eq(AuditAction.INTEGRITY_PAYOUT_BACKFILLED),
                eq("SETTLEMENT_BACKFILL"), eq(DATE + "~" + DATE), contains("\"created\":1"));
    }

    @Test
    @DisplayName("payout 백필 — 확정 배치와의 정상 경합(ConcurrentClaim)은 실행을 중단시키지 않고 skipped 로 격리한다")
    void isolatesConcurrentClaimAsSkipped() {
        when(queryPort.payoutRecon(DATE)).thenReturn(
                payoutReport(List.of(10L, 11L)),
                payoutReport(List.of()));
        stubPayoutTarget(10L, 100L, 7L, new BigDecimal("900"));
        stubPayoutTarget(11L, 101L, 7L, new BigDecimal("500"));
        when(requestPayoutUseCase.requestPayoutOfType(eq(10L), anyLong(), any(), eq(PayoutType.IMMEDIATE)))
                .thenThrow(new github.lms.lemuel.payout.application.service.PayoutConcurrentClaimException(10L));
        when(requestPayoutUseCase.requestPayoutOfType(eq(11L), anyLong(), any(), eq(PayoutType.IMMEDIATE)))
                .thenReturn(Optional.of(mock(Payout.class)));

        BackfillReport report = service.backfillPayouts(DATE, DATE, 20, false);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(1);
        verify(auditLogger).record(eq(AuditAction.INTEGRITY_PAYOUT_BACKFILLED),
                eq("SETTLEMENT_BACKFILL"), eq(DATE + "~" + DATE), contains("\"created\":1"));
    }

    // ─────────────────── 조정 역분개 백필 (INV-5 missingReverse) ───────────────────

    @Test
    @DisplayName("역분개 백필 — 탐지된 조정마다 출처별 역분개를 생성한다 (양수 금액·출처 매핑)")
    void createsReverseEntriesForDetectedAdjustments() {
        when(queryPort.ledgerCompleteness(eq(DATE), anyInt(), any())).thenReturn(
                ledgerReport(List.of(5L)),
                ledgerReport(List.of()));
        AdjustmentReversalTarget target = new AdjustmentReversalTarget(
                5L, 10L, 77L, ReferenceType.CHARGEBACK, new BigDecimal("3000"), DATE);
        when(targetPort.loadAdjustmentTargets(List.of(5L))).thenReturn(List.of(target));
        when(reverseEntryUseCase.reverseForReference(10L, 77L, ReferenceType.CHARGEBACK,
                new BigDecimal("3000"), DATE)).thenReturn(List.of(mock(LedgerEntry.class)));

        BackfillReport report = service.backfillAdjustmentReversals(DATE, DATE, 20, false);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.remaining()).isZero();
        assertThat(report.complete()).isTrue();
        verify(auditLogger).record(eq(AuditAction.INTEGRITY_REVERSAL_BACKFILLED),
                eq("SETTLEMENT_BACKFILL"), eq(DATE + "~" + DATE), contains("\"created\":1"));
    }

    @Test
    @DisplayName("역분개 백필 — 이미 역분개가 있으면(빈 반환) skipped 로 세고 종결한다")
    void countsAlreadyReversedAsSkippedAndTerminates() {
        when(queryPort.ledgerCompleteness(eq(DATE), anyInt(), any()))
                .thenReturn(ledgerReport(List.of(5L)));
        AdjustmentReversalTarget target = new AdjustmentReversalTarget(
                5L, 10L, 77L, ReferenceType.REFUND, new BigDecimal("1000"), DATE);
        when(targetPort.loadAdjustmentTargets(List.of(5L))).thenReturn(List.of(target));
        when(reverseEntryUseCase.reverseForReference(10L, 77L, ReferenceType.REFUND,
                new BigDecimal("1000"), DATE)).thenReturn(List.of());

        BackfillReport report = service.backfillAdjustmentReversals(DATE, DATE, 20, false);

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.remaining()).isEqualTo(1);
        verify(queryPort, times(1)).ledgerCompleteness(eq(DATE), anyInt(), any());
    }

    @Test
    @DisplayName("역분개 백필 dry run — 후보 건수만 세고 쓰기·감사가 없다")
    void reversalDryRunCountsOnly() {
        when(queryPort.ledgerCompleteness(eq(DATE), anyInt(), any()))
                .thenReturn(ledgerReport(List.of(5L, 6L)));

        BackfillReport report = service.backfillAdjustmentReversals(DATE, DATE, 20, true);

        assertThat(report.remaining()).isEqualTo(2);
        assertThat(report.pagesProcessed()).isZero();
        verifyNoInteractions(reverseEntryUseCase, targetPort, auditLogger);
    }

    @Test
    @DisplayName("역분개 백필 — poison-pill 조정(금액 0 등 검증 예외)은 skipped 로 격리하고 나머지를 계속 처리한다")
    void isolatesPoisonPillAdjustmentAsSkipped() {
        // 1차 탐지 [poison 5, 정상 6] → 6 정정 후 2차 탐지 [5]만 잔존 → created=0 페이지에서 종결
        when(queryPort.ledgerCompleteness(eq(DATE), anyInt(), any())).thenReturn(
                ledgerReport(List.of(5L, 6L)),
                ledgerReport(List.of(5L)));
        AdjustmentReversalTarget poison = new AdjustmentReversalTarget(
                5L, 10L, 77L, ReferenceType.REFUND, BigDecimal.ZERO, DATE);
        AdjustmentReversalTarget healthy = new AdjustmentReversalTarget(
                6L, 11L, 78L, ReferenceType.CHARGEBACK, new BigDecimal("3000"), DATE);
        when(targetPort.loadAdjustmentTargets(List.of(5L, 6L))).thenReturn(List.of(poison, healthy));
        when(targetPort.loadAdjustmentTargets(List.of(5L))).thenReturn(List.of(poison));
        when(reverseEntryUseCase.reverseForReference(10L, 77L, ReferenceType.REFUND, BigDecimal.ZERO, DATE))
                .thenThrow(new IllegalArgumentException("amount 양수여야 합니다"));
        when(reverseEntryUseCase.reverseForReference(11L, 78L, ReferenceType.CHARGEBACK,
                new BigDecimal("3000"), DATE)).thenReturn(List.of(mock(LedgerEntry.class)));

        BackfillReport report = service.backfillAdjustmentReversals(DATE, DATE, 20, false);

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.skipped()).isEqualTo(2); // poison 이 두 페이지에서 각각 격리됨
        assertThat(report.remaining()).isEqualTo(1);
        verify(auditLogger).record(eq(AuditAction.INTEGRITY_REVERSAL_BACKFILLED),
                eq("SETTLEMENT_BACKFILL"), eq(DATE + "~" + DATE), contains("\"skipped\":2"));
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    private void stubPayoutTarget(Long settlementId, Long paymentId, Long sellerId, BigDecimal immediateAmount) {
        Settlement settlement = mock(Settlement.class);
        when(settlement.getPaymentId()).thenReturn(paymentId);
        when(settlement.getImmediatePayoutAmount()).thenReturn(immediateAmount);
        when(loadSettlementPort.findById(settlementId)).thenReturn(Optional.of(settlement));
        when(loadSellerIdPort.findSellerIdByPaymentId(paymentId)).thenReturn(Optional.of(sellerId));
    }

    private static PayoutReconReport payoutReport(List<Long> withoutPayout) {
        return PayoutReconReport.of(DATE, withoutPayout.size(), BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
                withoutPayout, List.of(), List.of());
    }

    private static LedgerCompletenessReport ledgerReport(List<Long> missingReverseIds) {
        return LedgerCompletenessReport.of(DATE, 60, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                List.of(), 0, List.of(), missingReverseIds, 0, 0, 0);
    }
}
