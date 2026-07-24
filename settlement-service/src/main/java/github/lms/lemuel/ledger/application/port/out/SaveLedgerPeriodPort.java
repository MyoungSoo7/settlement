package github.lms.lemuel.ledger.application.port.out;

import github.lms.lemuel.ledger.domain.LedgerPeriod;

/**
 * 원장 기간(월) 저장 아웃포트 — 마감 스냅샷 못박기(신규 OPEN 생성 또는 CLOSED 전이 반영).
 */
public interface SaveLedgerPeriodPort {

    LedgerPeriod save(LedgerPeriod period);
}
