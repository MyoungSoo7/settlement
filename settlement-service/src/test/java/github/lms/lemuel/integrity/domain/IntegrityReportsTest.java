package github.lms.lemuel.integrity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정합성 리포트 도메인 판정(ok/reasons) 단위 검증 — Docker 불필요.
 * 집계 SQL 을 포함한 end-to-end 는 IntegrityPhaseAIntegrationTest 가 담당한다.
 */
class IntegrityReportsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 6);

    // ── LedgerCompletenessReport (INV-5) ──────────────────────────────────

    @Test
    @DisplayName("INV-5: 누락·불일치·FAILED 가 없으면 ok, grace 대기만 있어도 ok")
    void ledgerCompletenessOkWithOnlyGracePending() {
        var report = LedgerCompletenessReport.of(DATE, 15,
                10, new BigDecimal("1000000.00"),
                18, new BigDecimal("900000.00"),
                List.of(), 1, List.of(), List.of(),
                2, 0, 30);
        assertThat(report.ok()).isTrue();
        assertThat(report.reasons()).isEmpty();
        assertThat(report.pendingWithinGrace()).isEqualTo(1);
    }

    @Test
    @DisplayName("INV-5: 분개 누락/반쪽/역분개 누락/outbox FAILED 각각이 ok 를 무너뜨린다")
    void ledgerCompletenessViolations() {
        var missing = LedgerCompletenessReport.of(DATE, 15, 10, BigDecimal.ZERO,
                0, BigDecimal.ZERO, List.of(7L), 0, List.of(), List.of(), 0, 0, 0);
        assertThat(missing.ok()).isFalse();
        assertThat(missing.reasons()).anySatisfy(r -> assertThat(r).contains("INV-5"));

        var half = LedgerCompletenessReport.of(DATE, 15, 10, BigDecimal.ZERO,
                1, BigDecimal.ONE, List.of(), 0, List.of(8L), List.of(), 0, 0, 0);
        assertThat(half.ok()).isFalse();
        assertThat(half.reasons()).anySatisfy(r -> assertThat(r).contains("반쪽"));

        var reverse = LedgerCompletenessReport.of(DATE, 15, 10, BigDecimal.ZERO,
                0, BigDecimal.ZERO, List.of(), 0, List.of(), List.of(9L), 0, 0, 0);
        assertThat(reverse.ok()).isFalse();

        var failed = LedgerCompletenessReport.of(DATE, 15, 10, BigDecimal.ZERO,
                0, BigDecimal.ZERO, List.of(), 0, List.of(), List.of(), 0, 3, 0);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.reasons()).anySatisfy(r -> assertThat(r).contains("FAILED"));
    }

    // ── PayoutReconReport (INV-6) ─────────────────────────────────────────

    @Test
    @DisplayName("INV-6: payout 미생성은 정보성(ok 유지), 과다/이중 지급은 위반")
    void payoutReconJudgment() {
        var onlyMissing = PayoutReconReport.of(DATE, 5, new BigDecimal("500000.00"),
                3, new BigDecimal("300000.00"), 3,
                List.of(1L, 2L), List.of(), List.of(), List.of());
        assertThat(onlyMissing.ok()).isTrue();
        assertThat(onlyMissing.settlementsWithoutPayout()).hasSize(2);

        var overpaid = PayoutReconReport.of(DATE, 5, new BigDecimal("500000.00"),
                3, new BigDecimal("300000.00"), 3,
                List.of(),
                List.of(new PayoutReconReport.OverpaidPayout(11L, 1L,
                        new BigDecimal("97000.00"), new BigDecimal("96500.00"))),
                List.of(), List.of());
        assertThat(overpaid.ok()).isFalse();
        assertThat(overpaid.reasons()).anySatisfy(r -> assertThat(r).contains("과다 지급"));

        var dup = PayoutReconReport.of(DATE, 5, BigDecimal.ZERO,
                2, BigDecimal.ZERO, 0, List.of(), List.of(), List.of(1L), List.of());
        assertThat(dup.ok()).isFalse();
        assertThat(dup.reasons()).anySatisfy(r -> assertThat(r).contains("이중 지급"));

        // 유형별 1건씩이어도 payout 합계가 net 을 넘으면 위반 (유형 분산 이중 지급)
        var overTotal = PayoutReconReport.of(DATE, 5, new BigDecimal("500000.00"),
                2, new BigDecimal("120000.00"), 0, List.of(), List.of(), List.of(),
                List.of(new PayoutReconReport.OverTotalSettlement(1L,
                        new BigDecimal("120000.00"), new BigDecimal("96500.00"))));
        assertThat(overTotal.ok()).isFalse();
        assertThat(overTotal.reasons()).anySatisfy(r -> assertThat(r).contains("합계"));
    }

    // ── PayoutBounceReconReport (INV-13) ────────────────────────────────────

    @Test
    @DisplayName("INV-13: 미재지급 반송만 있으면 정보성(ok 유지), 금액불일치/settlement_id/고아는 위반")
    void payoutBounceReconJudgment() {
        var onlyUnresolved = PayoutBounceReconReport.of(3, 2, 1, List.of(), List.of(), List.of());
        assertThat(onlyUnresolved.ok()).isTrue();
        assertThat(onlyUnresolved.unresolvedBounces()).isEqualTo(1);

        var mismatch = PayoutBounceReconReport.of(1, 1, 0,
                List.of(new PayoutBounceReconReport.AmountMismatch(1L, 500L, 999L,
                        new BigDecimal("95500.00"), new BigDecimal("90000.00"))),
                List.of(), List.of());
        assertThat(mismatch.ok()).isFalse();
        assertThat(mismatch.reasons()).anySatisfy(r -> assertThat(r).contains("금액 불일치"));

        var withSettlement = PayoutBounceReconReport.of(1, 1, 0, List.of(), List.of(999L), List.of());
        assertThat(withSettlement.ok()).isFalse();
        assertThat(withSettlement.reasons()).anySatisfy(r -> assertThat(r).contains("이중지급 가드"));

        var orphan = PayoutBounceReconReport.of(0, 0, 0, List.of(), List.of(), List.of(777L));
        assertThat(orphan.ok()).isFalse();
        assertThat(orphan.reasons()).anySatisfy(r -> assertThat(r).contains("고아"));
    }

    // ── HoldbackStatusReport (INV-7) ──────────────────────────────────────

    @Test
    @DisplayName("INV-7: overdue 0 이면 ok, 있으면 위반")
    void holdbackJudgment() {
        var ok = HoldbackStatusReport.of(DATE, 0, BigDecimal.ZERO, List.of(),
                new BigDecimal("100.00"), new BigDecimal("50.00"), LocalDateTime.now());
        assertThat(ok.ok()).isTrue();

        var overdue = HoldbackStatusReport.of(DATE, 2, new BigDecimal("38950.00"),
                List.of(1L, 2L), BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertThat(overdue.ok()).isFalse();
        assertThat(overdue.reasons()).anySatisfy(r -> assertThat(r).contains("INV-7"));
    }

    // ── RefundAdjustmentReport (INV-8) ────────────────────────────────────

    @Test
    @DisplayName("INV-8: 조정 누락이 없으면 ok, 있으면 금액 합계와 함께 위반")
    void refundAdjustmentJudgment() {
        var ok = RefundAdjustmentReport.of(DATE.minusDays(5), DATE, 3,
                new BigDecimal("50000.00"), 3, List.of(), BigDecimal.ZERO, false);
        assertThat(ok.ok()).isTrue();
        assertThat(ok.reasons()).isEmpty();

        var missing = RefundAdjustmentReport.of(DATE.minusDays(5), DATE, 3,
                new BigDecimal("50000.00"), 2, List.of(902L), new BigDecimal("15000.00"), false);
        assertThat(missing.ok()).isFalse();
        assertThat(missing.reasons()).anySatisfy(r -> assertThat(r).contains("INV-8"));
    }

    @Test
    @DisplayName("INV-8: 조회 상한 절단은 ok 를 유지하되 완전 검사 아님을 경고한다")
    void refundAdjustmentTruncationWarns() {
        var truncated = RefundAdjustmentReport.of(DATE.minusDays(5), DATE, 2000,
                BigDecimal.ZERO, 2000, List.of(), BigDecimal.ZERO, true);
        assertThat(truncated.ok()).isTrue(); // 누락 자체는 없음
        assertThat(truncated.reasons()).anySatisfy(r -> assertThat(r).contains("절단"));
        assertThat(truncated.truncated()).isTrue();
    }

    // ── StuckStateReport (INV-11) ─────────────────────────────────────────

    @Test
    @DisplayName("INV-11: 전 항목 0 이면 ok, SENDING 체류는 이중지급 경고를 담는다")
    void stuckJudgment() {
        var ok = StuckStateReport.of(60, DATE, List.of(), List.of(), List.of(), List.of(), 0, 0);
        assertThat(ok.ok()).isTrue();

        var sending = StuckStateReport.of(60, DATE, List.of(), List.of(),
                List.of(new StuckStateReport.StuckPayout(1L, 2L,
                        new BigDecimal("50000.00"), LocalDateTime.now().minusHours(2))),
                List.of(), 0, 0);
        assertThat(sending.ok()).isFalse();
        assertThat(sending.reasons()).anySatisfy(r -> assertThat(r).contains("이중지급"));

        var overdueConfirm = StuckStateReport.of(60, DATE, List.of(),
                List.of(new StuckStateReport.StuckItem(3L, "REQUESTED", LocalDateTime.now())),
                List.of(), List.of(), 0, 0);
        assertThat(overdueConfirm.ok()).isFalse();
        assertThat(overdueConfirm.reasons()).anySatisfy(r -> assertThat(r).contains("확정 배치"));
    }
}
