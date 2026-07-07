package github.lms.lemuel.ai.chat.application.service;

import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.port.in.ConversationQuery;
import github.lms.lemuel.ai.chat.application.port.out.LoadConversationPort;
import github.lms.lemuel.ai.chat.application.port.out.SaveConversationPort;
import github.lms.lemuel.ai.chat.domain.Conversation;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 대화 이력 조회·삭제 — 모든 연산은 요청자 소유 대화로 한정(타인 것은 404). */
@Service
public class ConversationQueryService implements ConversationQuery {

    private final LoadConversationPort loadConversationPort;
    private final SaveConversationPort saveConversationPort;

    public ConversationQueryService(LoadConversationPort loadConversationPort,
                                    SaveConversationPort saveConversationPort) {
        this.loadConversationPort = loadConversationPort;
        this.saveConversationPort = saveConversationPort;
    }

    @Override
    public ConversationList list(Long userId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return new ConversationList(
                loadConversationPort.listByUser(userId, safePage, safeSize),
                safePage, safeSize,
                loadConversationPort.countByUser(userId));
    }

    @Override
    public ConversationDetail get(Long userId, UUID conversationId) {
        Conversation conversation = loadConversationPort.findOwned(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        return new ConversationDetail(conversation, loadConversationPort.findAllMessages(conversationId));
    }

    @Override
    public void delete(Long userId, UUID conversationId) {
        if (!saveConversationPort.delete(conversationId, userId)) {
            throw new ConversationNotFoundException(conversationId);
        }
    }
}
