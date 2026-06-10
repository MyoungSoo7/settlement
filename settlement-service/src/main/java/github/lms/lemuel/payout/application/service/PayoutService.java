package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.application.port.in.RetryFailedPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 출금(Payout) 핵심 서비스 — 정산 사이클의 종착점.
 *
 * <p>역할:
 * <ul>
 *   <li>정산 → Payout 전환 (멱등)</li>
 *   <li>REQUESTED 상태 Payout 일괄 펌뱅킹 호출 (한도 검증 + 개별 트랜잭션 격리)</li>
 *   <li>운영자 retry / cancel</li>
 * </ul>
 *
 * <p>Prometheus 메트릭:
 * <ul>
 *   <li>{@code payout.completed.total} — 송금 완료 누적</li>
 *   <li>{@code payout.failed.total} — 송금 실패 누적</li>
 *   <li>{@code payout.limited.total} — 한도 초과로 skip 된 누적</li>
 *   <li>{@code payout.admin.retry.total} — 운영자 재시도</li>
 * </ul>
 */
@Service
@Transactional
public class PayoutService implements RequestPayoutUseCase, ExecutePayoutUseCase, RetryFailedPayoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    private static final int BATCH_SIZE = 100;

    private final LoadPayoutPort loadPort;
    private final SavePayoutPort savePort;
    private final PayoutSingleExecutor singleExecutor;
    private final PayoutLimitChecker limitChecker;
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter limitedCounter;
    private final Counter retryCounter;
    private final Counter conflictCounter;

    public PayoutService(LoadPayoutPort loadPort,
                          SavePayoutPort savePort,
                          PayoutSingleExecutor singleExecutor,
                          PayoutLimitChecker limitChecker,
                          MeterRegistry meterRegistry) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.singleExecutor = singleExecutor;
        this.limitChecker = limitChecker;
        this.completedCounter = Counter.builder("payout.completed").register(meterRegistry);
        this.failedCounter = Counter.builder("payout.failed").register(meterRegistry);
        this.limitedCounter = Counter.builder("payout.limited").register(meterRegistry);
        this.retryCounter = Counter.builder("payout.admin.retry").register(meterRegistry);
        this.conflictCounter = Counter.builder("payout.conflict").register(meterRegistry);
    }

    @Override
    public Payout requestForSettlement(Long settlementId, Long sellerId,
                                        BigDecimal amount, SellerBankAccount account) {
        // 멱등성: 같은 정산은 1번만 Payout 생성 — 가장 위험한 사고 (이중 송금) 방지
        return loadPort.findBySettlementId(settlementId).orElseGet(() -> {
            Payout newPayout = Payout.requestFromSettlement(settlementId, sellerId, amount, account);
            Payout saved = savePort.save(newPayout);
            log.info("[Payout] requested: id={}, settlementId={}, sellerId={}, amount={}",
                    saved.getId(), settlementId, sellerId, amount);
            return saved;
        });
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExecutionReport executeAllPending() {
        List<Payout> pending = loadPort.findByStatus(PayoutStatus.REQUESTED, BATCH_SIZE);
        int succeeded = 0, failed = 0, limited = 0, conflicts = 0;
        LocalDate today = LocalDate.now();

        for (Payout p : pending) {
            // 한도 검사 → 미달 시 다음 배치 (다음 영업일) 로 미룸
            var decision = limitChecker.canSend(p.getSellerId(), p.getAmount(), today);
            if (!decision.allowed()) {
                limited++;
                limitedCounter.increment();
                log.warn("[Payout] limit-skip: payoutId={}, reason={}", p.getId(), decision.reason());
                continue;
            }

            try {
                singleExecutor.execute(p);
                succeeded++;
                completedCounter.increment();
            } catch (PayoutConcurrentClaimException | OptimisticLockingFailureException e) {
                // 동시성 경합 — 다른 인스턴스가 이미 처리. 실패 아님(알람 X), 해당 건은 그대로 두고 skip.
                conflicts++;
                conflictCounter.increment();
                log.warn("[Payout] concurrent-skip: payoutId={}, reason={}", p.getId(), e.toString());
            } catch (RuntimeException e) {
                failed++;
                failedCounter.increment();
                log.error("[Payout] 실패: payoutId={}, err={}", p.getId(), e.toString());
            }
        }
        if (succeeded > 0 || failed > 0 || limited > 0 || conflicts > 0) {
            log.info("[Payout] batch complete: succeeded={}, failed={}, limited={}, conflicts={}",
                    succeeded, failed, limited, conflicts);
        }
        return new ExecutionReport(succeeded, failed, limited);
    }

    @Override
    public Payout retry(Long payoutId, String operatorId) {
        Payout p = loadPort.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Payout not found: " + payoutId));
        p.retry(operatorId);
        Payout saved = savePort.save(p);
        retryCounter.increment();
        log.warn("[Payout] retry by operator: payoutId={}, operator={}, retryCount={}",
                payoutId, operatorId, saved.getRetryCount());
        return saved;
    }

    @Override
    public Payout cancel(Long payoutId, String operatorId, String reason) {
        Payout p = loadPort.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Payout not found: " + payoutId));
        p.cancel(operatorId, reason);
        log.warn("[Payout] cancel by operator: payoutId={}, operator={}, reason={}",
                payoutId, operatorId, reason);
        return savePort.save(p);
    }
}
