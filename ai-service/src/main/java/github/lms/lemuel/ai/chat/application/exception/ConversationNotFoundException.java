package github.lms.lemuel.ai.chat.application.exception;

import java.util.UUID;

/** 대화가 없거나 요청자 소유가 아님 — 타인 대화는 존재 자체를 숨긴다(404). */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(UUID conversationId) {
        super("대화를 찾을 수 없습니다: " + conversationId);
    }
}
