package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.LedgerPeriod;

import java.time.YearMonth;
import java.util.Optional;

/**
 * 원장 기간(월) 조회 아웃포트.
 *
 * <p>기간 행이 없으면 암묵적으로 OPEN 이다 — {@link #isClosed}는 CLOSED 로 저장된 행이 있을 때만 true.
 */
public interface LoadLedgerPeriodPort {

    Optional<LedgerPeriod> findByPeriod(YearMonth period);

    /** 해당 월이 마감(CLOSED)되었는지 — 행이 없으면 false(암묵적 OPEN). */
    boolean isClosed(YearMonth period);
}
