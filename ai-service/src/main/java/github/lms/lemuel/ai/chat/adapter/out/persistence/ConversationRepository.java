package github.lms.lemuel.ai.chat.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationJpaEntity, UUID> {

    Optional<ConversationJpaEntity> findByIdAndUserId(UUID id, Long userId);

    Page<ConversationJpaEntity> findByUserIdOrderByLastMessageAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    long deleteByIdAndUserId(UUID id, Long userId);

    /**
     * 기존 대화에 한 왕복(메시지 {@code delta}건)을 원자적으로 반영한다 —
     * {@code message_count = message_count + delta} DB 레벨 증가로 동시 왕복 시에도
     * 로스트 업데이트 없이 정확하다(낙관적 락+재시도 없이 카운트 정합 보장).
     * 벌크 UPDATE 는 {@code @PreUpdate} 를 우회하므로 {@code updated_at} 도 명시 갱신한다.
     *
     * @return 갱신된 행 수(0 이면 신규 대화 → 호출측이 INSERT)
     */
    @Modifying
    @Query("""
            UPDATE ConversationJpaEntity c
               SET c.messageCount = c.messageCount + :delta,
                   c.lastMessageAt = :lastMessageAt,
                   c.updatedAt = :updatedAt
             WHERE c.id = :id
            """)
    int incrementExchange(@Param("id") UUID id,
                          @Param("delta") int delta,
                          @Param("lastMessageAt") Instant lastMessageAt,
                          @Param("updatedAt") Instant updatedAt);
}
