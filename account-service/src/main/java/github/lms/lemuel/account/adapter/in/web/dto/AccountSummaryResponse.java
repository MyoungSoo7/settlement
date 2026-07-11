package github.lms.lemuel.account.adapter.in.web.dto;

import github.lms.lemuel.account.domain.AccountSide;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;

import java.math.BigDecimal;
import java.util.List;

/**
 * owner 계정 잔액 요약 응답.
 */
public record AccountSummaryResponse(
        OwnerType ownerType,
        String ownerId,
        List<BalanceView> balances,
        int entryCount) {

    public record BalanceView(GlAccount account, AccountSide side, BigDecimal balance) { }

    public static AccountSummaryResponse from(AccountSummary summary) {
        List<BalanceView> balances = summary.balances().stream()
                .map(b -> new BalanceView(b.account(), b.side(), b.balance()))
                .toList();
        return new AccountSummaryResponse(summary.ownerType(), summary.ownerId(), balances, summary.entryCount());
    }
}
