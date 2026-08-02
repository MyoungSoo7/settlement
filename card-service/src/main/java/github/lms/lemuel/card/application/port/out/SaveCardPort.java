package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.Card;

/** 카드 저장 포트. */
public interface SaveCardPort {

    /** 신규(id null)면 INSERT, 기존이면 @Version 낙관적 락 갱신. 영속 id 가 채워진 카드를 반환. */
    Card save(Card card);
}
