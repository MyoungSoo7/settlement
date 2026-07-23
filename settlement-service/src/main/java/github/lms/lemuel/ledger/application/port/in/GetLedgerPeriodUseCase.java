package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerPeriod;

import java.time.YearMonth;

/**
 * 원장 기간(월) 상태 조회 유스케이스 — read-only.
 *
 * <p>기간 행이 없으면 암묵적 OPEN 상태의 (미영속) {@link LedgerPeriod} 를 반환한다(null 반환 없음).
 */
public interface GetLedgerPeriodUseCase {

    LedgerPeriod getStatus(YearMonth period);
}
