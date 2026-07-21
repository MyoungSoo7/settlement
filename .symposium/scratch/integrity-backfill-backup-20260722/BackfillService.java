package github.lms.lemuel.integrity.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.integrity.application.port.in.RunBackfillUseCase;
import github.lms.lemuel.integrity.application.port.out.BackfillTargetPort;
import github.lms.lemuel.integrity.application.port.out.BackfillTargetPort.AdjustmentReversalTarget;
import github.lms.lemuel.integrity.application.port.out.IntegrityQueryPort;
import github.lms.lemuel.integrity.domain.BackfillReport;
import github.lms.lemuel.ledger.application.port.in.ReverseEntryUseCase;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.application.service.PayoutConcurrentClaimException;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * 과거 데이터 멱등 백필 (시드 P0-4) — 탐지는 무결성 스위트(INV-5/INV-6) 재사용, 정정은 기존
 * 유스케이스 재사용. 이 서비스는 새 탐지·새 쓰기 경로를 만들지 않는 오케스트레이션 계층이다.
 *
 * <p><b>append-only</b>: DONE 정산·POSTED 원장을 수정하지 않는다 — Payout 신규 생성
 * ((정산, 지급유형) UNIQUE 멱등)과 역분개 신규 적재((reference, 계정쌍) UNIQUE 멱등)만 한다.
 *
 * <p><b>페이지 루프</b>: 정정된 건은 다음 탐지에서 사라지므로(단조 감소) 커서 없이 "남은 것만
 * 다시 집는" 방식으로 재개 가능하다(PII 재암호화 백필과 동일 패턴). 고칠 수 없는 후보(판매자
 * 미해석·0원)만 남으면 created=0 페이지에서 종결해 무한 루프를 차단한다. 트랜잭션 경계는
 * 아이템별(기존 유스케이스의 @Transactional) — 부분 실패 시 이미 커밋된 건은 재실행에서
 * 멱등 스킵된다.
 */
@Slf4j
@Service
public class BackfillService implements RunBackfillUseCase {

    /** 탐지 grace — 방금 확정돼 outbox 폴러가 아직 못 쫓아간 건을 오탐하지 않기 위한 창(리포트 기본값과 동일). */
    private static final int GRACE_MINUTES = 60;

    private final IntegrityQueryPort queryPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSellerIdPort loadSellerIdPort;
    private final RequestPayoutUseCase requestPayoutUseCase;
    private final ReverseEntryUseCase reverseEntryUseCase;
    private final BackfillTargetPort targetPort;
    private final AuditLogger auditLogger;
    private final Clock clock;

    public BackfillService(IntegrityQueryPort queryPort,
                           LoadSettlementPort loadSettlementPort,
                           LoadSellerIdPort loadSellerIdPort,
                           RequestPayoutUseCase requestPayoutUseCase,
                           ReverseEntryUseCase reverseEntryUseCase,
                           BackfillTargetPort targetPort,
                           AuditLogger auditLogger,
                           Clock clock) {
        this.queryPort = queryPort;
        this.loadSettlementPort = loadSettlementPort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.requestPayoutUseCase = requestPayoutUseCase;
        this.reverseEntryUseCase = reverseEntryUseCase;
        this.targetPort = targetPort;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @Override
    public BackfillReport backfillPayouts(LocalDate from, LocalDate to, int pageSize, boolean dryRun) {
        BackfillReport report = run(from, to, pageSize, dryRun,
                date -> queryPort.payoutRecon(date).settlementsWithoutPayout(),
                this::createImmediatePayout);
        if (!dryRun) {
            audit(AuditAction.INTEGRITY_PAYOUT_BACKFILLED, from, to, report);
        }
        return report;
    }

    @Override
    public BackfillReport backfillAdjustmentReversals(LocalDate from, LocalDate to, int pageSize, boolean dryRun) {
        BackfillReport report = run(from, to, pageSize, dryRun,
                date -> queryPort.ledgerCompleteness(date, GRACE_MINUTES,
                        LocalDateTime.now(clock).minusMinutes(GRACE_MINUTES)).missingReverseAdjustmentIds(),
                this::createReverseEntriesForPage);
        if (!dryRun) {
            audit(AuditAction.INTEGRITY_REVERSAL_BACKFILLED, from, to, report);
        }
        return report;
    }

    /** 날짜 순회 × (탐지 → 페이지 정정 → 재탐지) 루프의 공통 골격. */
    private BackfillReport run(LocalDate from, LocalDate to, int pageSize, boolean dryRun,
                               Function<LocalDate, List<Long>> detect,
                               Function<List<Long>, PageResult> fixPage) {
        int created = 0;
        int skipped = 0;
        int remaining = 0;
        int pages = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<Long> candidates = detect.apply(date);
            if (dryRun) {
                remaining += candidates.size();
                continue;
            }
            // 종결 증명: created>0 페이지는 전체 후보를 최소 1건 줄이고(정정분은 탐지에서 탈락),
            // created==0 페이지는 즉시 break — 인위적 상한 없이 반드시 끝난다.
            while (!candidates.isEmpty()) {
                PageResult result = fixPage.apply(candidates.stream().limit(pageSize).toList());
                created += result.created();
                skipped += result.skipped();
                pages++;
                if (result.created() == 0) {
                    break; // 고칠 수 없는 후보만 남음 — 탐지가 더 줄지 않으므로 종결.
                }
                candidates = detect.apply(date);
            }
            // 종결 시점 잔여 — 마지막 페이지가 정정 없이 끝났으면 그 탐지 목록이 곧 잔여다(재탐지 불필요).
            remaining += candidates.size();
        }
        return BackfillReport.of(created, skipped, remaining, pages);
    }

    /** 확정 배선(SettlementConfirmItemWriter)과 동일한 경로로 즉시지급 Payout 1건을 멱등 생성한다. */
    private PageResult createImmediatePayout(List<Long> settlementIds) {
        int created = 0;
        int skipped = 0;
        for (Long settlementId : settlementIds) {
            boolean made;
            try {
                made = loadSettlementPort.findById(settlementId)
                        .flatMap(settlement -> loadSellerIdPort.findSellerIdByPaymentId(settlement.getPaymentId())
                                .flatMap(sellerId -> requestPayoutUseCase.requestPayoutOfType(
                                        settlementId, sellerId, settlement.getImmediatePayoutAmount(),
                                        PayoutType.IMMEDIATE)))
                        .isPresent();
            } catch (PayoutConcurrentClaimException e) {
                // 확정 배치가 같은 (정산, IMMEDIATE) 를 먼저 생성한 정상 경합 — 이중 지급이 아니라
                // 멱등 키가 방어한 결과이므로 실행을 중단시키지 않고 건너뛴다(다음 탐지에서 탈락).
                log.warn("[Backfill] payout 경합 skip: settlementId={}", settlementId);
                made = false;
            }
            if (made) {
                created++;
            } else {
                skipped++;
            }
        }
        return new PageResult(created, skipped);
    }

    /** 조정 행의 출처(환불·차지백·PG대사)별 역분개를 멱등 적재한다 — 이미 있으면 빈 반환 → skipped. */
    private PageResult createReverseEntriesForPage(List<Long> adjustmentIds) {
        int created = 0;
        int skipped = 0;
        for (AdjustmentReversalTarget target : targetPort.loadAdjustmentTargets(adjustmentIds)) {
            try {
                List<?> rows = reverseEntryUseCase.reverseForReference(target.settlementId(), target.referenceId(),
                        target.referenceType(), target.amount(), target.adjustmentDate());
                if (rows.isEmpty()) {
                    skipped++;
                } else {
                    created++;
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                // poison-pill 조정(금액 0·payment 초과 등 검증 거절) 1건이 전체 백필을 중단시키지 않도록
                // 아이템 단위로 격리한다 — 해당 건은 탐지에 계속 남아 remaining 으로 보고된다.
                log.warn("[Backfill] 역분개 검증 거절 skip: adjustmentId={}, reason={}",
                        target.adjustmentId(), e.getMessage());
                skipped++;
            }
        }
        return new PageResult(created, skipped);
    }

    private void audit(AuditAction action, LocalDate from, LocalDate to, BackfillReport report) {
        String detail = String.format("{\"created\":%d,\"skipped\":%d,\"remaining\":%d,\"pages\":%d}",
                report.created(), report.skipped(), report.remaining(), report.pagesProcessed());
        auditLogger.record(action, "SETTLEMENT_BACKFILL", from + "~" + to, detail);
    }

    private record PageResult(int created, int skipped) {
    }
}
