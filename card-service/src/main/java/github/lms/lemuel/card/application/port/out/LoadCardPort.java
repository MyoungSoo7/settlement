package github.lms.lemuel.card.application.port.out;

import github.lms.lemuel.card.domain.Card;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 카드 조회 포트. */
public interface LoadCardPort {

    /** 단순 조회 — 락 없음. */
    Optional<Card> findById(Long id);

    /** 특정 카드계정에 속한 카드 전체(상태 무관). */
    List<Card> findByCardAccountId(Long cardAccountId);

    /**
     * 한 임직원이 보유한 카드 전체 — "내 카드" 조회 전용. 카드계정을 가로지른다(한 사람이
     * 여러 법인에 소속될 수 있다). CANCELED 도 포함한다 — 해지 이력은 본인에게 숨길 이유가 없고,
     * 오히려 "왜 안 되는지"를 설명하는 화면이 필요하다.
     */
    List<Card> findByHolderUserId(Long holderUserId);

    /**
     * 임직원의 "활성 슬롯 점유자" 조회 — {@code status <> CANCELED} 인 카드.
     * uq_card_active_holder 부분 유니크 인덱스와 동일한 판정 기준이다(WHERE status &lt;&gt; 'CANCELED').
     * CANCELED 카드는 슬롯을 비우므로 재발급 가능 여부 사전 검증에 이 메서드를 쓴다.
     */
    Optional<Card> findActiveByHolder(Long cardAccountId, Long holderUserId);

    /**
     * 카드계정의 활성 서브한도 합계 — {@code status <> CANCELED} 기준(SUSPENDED 포함).
     *
     * <p>SUSPENDED 카드를 합계에서 빼지 않는 이유: 정지는 일시적이고 재개(resume)되면 그 한도를
     * 다시 쓰게 되므로, 정지 중이라고 합계에서 제외하면 그 사이 다른 카드에 남는 한도가
     * 잘못 배분되어(over-allocate) 재개 시 마스터 한도 불변식이 깨질 수 있다. 오직 CANCELED(터미널)만
     * 합계에서 제외한다.
     */
    BigDecimal sumActiveSubLimits(Long cardAccountId);
}
