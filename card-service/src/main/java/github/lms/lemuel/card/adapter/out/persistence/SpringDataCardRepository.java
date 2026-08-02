package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.domain.CardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface SpringDataCardRepository extends JpaRepository<CardJpaEntity, Long> {

    List<CardJpaEntity> findByCardAccountId(Long cardAccountId);

    /** "내 카드" 조회 — 카드계정을 가로질러 한 임직원의 카드를 모은다(idx_card_holder). */
    List<CardJpaEntity> findByHolderUserId(Long holderUserId);

    /**
     * 임직원의 활성 슬롯 점유자 — status &lt;&gt; CANCELED. uq_card_active_holder 부분 유니크
     * 인덱스와 동일한 판정 기준이라, "재발급 가능한가"를 발급 전에 이 메서드로 선검증한다.
     */
    Optional<CardJpaEntity> findFirstByCardAccountIdAndHolderUserIdAndStatusNot(
            Long cardAccountId, Long holderUserId, CardStatus status);

    /**
     * 활성(≠CANCELED) 서브한도 합계. SUSPENDED 도 포함한다 — {@link
     * github.lms.lemuel.card.application.port.out.LoadCardPort#sumActiveSubLimits} 참조.
     * 문자열 리터럴 'CANCELED' 비교는 status 가 @Enumerated(STRING) 이라 Hibernate 가
     * CardStatus.CANCELED 로 해석한다(엔티티가 없으면 이 리포지토리를 결코 컴파일할 수 없다).
     */
    @Query("""
            select coalesce(sum(c.subLimit), 0)
              from CardJpaEntity c
             where c.cardAccountId = :cardAccountId
               and c.status <> 'CANCELED'
            """)
    BigDecimal sumActiveSubLimits(@Param("cardAccountId") Long cardAccountId);
}
