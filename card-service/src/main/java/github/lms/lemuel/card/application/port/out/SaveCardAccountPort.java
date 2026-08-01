package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.CardAccount;

/**
 * 카드계정 저장 포트 — 생성·상태 전이·한도 변경의 영속화.
 */
public interface SaveCardAccountPort {

    CardAccount save(CardAccount account);
}
