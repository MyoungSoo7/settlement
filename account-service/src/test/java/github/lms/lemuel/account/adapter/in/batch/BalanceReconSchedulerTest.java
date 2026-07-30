package github.lms.lemuel.account.adapter.in.batch;

import github.lms.lemuel.account.application.port.in.TrialBalanceQuery;
import github.lms.lemuel.account.application.port.in.TrialBalanceQuery.BalanceDrift;
import github.lms.lemuel.account.application.port.in.TrialBalanceQuery.BalanceRecon;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 정기 대사 배치({@link BalanceReconScheduler}) — 대사 결과·실패가 Prometheus 게이지에 어떻게
 * 반영되는지 검증한다. 드리프트 게이지가 알람 정본이므로 실행마다 최신 값으로 덮어써야 하고,
 * <b>대사 실행 실패는 "정합"으로 위장되면 안 된다</b>(감사 MED-1) — 실패 시 게이지는 직전 값을
 * 유지하고 {@code last.success.epoch} 정체가 실패 신호다.
 */
class BalanceReconSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-07-30T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TrialBalanceQuery query = mock(TrialBalanceQuery.class);
    private final BalanceReconScheduler scheduler =
            new BalanceReconScheduler(query, registry, Clock.fixed(T0, ZoneOffset.UTC));

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("첫 성공 전 드리프트 게이지는 −1(미검증) — '정합 0' 과 구별된다")
    void beforeFirstRun_gaugeIsSentinel() {
        assertThat(gauge("account.balance.recon.drift.count")).isEqualTo(-1.0);
        assertThat(gauge("account.balance.recon.last.success.epoch")).isZero();
    }

    @Test
    @DisplayName("정합이면 드리프트 0·대조 쌍 수·성공 시각이 게이지로 노출된다")
    void consistentRun_setsZeroDriftAndSuccessEpoch() {
        when(query.balanceRecon()).thenReturn(new BalanceRecon(9L, 0L, List.of()));

        scheduler.reconcile();

        assertThat(gauge("account.balance.recon.drift.count")).isZero();
        assertThat(gauge("account.balance.recon.checked.pairs")).isEqualTo(9.0);
        assertThat(gauge("account.balance.recon.last.success.epoch")).isEqualTo(T0.getEpochSecond());
    }

    @Test
    @DisplayName("드리프트 검출 시 게이지에 건수가 실리고, 다음 정합 실행에서 0 으로 회복된다")
    void driftRun_updatesGauge_thenRecovers() {
        when(query.balanceRecon()).thenReturn(new BalanceRecon(9L, 2L, List.of(
                new BalanceDrift(OwnerType.SELLER, "55", GlAccount.SELLER_PAYABLE,
                        new BigDecimal("120000"), new BigDecimal("100000")),
                new BalanceDrift(OwnerType.BORROWER, "42", GlAccount.SECURED_LOAN_RECEIVABLE,
                        new BigDecimal("0"), new BigDecimal("300000000")))));

        scheduler.reconcile();
        assertThat(gauge("account.balance.recon.drift.count")).isEqualTo(2.0);

        // 캐시 정정 후 다음 주기는 0 으로 돌아와야 알람이 해제된다 — 게이지는 누적이 아니라 최신 상태다.
        when(query.balanceRecon()).thenReturn(new BalanceRecon(9L, 0L, List.of()));
        scheduler.reconcile();
        assertThat(gauge("account.balance.recon.drift.count")).isZero();
    }

    @Test
    @DisplayName("대사 실행 실패는 게이지를 건드리지 않는다 — 실패가 '정합 0' 으로 위장되지 않는다")
    void failedRun_keepsPreviousGauges() {
        when(query.balanceRecon()).thenReturn(new BalanceRecon(9L, 2L,
                List.of(new BalanceDrift(OwnerType.SELLER, "55", GlAccount.SELLER_PAYABLE,
                        new BigDecimal("120000"), new BigDecimal("100000")))));
        scheduler.reconcile();
        double epochAfterSuccess = gauge("account.balance.recon.last.success.epoch");

        when(query.balanceRecon()).thenThrow(new IllegalStateException("DB down"));
        scheduler.reconcile();   // 예외를 삼키고(스케줄 지속) 게이지는 직전 값 유지

        assertThat(gauge("account.balance.recon.drift.count")).isEqualTo(2.0);
        assertThat(gauge("account.balance.recon.last.success.epoch")).isEqualTo(epochAfterSuccess);
    }
}
