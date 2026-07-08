package github.lms.lemuel.ai.chat.application.port.out;

import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 대화 조회 아웃바운드 포트. 소유자(userId) 조건이 모든 단건 조회에 포함된다. */
public interface LoadConversationPort {

    Optional<Conversation> findOwned(UUID conversationId, Long userId);

    /** 최근 N개 메시지를 시간순(오래된 것부터)으로 반환 — LLM 컨텍스트 윈도 구성용. */
    List<ChatMessage> findRecentMessages(UUID conversationId, int limit);

    /** 대화 전체 메시지 시간순 — 이력 화면용. */
    List<ChatMessage> findAllMessages(UUID conversationId);

    List<Conversation> listByUser(Long userId, int page, int size);

    long countByUser(Long userId);
}
