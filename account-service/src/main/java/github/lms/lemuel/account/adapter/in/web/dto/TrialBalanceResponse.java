package github.lms.lemuel.account.adapter.in.web.dto;

import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.TrialBalance;

import java.math.BigDecimal;
import java.util.List;

/**
 * 시산표 응답.
 */
public record TrialBalanceResponse(
        List<LineView> accounts,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean balanced,
        boolean normalBalanceRespected) {

    public record LineView(GlAccount account, BigDecimal debitTotal, BigDecimal creditTotal) { }

    public static TrialBalanceResponse from(TrialBalance tb) {
        List<LineView> accounts = tb.lines().stream()
                .map(l -> new LineView(l.account(), l.debitTotal(), l.creditTotal()))
                .toList();
        return new TrialBalanceResponse(accounts, tb.totalDebit(), tb.totalCredit(),
                tb.balanced(), tb.normalBalanceRespected());
    }
}
