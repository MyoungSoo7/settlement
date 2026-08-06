package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.application.port.in.RetryFailedPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.LoadSellerBankAccountPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

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
    private final LoadSellerBankAccountPort bankAccountPort;
    private final PayoutSingleExecutor singleExecutor;
    private final PayoutLimitChecker limitChecker;
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter limitedCounter;
    private final Counter retryCounter;
    private final Counter conflictCounter;
    private final Counter autoCreatedCounter;
    /** KST 기준 시각 소스 — 일 한도 판정 기준일이 JVM 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    public PayoutService(LoadPayoutPort loadPort,
                          SavePayoutPort savePort,
                          LoadSellerBankAccountPort bankAccountPort,
                          PayoutSingleExecutor singleExecutor,
                          PayoutLimitChecker limitChecker,
                          MeterRegistry meterRegistry,
                          Clock clock) {
        this.loadPort = loadPort;
        this.savePort = savePort;
        this.bankAccountPort = bankAccountPort;
        this.singleExecutor = singleExecutor;
        this.limitChecker = limitChecker;
        this.clock = clock;
        this.completedCounter = Counter.builder("payout.completed").register(meterRegistry);
        this.failedCounter = Counter.builder("payout.failed").register(meterRegistry);
        this.limitedCounter = Counter.builder("payout.limited").register(meterRegistry);
        this.retryCounter = Counter.builder("payout.admin.retry").register(meterRegistry);
        this.conflictCounter = Counter.builder("payout.conflict").register(meterRegistry);
        this.autoCreatedCounter = Counter.builder("payout.auto.created").register(meterRegistry);
    }

    @Override
    public Payout requestForSettlement(Long settlementId, Long sellerId,
                                        BigDecimal amount, SellerBankAccount account) {
        // 멱등성: 같은 정산의 IMMEDIATE 지급은 1번만 생성 — 가장 위험한 사고 (이중 송금) 방지
        return loadPort.findBySettlementIdAndType(settlementId, PayoutType.IMMEDIATE).orElseGet(() -> {
            Payout newPayout = Payout.requestFromSettlement(
                    settlementId, sellerId, amount, account, PayoutType.IMMEDIATE);
            Payout saved = savePort.save(newPayout);
            log.info("[Payout] requested: id={}, settlementId={}, sellerId={}, amount={}",
                    saved.getId(), settlementId, sellerId, amount);
            return saved;
        });
    }

    @Override
    public Optional<Payout> requestPayoutOfType(Long settlementId, Long sellerId,
                                                BigDecimal amount, PayoutType payoutType) {
        if (settlementId == null || sellerId == null || payoutType == null) {
            return Optional.empty();
        }
        // 금액 0(또는 음수)은 지급 자체가 없음 — Payout 을 만들지 않는다(AC: 금액 0 이면 미생성).
        if (amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }

        // 1차 멱등: 이미 (정산, 유형) Payout 이 있으면 그대로 반환(중복 전달·재시도 흡수).
        Optional<Payout> existing = loadPort.findBySettlementIdAndType(settlementId, payoutType);
        if (existing.isPresent()) {
            return existing;
        }

        SellerBankAccount account = bankAccountPort.findBySellerId(sellerId).orElse(null);
        if (account == null) {
            log.warn("[Payout] 셀러 지급수단 미해석 — payout 생성 생략. sellerId={}, settlementId={}, type={}",
                    sellerId, settlementId, payoutType);
            return Optional.empty();
        }

        try {
            Payout saved = savePort.save(
                    Payout.requestFromSettlement(settlementId, sellerId, amount, account, payoutType));
            autoCreatedCounter.increment();
            log.info("[Payout] auto-created: id={}, settlementId={}, type={}, sellerId={}, amount={}",
                    saved.getId(), settlementId, payoutType, sellerId, amount);
            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // 2차 멱등(하드 백스톱): (settlement_id, payout_type) 부분 UNIQUE 경합 — 동시 처리에서
            // 다른 트랜잭션이 먼저 생성. 이중 지급이 아니라 정상 경합이므로 실패가 아닌 경합으로 표시한다.
            conflictCounter.increment();
            log.warn("[Payout] concurrent-create skip: settlementId={}, type={}, reason={}",
                    settlementId, payoutType, e.toString());
            throw new PayoutConcurrentClaimException(settlementId);
        }
    }

    /**
     * 송금 미리보기 — 아무것도 보내지 않고 "이번 배치가 무엇을 얼마나 보낼지"만 산출한다.
     *
     * <p>실행 경로는 건별 송금이 COMPLETED 되면서 일 한도가 자연히 소진되지만, 미리보기는 아무것도
     * 완료시키지 않아 DB 합계가 고정이다. 그대로 건별 판정하면 같은 셀러의 여러 건이 전부 "보낼 수 있음"
     * 으로 잡혀 <b>실제보다 많이 나간다고 약속</b>하게 된다. 그래서 나갈 예정 금액을 누적해 다음 건 판정에
     * 넘긴다 — 밀릴 건의 금액은 실제로 나가지 않으므로 누적에 넣지 않는다.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PayoutPreview previewPending() {
        List<Payout> pending = loadPort.findByStatus(PayoutStatus.REQUESTED, BATCH_SIZE);
        LocalDate today = LocalDate.now(clock);

        List<PayoutPreviewLine> lines = new ArrayList<>();
        Map<Long, BigDecimal> projectedBySeller = new HashMap<>();
        BigDecimal projectedSystem = BigDecimal.ZERO;
        BigDecimal sendableAmount = BigDecimal.ZERO;
        BigDecimal limitedAmount = BigDecimal.ZERO;
        int sendable = 0;
        int limited = 0;

        for (Payout p : pending) {
            BigDecimal sellerProjected = projectedBySeller.getOrDefault(p.getSellerId(), BigDecimal.ZERO);
            var decision = limitChecker.canSend(p.getSellerId(), p.getAmount(), today,
                    sellerProjected, projectedSystem);
            lines.add(new PayoutPreviewLine(p.getId(), p.getSellerId(), p.getAmount(),
                    decision.allowed(), decision.reason()));
            if (decision.allowed()) {
                sendable++;
                sendableAmount = sendableAmount.add(p.getAmount());
                projectedBySeller.put(p.getSellerId(), sellerProjected.add(p.getAmount()));
                projectedSystem = projectedSystem.add(p.getAmount());
            } else {
                limited++;
                limitedAmount = limitedAmount.add(p.getAmount());
            }
        }
        return new PayoutPreview(sendable, sendableAmount, limited, limitedAmount, List.copyOf(lines));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExecutionReport executeAllPending() {
        List<Payout> pending = loadPort.findByStatus(PayoutStatus.REQUESTED, BATCH_SIZE);
        int succeeded = 0, failed = 0, limited = 0, conflicts = 0;
        LocalDate today = LocalDate.now(clock);

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
