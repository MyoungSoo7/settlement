package github.lms.lemuel.account.adapter.in.batch;

import github.lms.lemuel.account.application.port.in.TrialBalanceQuery;
import github.lms.lemuel.account.application.port.in.TrialBalanceQuery.BalanceRecon;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 실체화 잔액 정기 대사 배치 (ADR 0030 Phase 3).
 *
 * <p>{@code account_balances}(파생 캐시)를 원장 재합산(정답지)과 주기 대조해 드리프트 건수를
 * Prometheus 게이지로 노출한다. 드리프트는 "payout 분할 라우팅이 틀린 잔액으로 판단하고 있다"는
 * 신호라 관측이 곧 방어선이다 — 자동 정정은 하지 않는다(캐시 재작성은 원인 규명 후 운영 판단,
 * 정정 절차는 Phase 1 백필 쿼리 재실행).
 *
 * <p>게이지 3종(감사 MED-1 — 관측 자체의 생존 신호 포함):
 * <ul>
 *   <li>{@code account.balance.recon.drift.count} — 0 정상, <b>−1 은 "아직 성공한 대사 없음"</b>(미검증 ≠ 정합)</li>
 *   <li>{@code account.balance.recon.checked.pairs} — 마지막 성공 대사의 대조 쌍 수</li>
 *   <li>{@code account.balance.recon.last.success.epoch} — 마지막 성공 시각(초). 정체되면 대사 자체가
 *       죽은 것이다(예외는 잡아서 로그만 남기고 게이지는 직전 값을 유지하므로, 이 게이지가 실패 알람 축)</li>
 * </ul>
 * 대조는 읽기 전용이라 소비 전용 원칙과 무관하다(Outbox·발행 없음).
 */
@Component
@ConditionalOnProperty(name = "app.recon.balance.enabled", havingValue = "true", matchIfMissing = true)
public class BalanceReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(BalanceReconScheduler.class);

    /** 아직 성공한 대사가 없음 — "드리프트 0(정합)" 과 구별되는 센티널. */
    static final long NOT_YET_RUN = -1L;

    private final TrialBalanceQuery trialBalanceQuery;
    private final java.time.Clock clock;
    private final AtomicLong driftCount = new AtomicLong(NOT_YET_RUN);
    private final AtomicLong checkedPairs = new AtomicLong(0);
    private final AtomicLong lastSuccessEpoch = new AtomicLong(0);

    public BalanceReconScheduler(TrialBalanceQuery trialBalanceQuery, MeterRegistry meterRegistry,
                                 java.time.Clock clock) {
        this.trialBalanceQuery = trialBalanceQuery;
        this.clock = clock;
        meterRegistry.gauge("account.balance.recon.drift.count", driftCount);
        meterRegistry.gauge("account.balance.recon.checked.pairs", checkedPairs);
        meterRegistry.gauge("account.balance.recon.last.success.epoch", lastSuccessEpoch);
    }

    @Scheduled(fixedDelayString = "${app.recon.balance.interval-ms:600000}",
               initialDelayString = "${app.recon.balance.initial-delay-ms:60000}")
    public void reconcile() {
        BalanceRecon recon;
        try {
            recon = trialBalanceQuery.balanceRecon();
        } catch (RuntimeException e) {
            // 실패를 삼키지 않으면 fixedDelay 가 매 주기 예외 스택만 쌓는다. 게이지는 직전 값을
            // 유지하므로 last.success.epoch 정체가 실패 신호다 — 여기서 값을 건드리지 않는다.
            log.error("실체화 잔액 대사 실행 실패 — last.success.epoch 정체로 알람하라", e);
            return;
        }
        checkedPairs.set(recon.checkedPairs());
        driftCount.set(recon.driftCount());
        lastSuccessEpoch.set(clock.instant().getEpochSecond());
        if (recon.consistent()) {
            log.info("실체화 잔액 대사 정합. checkedPairs={}", recon.checkedPairs());
            return;
        }
        // 상세는 상한 캡 목록만 — 건수 정본은 driftCount 게이지다.
        log.warn("실체화 잔액 드리프트 검출! driftCount={} / checkedPairs={} — 상위: {}",
                recon.driftCount(), recon.checkedPairs(),
                recon.drifts().stream().limit(5)
                        .map(d -> d.ownerType() + ":" + d.ownerId() + ":" + d.account()
                                + " Δ" + d.delta())
                        .toList());
    }
}
