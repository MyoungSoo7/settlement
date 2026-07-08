package github.lms.lemuel.financial.application.port.out;

import github.lms.lemuel.financial.domain.FinancialStatement;

import java.util.List;

public interface LoadFinancialStatementPort {

    /** 연도 내림차순. fromYear/toYear 는 null 허용(무제한). */
    List<FinancialStatement> findByCompany(String stockCode, Integer fromYear, Integer toYear);
}
