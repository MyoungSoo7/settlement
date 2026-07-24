package github.lms.lemuel.ledger.application.port.in;

import github.lms.lemuel.ledger.domain.LedgerPeriod;

import java.time.YearMonth;

/**
 * 원장 기간(월) 마감 유스케이스(관리자).
 *
 * <p>절차: 기간 확정 시산표 산출 → 차대 균형 확인 → {@link LedgerPeriod} CLOSED 전이 + 합계 스냅샷 저장.
 * <b>멱등</b>: 이미 CLOSED 인 기간을 재마감 요청하면 기존 마감 스냅샷을 그대로 반환한다(no-op).
 */
public interface CloseLedgerPeriodUseCase {

    LedgerPeriod close(YearMonth period, String closedBy);
}
