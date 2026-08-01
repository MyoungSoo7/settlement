package github.lms.lemuel.card.adapter.in.web.dto;

import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardStatus;

import java.math.BigDecimal;

/** 임직원 카드 응답. 번호는 도메인이 이미 마스킹된 값만 보유하므로 그대로 내보내도 안전하다. */
public record CardResponse(
        Long id,
        Long cardAccountId,
        Long holderUserId,
        String maskedCardNo,
        BigDecimal subLimit,
        CardStatus status) {

    public static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                card.getCardAccountId(),
                card.getHolderUserId(),
                card.getMaskedCardNo(),
                card.getSubLimit(),
                card.getStatus());
    }
}
