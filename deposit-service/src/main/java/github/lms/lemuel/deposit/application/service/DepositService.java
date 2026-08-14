package github.lms.lemuel.deposit.application.service;

import github.lms.lemuel.deposit.application.port.in.*;
import github.lms.lemuel.deposit.application.port.out.*;
import github.lms.lemuel.deposit.domain.*;
import github.lms.lemuel.deposit.domain.exception.InsufficientDepositException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 예치금 도메인 서비스 — application 계층, JPA 직접 사용 금지.
 *
 * <p>모든 쓰기 연산은 {@code @Transactional} 범위 안에서 비관적 락을 획득하고,
 * 도메인 불변식(total = available + locked, all >= 0)을 강제한다.
 */
@Service
@Transactional
public class DepositService
        implements CreditDepositUseCase, DebitDepositUseCase,
                   PlaceHoldUseCase, ApplyOffsetUseCase, QueryDepositAccountUseCase,
                   ExpireDueHoldsUseCase, ManageShortfallUseCase {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private static final int DEFAULT_HOLD_HOURS = 72;

    private final LoadDepositAccountPort loadAccountPort;
    private final SaveDepositAccountPort saveAccountPort;
    private final SaveDepositEntryPort saveEntryPort;
    private final LoadDepositHoldPort loadHoldPort;
    private final SaveDepositHoldPort saveHoldPort;
    private final LoadDepositOffsetShortfallPort loadShortfallPort;
    private final SaveDepositOffsetShortfallPort saveShortfallPort;
    private final PublishDepositEventPort publishEventPort;
    private final DepositProofGate depositProofGate;

    public DepositService(LoadDepositAccountPort loadAccountPort,
                           SaveDepositAccountPort saveAccountPort,
                           SaveDepositEntryPort saveEntryPort,
                           LoadDepositHoldPort loadHoldPort,
                           SaveDepositHoldPort saveHoldPort,
                           LoadDepositOffsetShortfallPort loadShortfallPort,
                           SaveDepositOffsetShortfallPort saveShortfallPort,
                           PublishDepositEventPort publishEventPort,
                           DepositProofGate depositProofGate) {
        this.loadAccountPort = loadAccountPort;
        this.saveAccountPort = saveAccountPort;
        this.saveEntryPort = saveEntryPort;
        this.loadHoldPort = loadHoldPort;
        this.saveHoldPort = saveHoldPort;
        this.loadShortfallPort = loadShortfallPort;
        this.saveShortfallPort = saveShortfallPort;
        this.publishEventPort = publishEventPort;
        this.depositProofGate = depositProofGate;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 입금 (settlement.confirmed)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void credit(Long sellerId, BigDecimal amount, String referenceId, String referenceType) {
        // 증빙 대사 게이트(ADR 0036) — 이 참조에 증빙이 첨부돼 있으면 MATCHED 여야 기표 통과.
        // 증빙이 없으면 그대로 통과(점진 도입) — Kafka 자동 기표(SETTLEMENT)는 무영향.
        depositProofGate.assertMatchedIfProofExists(sellerId, referenceType, referenceId, amount);
        SellerDepositAccount account = getOrCreateAccount(sellerId);
        account.credit(amount);
        saveAccountPort.save(account);

        DepositEntry entry = DepositEntry.of(account.getId(), DepositEntryType.CREDIT,
                amount, referenceId, referenceType);
        saveEntryPort.save(entry);

        publishEventPort.publishBalanceChanged(account, "CREDIT");
        log.info("[deposit] credit sellerId={} amount={} ref={}", sellerId, amount, referenceId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 출금 (payout.completed)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void debit(Long sellerId, BigDecimal amount, String referenceId, String referenceType) {
        // 증빙 대사 게이트(ADR 0036) — credit 과 동일. Kafka 자동 기표(PAYOUT)는 무영향.
        depositProofGate.assertMatchedIfProofExists(sellerId, referenceType, referenceId, amount);
        SellerDepositAccount account = loadAccountForUpdate(sellerId);
        account.debit(amount);
        saveAccountPort.save(account);

        DepositEntry entry = DepositEntry.of(account.getId(), DepositEntryType.DEBIT,
                amount, referenceId, referenceType);
        saveEntryPort.save(entry);

        publishEventPort.publishBalanceChanged(account, "DEBIT");
        log.info("[deposit] debit sellerId={} amount={} ref={}", sellerId, amount, referenceId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // hold (card.authorized)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public DepositHold placeHold(Long sellerId, DepositHolderType holderType,
                                  String holderReference, BigDecimal amount,
                                  LocalDateTime expiresAt) {
        // 멱등: 동일 (holderType, holderReference) 이면 기존 hold 반환
        Optional<DepositHold> existing = loadHoldPort.findByHolderTypeAndReference(holderType, holderReference);
        if (existing.isPresent()) {
            log.info("[deposit] placeHold idempotent hit holderType={} ref={}", holderType, holderReference);
            return existing.get();
        }

        SellerDepositAccount account = loadAccountForUpdate(sellerId);
        LocalDateTime expiry = expiresAt != null ? expiresAt
                : LocalDateTime.now().plusHours(DEFAULT_HOLD_HOURS);

        account.lock(amount);
        saveAccountPort.save(account);

        DepositHold hold = DepositHold.place(account.getId(), holderType, holderReference, amount, expiry);
        saveHoldPort.save(hold);

        DepositEntry entry = DepositEntry.of(account.getId(), DepositEntryType.HOLD,
                amount, holderReference, holderType.name());
        saveEntryPort.save(entry);

        publishEventPort.publishHoldPlaced(hold, account);
        log.info("[deposit] hold placed sellerId={} holderType={} ref={} amount={}",
                sellerId, holderType, holderReference, amount);
        return hold;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // offset (card.captured) — 혼합 모델(C)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void applyOffset(Long sellerId, DepositHolderType holderType,
                             String holderReference, BigDecimal offsetAmount,
                             int offsetSequence, OffsetDateTime occurredAt) {
        SellerDepositAccount account = loadAccountForUpdate(sellerId);
        Optional<DepositHold> holdOpt = loadHoldPort.findByHolderTypeAndReference(holderType, holderReference);

        BigDecimal applied = BigDecimal.ZERO;
        Long sourceHoldId = null;

        if (holdOpt.isPresent() && holdOpt.get().isActive()) {
            DepositHold hold = holdOpt.get();
            sourceHoldId = hold.getId();

            // locked 에서 먼저 상계
            BigDecimal captureAmount = offsetAmount.min(hold.getRemainingAmount());
            hold.capture(captureAmount);
            account.captureFromLocked(captureAmount);
            applied = applied.add(captureAmount);

            // 잔여 locked 가 있으면 release
            if (hold.getRemainingAmount().signum() > 0) {
                account.release(hold.getRemainingAmount());
                hold.release();
                DepositEntry releaseEntry = DepositEntry.of(account.getId(), DepositEntryType.RELEASE,
                        hold.getRemainingAmount(), holderReference, holderType.name());
                saveEntryPort.save(releaseEntry);
                publishEventPort.publishHoldReleased(hold, account);
            }
            saveHoldPort.save(hold);
        }

        // hold 에서 충당하고도 부족한 경우 — available 에서 직접 차감
        BigDecimal remaining = offsetAmount.subtract(applied);
        if (remaining.signum() > 0) {
            BigDecimal fromAvailable = remaining.min(account.getAvailable());
            if (fromAvailable.signum() > 0) {
                account.debitAvailable(fromAvailable);
                applied = applied.add(fromAvailable);
            }
        }

        saveAccountPort.save(account);

        // OFFSET 엔트리 기록
        if (applied.signum() > 0) {
            DepositEntry offsetEntry = DepositEntry.ofOffset(
                    account.getId(), applied, holderReference, holderType.name(),
                    offsetSequence, sourceHoldId);
            saveEntryPort.save(offsetEntry);
            publishEventPort.publishOffsetApplied(offsetEntry, account);
        }

        // 부족분 처리
        BigDecimal shortfall = offsetAmount.subtract(applied);
        if (shortfall.signum() > 0) {
            DepositOffsetShortfall shortfallRecord = DepositOffsetShortfall.open(
                    sellerId, holderType, holderReference,
                    offsetAmount, applied, sourceHoldId,
                    occurredAt != null ? occurredAt : OffsetDateTime.now());
            saveShortfallPort.save(shortfallRecord);
            publishEventPort.publishOffsetShortfall(shortfallRecord);
            log.warn("[deposit] shortfall sellerId={} req={} applied={} shortfall={}",
                    sellerId, offsetAmount, applied, shortfall);
        }

        log.info("[deposit] offset applied sellerId={} holderRef={} amount={} applied={}",
                sellerId, holderReference, offsetAmount, applied);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 만료 hold 회수 (배치)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 건별 독립 트랜잭션이 아니라 <b>건별 예외 격리</b>다 — 한 건이 터져도 루프를 멈추지 않고,
     * 실패한 hold 는 ACTIVE 로 남아 다음 회차가 다시 집는다. 배치가 매번 다시 도는 것 자체가
     * 복구 수단이므로, 실패를 삼키지 않되(로그+카운트) 전체를 되돌리지도 않는다.
     */
    @Override
    public int expireDueHolds(LocalDateTime cutoff) {
        List<DepositHold> due = loadHoldPort.findExpiredStillHolding(cutoff);
        if (due.isEmpty()) {
            return 0;
        }

        int expired = 0;
        for (DepositHold hold : due) {
            try {
                expireOne(hold);
                expired++;
            } catch (RuntimeException e) {
                // 삼키지 않는다 — 남기고 넘어간다. 조용히 건너뛰면 영영 안 풀리는 hold 가 생긴다.
                log.error("[deposit] hold 만료 회수 실패 holdId={} accountId={} — 다음 회차 재시도",
                        hold.getId(), hold.getAccountId(), e);
            }
        }
        log.info("[deposit] hold 만료 회수: 대상 {}건 중 {}건 처리", due.size(), expired);
        return expired;
    }

    private void expireOne(DepositHold hold) {
        SellerDepositAccount account = loadAccountPort.findByIdForUpdate(hold.getAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "hold 가 가리키는 계좌가 없습니다. accountId=" + hold.getAccountId()));

        // 되돌리는 것은 remaining 이다. 부분 캡처된 hold 에서 originalAmount 를 쓰면
        // 이미 계좌를 떠난 캡처분까지 available 로 되살아나 없는 돈이 생긴다.
        BigDecimal remaining = hold.getRemainingAmount();

        // 종단 상태를 갈라 적는다 — 둘 다 "재원을 놓았다"지만 사후에 읽는 의미가 다르다.
        // EXPIRED = 한 번도 쓰이지 않고 시간이 지났다(상류가 매입을 아예 안 보냈다).
        // RELEASED = 쓰이다 남은 잔여를 놓았다(상류가 일부만 매입했다).
        // 하나로 뭉개면 "왜 이 hold 가 풀렸나"를 카드 승인 쪽과 대사할 때 구분이 사라진다.
        if (hold.getStatus() == DepositHoldStatus.PARTIALLY_CAPTURED) {
            hold.release();
        } else {
            hold.expire();
        }
        saveHoldPort.save(hold);

        if (remaining.signum() > 0) {
            account.release(remaining);
            saveAccountPort.save(account);

            DepositEntry entry = DepositEntry.of(account.getId(), DepositEntryType.RELEASE,
                    remaining, hold.getHolderReference(), hold.getHolderType().name());
            saveEntryPort.save(entry);
            publishEventPort.publishHoldReleased(hold, account);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 부족분 해소 (운영자 주도)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DepositOffsetShortfall> findOpenShortfalls() {
        return loadShortfallPort.findByStatus(DepositShortfallStatus.OPEN);
    }

    @Override
    public BigDecimal resolveFromAvailable(Long shortfallId) {
        DepositOffsetShortfall shortfall = requireShortfall(shortfallId);
        BigDecimal outstanding = shortfall.getShortfallAmount();

        SellerDepositAccount account = loadAccountForUpdate(shortfall.getSellerId());
        if (account.getAvailable().compareTo(outstanding) < 0) {
            // 부분 해소를 하지 않는 이유 — 남은 부족분이 새 레코드를 낳거나 같은 레코드를
            // 두 번 건드리게 되고, 그러면 "이 부족분은 언제 덮였나"의 답이 갈래로 쪼개진다.
            throw new InsufficientDepositException(
                    "가용액(" + account.getAvailable() + ")이 부족분(" + outstanding + ")에 못 미칩니다",
                    String.valueOf(shortfall.getSellerId()), "resolveShortfall");
        }

        account.debitAvailable(outstanding);
        saveAccountPort.save(account);

        DepositEntry entry = DepositEntry.ofOffset(account.getId(), outstanding,
                shortfall.getHolderReference(), shortfall.getHolderType().name(),
                0, shortfall.getSourceHoldId());
        saveEntryPort.save(entry);

        shortfall.resolve(outstanding);
        saveShortfallPort.save(shortfall);

        publishEventPort.publishOffsetApplied(entry, account);
        publishEventPort.publishBalanceChanged(account, "SHORTFALL_RESOLVED");
        log.info("[deposit] shortfall 해소 id={} sellerId={} amount={}",
                shortfallId, shortfall.getSellerId(), outstanding);
        return outstanding;
    }

    @Override
    public void writeOff(Long shortfallId) {
        DepositOffsetShortfall shortfall = requireShortfall(shortfallId);
        shortfall.writeOff();
        saveShortfallPort.save(shortfall);
        // 잔고를 건드리지 않는다 — 상각은 돈의 이동이 아니라 회수를 포기했다는 판단의 기록이다.
        log.warn("[deposit] shortfall 상각 id={} sellerId={} amount={}",
                shortfallId, shortfall.getSellerId(), shortfall.getShortfallAmount());
    }

    private DepositOffsetShortfall requireShortfall(Long shortfallId) {
        return loadShortfallPort.findById(shortfallId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "부족분을 찾을 수 없습니다: " + shortfallId));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 조회
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<SellerDepositAccount> findBySellerId(Long sellerId) {
        return loadAccountPort.findBySellerId(sellerId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    private SellerDepositAccount getOrCreateAccount(Long sellerId) {
        return loadAccountPort.findBySellerIdForUpdate(sellerId)
                .orElseGet(() -> saveAccountPort.save(SellerDepositAccount.open(sellerId)));
    }

    private SellerDepositAccount loadAccountForUpdate(Long sellerId) {
        return loadAccountPort.findBySellerIdForUpdate(sellerId)
                .orElseThrow(() -> new IllegalStateException("예치 계좌가 없습니다. sellerId=" + sellerId));
    }
}
