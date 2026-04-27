package github.lms.lemuel.report.application.port.out;

import github.lms.lemuel.report.domain.CashflowReport;

public interface RenderCashflowReportPdfPort {

    /**
     * Cashflow 리포트를 PDF 바이트 배열로 렌더링한다.
     * @param report 이미 완성된 도메인 리포트 (totals/buckets/reconciliation 포함)
     * @return PDF 바이너리
     */
    byte[] render(CashflowReport report);
}
