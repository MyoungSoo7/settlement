package github.lms.lemuel.financial.application.port.in;

import github.lms.lemuel.financial.domain.FinancialStatement;

import java.util.List;

/** 기업별 연도 구간 요약 재무제표 조회. */
public interface GetFinancialStatementsUseCase {

    /**
     * @param fromYear null 이면 하한 없음
     * @param toYear   null 이면 상한 없음
     * @throws java.util.NoSuchElementException 종목코드에 해당하는 기업이 없을 때
     */
    List<FinancialStatement> byCompany(String stockCode, Integer fromYear, Integer toYear);
}
