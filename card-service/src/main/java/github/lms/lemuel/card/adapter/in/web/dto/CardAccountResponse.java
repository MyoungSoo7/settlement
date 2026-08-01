package github.lms.lemuel.card.adapter.in.web.dto;

import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;

import java.math.BigDecimal;

/**
 * 카드계정 응답. 한도와 함께 <b>산정 근거</b>(재원·인정비율·평판등급)를 노출한다 —
 * "왜 이 한도인가"에 답할 수 없는 여신 화면은 그 자체로 CS 비용이다.
 */
public record CardAccountResponse(
        Long id,
        Long organizationId,
        String sellerId,
        CardAccountStatus status,
        BigDecimal masterLimit,
        String reputationGrade,
        BigDecimal sellerPayable,
        BigDecimal holdbackPayable,
        BigDecimal appliedRatio,
        String rejectReason) {

    public static CardAccountResponse from(CardAccount account) {
        LimitSnapshot snapshot = account.getLimitSnapshot();
        return new CardAccountResponse(
                account.getId(),
                account.getOrganizationId(),
                account.getSellerId(),
                account.getStatus(),
                account.getMasterLimit(),
                snapshot == null ? null : snapshot.reputationGrade().name(),
                snapshot == null ? null : snapshot.sellerPayable(),
                snapshot == null ? null : snapshot.holdbackPayable(),
                snapshot == null ? null : snapshot.appliedRatio(),
                account.getRejectReason());
    }
}
