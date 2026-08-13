package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.in.BackfillMissingPayoutsUseCase;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.application.port.out.PayoutBackfillQueryPort;
import github.lms.lemuel.payout.application.port.out.PayoutBackfillQueryPort.SettlementForPayout;
import github.lms.lemuel.payout.domain.PayoutBackfillReport;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.application.port.in.ApplyLoanDeductionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 미생성 Payout 멱등 백필 서비스.
 *
 * <p>INV-6 탐지 SQL(settlementsWithoutPayout)을 페이지 단위 재사용해 확정 정산의 누락된
 * Payout 을 append-only 로 신규 생성한다. 각 정산당 IMMEDIATE / HOLDBACK_RELEASE 두 유형을
 * 독립적으로 처리한다.
 *
 * <h3>IMMEDIATE 는 재구동, HOLDBACK_RELEASE 는 직접 생성</h3>
 * IMMEDIATE 지급액은 원천징수·대출 상환차감·채권상계를 거쳐 정해지므로 백필이 자체 계산하지 않고
 * 확정 경로의 종착점({@link ApplyLoanDeductionUseCase#redriveFromRecordedDeduction})을 재구동한다.
 * HOLDBACK_RELEASE 는 유보분 해제라 차감 대상이 아니므로 그대로 생성한다.
 *
 * <h3>멱등성 보장</h3>
 * <ul>
 *   <li>1차: {@link RequestPayoutUseCase#requestPayoutOfType} 내 {@code findBySettlementIdAndType} 조회.</li>
 *   <li>2차: DB {@code uq_payouts_settlement_type}(settlement_id, payout_type) UNIQUE 제약 —
 *       동시 백필이 INSERT 경합 시 {@link DataIntegrityViolationException} 이 발생하면 기존 건을 스킵 카운트로 처리.</li>
 * </ul>
 *
 * <h3>페이지 단위 커밋</h3>
 * 루프가 서비스 메서드 자체를 트랜잭션으로 감싸지 않는다. {@link RequestPayoutUseCase#requestPayoutOfType} 은
 * {@code PayoutService} 의 {@code @Transactional} 메서드이므로 각 호출이 독립적으로 커밋된다
 * (self-invocation 없이 프록시 경계를 타는 것은 별개 빈 호출이라 보장). 페이지 내 개별 정산은
 * 각자 독립 트랜잭션으로 처리되어 부분 성공이 보존된다.
 *
 * <h3>append-only 원칙</h3>
 * DONE 정산·POSTED 원장은 건드리지 않는다. Payout 신규 INSERT 만 수행한다.
 */
@Service
public class BackfillMissingPayoutsService implements BackfillMissingPayoutsUseCase {

    private static final Logger log = LoggerFactory.getLogger(BackfillMissingPayoutsService.class);
    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final PayoutBackfillQueryPort queryPort;
    private final RequestPayoutUseCase requestPayoutUseCase;
    private final ApplyLoanDeductionUseCase applyLoanDeductionUseCase;
    private final int defaultPageSize;

    public BackfillMissingPayoutsService(
            PayoutBackfillQueryPort queryPort,
            RequestPayoutUseCase requestPayoutUseCase,
            ApplyLoanDeductionUseCase applyLoanDeductionUseCase,
            @Value("${app.payout.backfill.page-size:" + DEFAULT_PAGE_SIZE + "}") int defaultPageSize) {
        this.queryPort = queryPort;
        this.requestPayoutUseCase = requestPayoutUseCase;
        this.applyLoanDeductionUseCase = applyLoanDeductionUseCase;
        this.defaultPageSize = defaultPageSize;
    }

    @Override
    public PayoutBackfillReport backfill(LocalDate from, LocalDate to, Integer pageSizeOverride) {
        validateRange(from, to);
        int pageSize = clampPageSize(pageSizeOverride);

        long created = 0;
        long skipped = 0;
        long failed = 0;
        int pagesCommitted = 0;

        // ── IMMEDIATE Payout 백필 ─────────────────────────────────────────
        long afterId = 0;
        while (true) {
            List<SettlementForPayout> page = queryPort.findDoneWithoutImmediatePayoutPage(
                    from, to, afterId, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (SettlementForPayout s : page) {
                Result r = redriveImmediate(s);
                created += r.created;
                skipped += r.skipped;
                failed += r.failed;
            }
            pagesCommitted++;
            afterId = page.get(page.size() - 1).settlementId();
        }

        // ── HOLDBACK_RELEASE Payout 백필 (holdback_released=true 이고 holdback>0) ───
        afterId = 0;
        while (true) {
            List<SettlementForPayout> page = queryPort.findDoneWithoutHoldbackReleasePayoutPage(
                    from, to, afterId, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (SettlementForPayout s : page) {
                Result r = createPayout(s, s.holdbackAmount(), PayoutType.HOLDBACK_RELEASE);
                created += r.created;
                skipped += r.skipped;
                failed += r.failed;
            }
            pagesCommitted++;
            afterId = page.get(page.size() - 1).settlementId();
        }

        // 잔여 건수 = IMMEDIATE 미생성 + HOLDBACK_RELEASE 미생성 합계
        long remainingImmediate = queryPort.countDoneWithoutImmediatePayout(from, to);
        long remainingHoldback = queryPort.countDoneWithoutHoldbackReleasePayout(from, to);
        long remaining = remainingImmediate + remainingHoldback;

        log.info("[PayoutBackfill] 완료: created={}, skipped={}, failed={}, remaining={}, pages={}",
                created, skipped, failed, remaining, pagesCommitted);

        return PayoutBackfillReport.of(from, to, pageSize,
                created, skipped, failed, remaining, pagesCommitted);
    }

    @Override
    public PayoutBackfillReport status(LocalDate from, LocalDate to) {
        validateRange(from, to);
        long remainingImmediate = queryPort.countDoneWithoutImmediatePayout(from, to);
        long remainingHoldback = queryPort.countDoneWithoutHoldbackReleasePayout(from, to);
        long remaining = remainingImmediate + remainingHoldback;
        return PayoutBackfillReport.status(from, to, remaining);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * IMMEDIATE 백필 — <b>금액을 여기서 계산하지 않는다</b>.
     *
     * <p>과거에는 {@code s.immediatePayoutAmount()} 를 그대로 지급했다. 그 값은 원천징수·대출
     * 상환차감·채권상계를 하나도 반영하지 않은 총액이라, 백필이 돌 때마다 확정 경로보다 많은 현금이
     * 나갔다. 계산을 복제하면 정책이 바뀔 때마다 두 경로가 다시 갈라지므로, 백필은 확정 경로의
     * 종착점({@code ApplyLoanDeductionUseCase})을 재구동하고 금액 산식은 거기 한 곳에만 둔다.
     *
     * <p>차감 기록이 없으면 <b>지급하지 않고 FAILED 로 남긴다</b>. 이 정산은 loan 이벤트가 도착하지
     * 않은 건이고, 차감액을 모르는 채 지급하면 대출채권이 사라진다. 남은 건수는 {@code remaining}
     * 으로 계속 보고되므로 "백필을 돌렸는데 잔여가 안 줄었다" 가 곧 loan 이벤트 유실 신호다.
     */
    private Result redriveImmediate(SettlementForPayout s) {
        if (s.sellerId() == null) {
            log.warn("[PayoutBackfill] sellerId 미해석 — 스킵. settlementId={}, type=IMMEDIATE", s.settlementId());
            return Result.FAILED;
        }
        java.math.BigDecimal immediate = s.immediatePayoutAmount();
        if (immediate == null || immediate.signum() <= 0) {
            // 차감은 금액을 늘리지 못하므로 즉시지급분이 0 이면 재구동해도 지급은 0 이다.
            log.debug("[PayoutBackfill] 즉시지급분 0 이하 — 스킵. settlementId={}, amount={}",
                    s.settlementId(), immediate);
            return Result.SKIPPED;
        }
        try {
            boolean redriven = applyLoanDeductionUseCase.redriveFromRecordedDeduction(
                    s.settlementId(), s.sellerId());
            if (!redriven) {
                log.warn("[PayoutBackfill] 대출 차감 기록 없음 — 백필하지 않는다(loan 이벤트 미도착 의심). "
                        + "settlementId={}, sellerId={}", s.settlementId(), s.sellerId());
                return Result.FAILED;
            }
            return Result.CREATED;
        } catch (DataIntegrityViolationException e) {
            log.warn("[PayoutBackfill] UNIQUE 충돌(동시 실행) — 스킵. settlementId={}, type=IMMEDIATE",
                    s.settlementId());
            return Result.SKIPPED;
        } catch (PayoutConcurrentClaimException e) {
            log.warn("[PayoutBackfill] 동시 생성 경합 — 스킵. settlementId={}, type=IMMEDIATE", s.settlementId());
            return Result.SKIPPED;
        }
    }

    private Result createPayout(SettlementForPayout s, java.math.BigDecimal amount, PayoutType type) {
        if (s.sellerId() == null) {
            log.warn("[PayoutBackfill] sellerId 미해석 — 스킵. settlementId={}, type={}", s.settlementId(), type);
            return Result.FAILED;
        }
        if (amount == null || amount.signum() <= 0) {
            log.debug("[PayoutBackfill] 금액 0 이하 — 스킵. settlementId={}, type={}, amount={}",
                    s.settlementId(), type, amount);
            return Result.SKIPPED;
        }
        try {
            var result = requestPayoutUseCase.requestPayoutOfType(s.settlementId(), s.sellerId(), amount, type);
            // requestPayoutOfType 은 기존 존재 시 Optional.of(기존) 반환 — 멱등 조회에서 걸림
            if (result.isPresent()) {
                // 반환된 Payout 이 방금 생성된 것인지 이미 존재하던 것인지 구별하기 위해 로그 레벨로 표시.
                // requestPayoutOfType 은 내부적으로 findBySettlementIdAndType 으로 이미 존재 여부를 확인하므로,
                // 여기 도달한 것은 Payout 이 생성·반환된 것이다(이미 있으면 isPresent 이고 created 가 아님).
                // 단, 둘 다 Optional.isPresent() = true 이므로 메트릭 구분은 서비스 내부 로그에 의존한다.
                // 여기서는 단순히 created 로 카운트한다(이미 있던 건은 페이지 쿼리에서 필터돼 이 분기에 안 온다).
                return Result.CREATED;
            } else {
                // amount > 0 이고 sellerId 도 있는데 empty 반환 = 수취정보 해석 실패
                log.warn("[PayoutBackfill] 수취정보 해석 실패 — Payout 미생성. settlementId={}, type={}",
                        s.settlementId(), type);
                return Result.FAILED;
            }
        } catch (DataIntegrityViolationException e) {
            // 2차 멱등: 동시 백필 실행 시 INSERT 경합 — 기존 건 존재로 처리
            log.warn("[PayoutBackfill] UNIQUE 충돌(동시 실행) — 스킵. settlementId={}, type={}", s.settlementId(), type);
            return Result.SKIPPED;
        } catch (PayoutConcurrentClaimException e) {
            // requestPayoutOfType 내부에서 DataIntegrityViolationException 을 잡아 던지는 경우
            log.warn("[PayoutBackfill] 동시 생성 경합 — 스킵. settlementId={}, type={}", s.settlementId(), type);
            return Result.SKIPPED;
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to 날짜는 필수입니다");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from 은 to 보다 이전이어야 합니다: " + from + " > " + to);
        }
    }

    private int clampPageSize(Integer override) {
        if (override == null || override <= 0) {
            return defaultPageSize;
        }
        return Math.min(override, MAX_PAGE_SIZE);
    }

    private enum Result {
        CREATED(1, 0, 0),
        SKIPPED(0, 1, 0),
        FAILED(0, 0, 1);

        final int created;
        final int skipped;
        final int failed;

        Result(int created, int skipped, int failed) {
            this.created = created;
            this.skipped = skipped;
            this.failed = failed;
        }
    }
}
