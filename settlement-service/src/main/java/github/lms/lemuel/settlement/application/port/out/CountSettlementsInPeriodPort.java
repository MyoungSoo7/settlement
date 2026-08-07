package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.domain.RateScope;

import java.time.LocalDate;

/** 소급 판정용 — 해당 scope·기간에 이미 생성된 정산 건수. */
public interface CountSettlementsInPeriodPort {

    long countInPeriod(RateScope scope, String scopeKey, LocalDate from, LocalDate to);
}
