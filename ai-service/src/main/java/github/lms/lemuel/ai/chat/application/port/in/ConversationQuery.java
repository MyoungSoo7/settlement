package github.lms.lemuel.ai.chat.application.port.in;

import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;

import java.util.List;
import java.util.UUID;

/**
 * 대화 이력 조회·삭제 인바운드 포트.
 *
 * <p>모든 연산은 요청자(userId) 소유의 대화로 한정한다 — 타인 대화는 존재 자체를 숨긴다(404).
 */
public interface ConversationQuery {

    ConversationList list(Long userId, int page, int size);

    ConversationDetail get(Long userId, UUID conversationId);

    void delete(Long userId, UUID conversationId);

    record ConversationList(List<Conversation> content, int page, int size, long totalElements) {
    }

    record ConversationDetail(Conversation conversation, List<ChatMessage> messages) {
    }
}
