package github.lms.lemuel.ledger.adapter.out.persistence;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerTrialBalancePort;
import github.lms.lemuel.ledger.domain.AccountType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 기간 확정 시산표 집계 어댑터 — POSTED 분개를 계정과목별 차/대로 GROUP BY 집계한다.
 *
 * <p>계정과목은 {@code ledger_entries.debit_account}/{@code credit_account} 에 {@link AccountType} 이름
 * 문자열로 저장되므로, 집계 결과를 {@link AccountType} 키 맵으로 역직렬화해 반환한다.
 */
@Repository
@RequiredArgsConstructor
public class LedgerTrialBalancePersistenceAdapter implements LoadLedgerTrialBalancePort {

    private final SpringDataLedgerJpaRepository repository;

    @Override
    public Map<AccountType, BigDecimal> sumPostedDebitByAccount(LocalDate from, LocalDate to) {
        return toMap(repository.sumPostedDebitByAccount(from, to));
    }

    @Override
    public Map<AccountType, BigDecimal> sumPostedCreditByAccount(LocalDate from, LocalDate to) {
        return toMap(repository.sumPostedCreditByAccount(from, to));
    }

    private Map<AccountType, BigDecimal> toMap(List<Object[]> rows) {
        Map<AccountType, BigDecimal> out = new EnumMap<>(AccountType.class);
        for (Object[] row : rows) {
            AccountType account = AccountType.valueOf((String) row[0]);
            BigDecimal sum = (BigDecimal) row[1];
            out.put(account, sum != null ? sum : BigDecimal.ZERO);
        }
        return out;
    }
}
