package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerTrialBalance;

import java.time.YearMonth;

/**
 * 기간별 확정 시산표 조회 유스케이스 — read-only. 마감 여부와 무관하게 해당 월의 현재 시산표를 산출한다.
 */
public interface GetLedgerTrialBalanceUseCase {

    LedgerTrialBalance getForPeriod(YearMonth period);
}
