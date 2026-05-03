package github.lms.lemuel.settlement.application.port.in;

import github.lms.lemuel.settlement.domain.ReconciliationReport;

import java.time.LocalDate;

/**
 * 일일 대사(이중장부) 유스케이스.
 * 결제/환불/정산 총액을 교차 검증해 리포트를 돌려준다.
 */
public interface ReconcileDailyTotalsUseCase {

    ReconciliationReport reconcile(LocalDate targetDate);
}
