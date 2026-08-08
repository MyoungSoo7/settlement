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
                   PlaceHoldUseCase, ApplyOffsetUseCase, QueryDepositAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private static final int DEFAULT_HOLD_HOURS = 72;

    private final LoadDepositAccountPort loadAccountPort;
    private final SaveDepositAccountPort saveAccountPort;
    private final SaveDepositEntryPort saveEntryPort;
    private final LoadDepositHoldPort loadHoldPort;
    private final SaveDepositHoldPort saveHoldPort;
    private final SaveDepositOffsetShortfallPort saveShortfallPort;
    private final PublishDepositEventPort publishEventPort;

    public DepositService(LoadDepositAccountPort loadAccountPort,
                           SaveDepositAccountPort saveAccountPort,
                           SaveDepositEntryPort saveEntryPort,
                           LoadDepositHoldPort loadHoldPort,
                           SaveDepositHoldPort saveHoldPort,
                           SaveDepositOffsetShortfallPort saveShortfallPort,
                           PublishDepositEventPort publishEventPort) {
        this.loadAccountPort = loadAccountPort;
        this.saveAccountPort = saveAccountPort;
        this.saveEntryPort = saveEntryPort;
        this.loadHoldPort = loadHoldPort;
        this.saveHoldPort = saveHoldPort;
        this.saveShortfallPort = saveShortfallPort;
        this.publishEventPort = publishEventPort;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 입금 (settlement.confirmed)
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void credit(Long sellerId, BigDecimal amount, String referenceId, String referenceType) {
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
