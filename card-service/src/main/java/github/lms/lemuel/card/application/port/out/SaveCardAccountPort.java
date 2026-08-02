package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardAccount;

/** 카드계정 저장 포트. */
public interface SaveCardAccountPort {

    /** 신규(id null)면 INSERT, 기존이면 @Version 낙관적 락 갱신. 영속 id 가 채워진 카드계정을 반환. */
    CardAccount save(CardAccount account);
}
