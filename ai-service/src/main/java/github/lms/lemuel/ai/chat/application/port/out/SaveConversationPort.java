package github.lms.lemuel.ai.chat.application.port.out;

import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;

import java.util.UUID;

/** 대화 영속화 아웃바운드 포트. */
public interface SaveConversationPort {

    /**
     * 대화 메타(생성 또는 갱신) + 사용자/어시스턴트 메시지 2건을 <b>단일 트랜잭션</b>으로 저장한다.
     * LLM 호출 성공 후에만 불린다 — 실패한 왕복은 이력에 흔적을 남기지 않는다(설계 §2.4).
     */
    void saveExchange(Conversation conversation, ChatMessage userMessage, ChatMessage assistantMessage);

    /** 소유자 일치 시 대화 삭제(메시지는 DB CASCADE). 삭제됐으면 true. */
    boolean delete(UUID conversationId, Long userId);
}
