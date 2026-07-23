package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.CloseLedgerPeriodUseCase;
import github.lms.lemuel.ledger.application.port.in.GetLedgerTrialBalanceUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerPeriodPort;
import github.lms.lemuel.ledger.application.port.out.SaveLedgerPeriodPort;
import github.lms.lemuel.ledger.domain.LedgerPeriod;
import github.lms.lemuel.ledger.domain.LedgerTrialBalance;
import github.lms.lemuel.ledger.domain.exception.LedgerPeriodImbalanceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

/**
 * 원장 기간(월) 마감 서비스(관리자).
 *
 * <p>절차:
 * <ol>
 *   <li>이미 CLOSED 인 기간이면 기존 스냅샷을 그대로 반환한다(<b>멱등 no-op</b>) — 시산표 재산출/재저장 없음.</li>
 *   <li>해당 기간의 확정 시산표를 산출한다({@link GetLedgerTrialBalanceUseCase}).</li>
 *   <li>차대 균형을 확인한다 — 불균형이면 {@link LedgerPeriodImbalanceException} 로 마감을 거부한다.</li>
 *   <li>{@link LedgerPeriod} 를 OPEN 으로 열고 CLOSED 로 전이하며 합계 스냅샷을 못박아 저장한다.</li>
 * </ol>
 *
 * <p>기간 잠금 자체는 {@link LedgerPeriodGuard} 가 분개 생성 초크포인트에서 강제한다 — 본 서비스는
 * 마감(상태 전이 + 스냅샷)만 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloseLedgerPeriodService implements CloseLedgerPeriodUseCase {

    private final GetLedgerTrialBalanceUseCase trialBalanceUseCase;
    private final LoadLedgerPeriodPort loadPeriodPort;
    private final SaveLedgerPeriodPort savePeriodPort;

    @Override
    @Transactional
    public LedgerPeriod close(YearMonth period, String closedBy) {
        if (period == null) {
            throw new IllegalArgumentException("period 필수");
        }
        if (closedBy == null || closedBy.isBlank()) {
            throw new IllegalArgumentException("closedBy 필수");
        }

        // 1) 멱등 — 이미 마감된 기간은 기존 스냅샷 그대로 반환(no-op).
        LedgerPeriod existing = loadPeriodPort.findByPeriod(period).orElse(null);
        if (existing != null && existing.isClosed()) {
            log.info("Ledger period {} already CLOSED; idempotent no-op", period);
            return existing;
        }

        // 2) 확정 시산표 산출.
        LedgerTrialBalance trialBalance = trialBalanceUseCase.getForPeriod(period);

        // 3) 균형 확인 — 불균형이면 마감 거부.
        if (!trialBalance.isBalanced()) {
            throw new LedgerPeriodImbalanceException(
                    period, trialBalance.getTotalDebit(), trialBalance.getTotalCredit());
        }

        // 4) OPEN → CLOSED 전이 + 합계 스냅샷 저장.
        LedgerPeriod toClose = existing != null ? existing : LedgerPeriod.open(period);
        toClose.close(closedBy, trialBalance.getTotalDebit(), trialBalance.getTotalCredit());
        LedgerPeriod saved = savePeriodPort.save(toClose);

        log.info("Ledger period CLOSED: period={}, closedBy={}, totalDebit={}, totalCredit={}",
                period, closedBy, saved.getTotalDebit(), saved.getTotalCredit());
        return saved;
    }
}
