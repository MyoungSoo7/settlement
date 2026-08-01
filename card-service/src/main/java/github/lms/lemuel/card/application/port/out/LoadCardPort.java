package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.Card;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 임직원 카드 조회 포트.
 */
public interface LoadCardPort {

    Optional<Card> findById(Long id);

    List<Card> findByCardAccountId(Long cardAccountId);

    /** 활성(CANCELED 아님) 카드 — 임직원당 1장(uq_card_active_holder)과 같은 기준. */
    Optional<Card> findActiveByHolder(Long cardAccountId, Long holderUserId);

    /**
     * 활성(CANCELED 아님) 카드의 서브한도 합계. <b>SUSPENDED 는 포함한다</b> —
     * 정지는 일시적이라 재개되면 그 한도를 다시 쓰므로, 빼면 그 사이 다른 카드에
     * 배분된 한도와 충돌한다.
     */
    BigDecimal sumActiveSubLimits(Long cardAccountId);
}
