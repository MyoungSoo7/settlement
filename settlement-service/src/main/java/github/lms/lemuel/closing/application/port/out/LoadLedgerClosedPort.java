package github.lms.lemuel.closing.application.port.out;

import java.time.YearMonth;

/**
 * 원장 월마감(ledger_periods CLOSED) 여부 조회 — 정보계 재마감 잠금 판단용.
 *
 * <p>ledger 모듈 코드에 의존하지 않고 자기 어댑터가 {@code ledger_periods} 테이블을 직접 읽는다
 * (모듈 간 결합 대신 DB 레벨 공유 — report 모듈과 같은 방식).
 */
public interface LoadLedgerClosedPort {

    boolean isLedgerClosed(YearMonth period);
}
