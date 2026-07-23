package github.lms.lemuel.ledger.adapter.in.web.response;

import github.lms.lemuel.ledger.domain.LedgerTrialBalance;

import java.math.BigDecimal;
import java.util.List;

/**
 * 기간별 확정 시산표 응답 — 계정과목별 차/대/순액 라인 + 총합 + 균형 여부.
 */
public record LedgerTrialBalanceResponse(
        String periodYm,
        List<Line> lines,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean balanced
) {
    public record Line(String account, BigDecimal debit, BigDecimal credit, BigDecimal net) {
    }

    public static LedgerTrialBalanceResponse from(LedgerTrialBalance tb) {
        List<Line> lines = tb.getLines().stream()
                .map(l -> new Line(l.account().name(), l.debit(), l.credit(), l.net()))
                .toList();
        return new LedgerTrialBalanceResponse(
                tb.getPeriodYm(), lines, tb.getTotalDebit(), tb.getTotalCredit(), tb.isBalanced());
    }
}
