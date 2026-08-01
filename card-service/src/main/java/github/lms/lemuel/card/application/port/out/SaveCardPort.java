package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.Card;

/**
 * 임직원 카드 저장 포트 — 발급·정지·재개·해지·서브한도 변경의 영속화.
 */
public interface SaveCardPort {

    Card save(Card card);
}
