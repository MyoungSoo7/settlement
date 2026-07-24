package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.AccountType;

import java.time.LocalDate;
import java.util.Map;

/**
 * 기간별 확정 시산표 집계 아웃포트 — {@code settlement_date} 범위의 <b>POSTED</b> 분개를
 * 계정과목별 차/대 합계로 집계한다(DB GROUP BY). 반환 맵은 활동이 있는 계정만 포함한다.
 */
public interface LoadLedgerTrialBalancePort {

    /** [from, to] 구간 POSTED 분개의 차변계정별 amount 합계. */
    Map<AccountType, java.math.BigDecimal> sumPostedDebitByAccount(LocalDate from, LocalDate to);

    /** [from, to] 구간 POSTED 분개의 대변계정별 amount 합계. */
    Map<AccountType, java.math.BigDecimal> sumPostedCreditByAccount(LocalDate from, LocalDate to);
}
