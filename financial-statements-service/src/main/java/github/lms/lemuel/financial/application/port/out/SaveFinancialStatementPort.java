package github.lms.lemuel.financial.application.port.out;

import github.lms.lemuel.financial.domain.FinancialStatement;

public interface SaveFinancialStatementPort {

    /** (종목코드, 사업연도, 재무제표구분) 기준 upsert — SEED 행을 DART 실데이터가 덮어쓴다. */
    void upsert(FinancialStatement statement);
}
