package github.lms.lemuel.account.adapter.in.web.dto;

import github.lms.lemuel.account.application.port.in.OwnerAccountQuery.EntryPage;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 분개 페이지 응답.
 */
public record EntryPageResponse(
        List<EntryView> content,
        long totalElements,
        int page,
        int size) {

    public record EntryView(
            Long id,
            GlAccount debitAccount,
            GlAccount creditAccount,
            BigDecimal amount,
            String refType,
            String refId,
            LocalDateTime occurredAt) { }

    public static EntryPageResponse from(EntryPage page) {
        List<EntryView> content = page.content().stream()
                .map(EntryPageResponse::toView)
                .toList();
        return new EntryPageResponse(content, page.totalElements(), page.page(), page.size());
    }

    private static EntryView toView(AccountEntry e) {
        return new EntryView(e.getId(), e.getDebitAccount(), e.getCreditAccount(), e.getAmount(),
                e.getRefType(), e.getRefId(), e.getOccurredAt());
    }
}
