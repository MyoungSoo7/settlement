package github.lms.lemuel.ledger.application.service;

import github.lms.lemuel.ledger.application.port.in.GetLedgerTrialBalanceUseCase;
import github.lms.lemuel.ledger.application.port.out.LoadLedgerTrialBalancePort;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerTrialBalance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * 기간별 확정 시산표 조회 서비스 — 월 경계([1일, 말일])의 POSTED 분개를 계정별 차/대로 집계한다.
 *
 * <p>기간 경계: {@code period.atDay(1)} ~ {@code period.atEndOfMonth()} (양끝 포함, {@code settlement_date} 기준).
 * 집계는 아웃포트(DB GROUP BY)가, 조립·균형 계산은 도메인 값 객체 {@link LedgerTrialBalance}가 담당한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerTrialBalanceService implements GetLedgerTrialBalanceUseCase {

    private final LoadLedgerTrialBalancePort loadPort;

    @Override
    public LedgerTrialBalance getForPeriod(YearMonth period) {
        if (period == null) {
            throw new IllegalArgumentException("period 필수");
        }
        LocalDate from = period.atDay(1);
        LocalDate to = period.atEndOfMonth();

        Map<AccountType, BigDecimal> debit = loadPort.sumPostedDebitByAccount(from, to);
        Map<AccountType, BigDecimal> credit = loadPort.sumPostedCreditByAccount(from, to);

        return LedgerTrialBalance.of(period, debit, credit);
    }
}
