package github.lms.lemuel.rag.application.service;

import github.lms.lemuel.rag.application.port.in.RagQueryUseCase;
import github.lms.lemuel.rag.application.port.out.ChatPort;
import github.lms.lemuel.rag.application.port.out.ConversationPort;
import github.lms.lemuel.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.rag.application.port.out.VectorSearchPort;
import github.lms.lemuel.rag.domain.Conversation;
import github.lms.lemuel.rag.domain.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagQueryService implements RagQueryUseCase {

    private final EmbeddingPort embeddingPort;
    private final VectorSearchPort vectorSearchPort;
    private final ChatPort chatPort;
    private final ConversationPort conversationPort;
    private final RagProperties ragProperties;

    @Override
    public void query(String sessionId, String question, Consumer<String> tokenConsumer) {
        // 1. 이전 대화 로드
        Conversation conversation = conversationPort.getConversation(sessionId);

        // 2. 질문 임베딩
        float[] queryEmbedding = embeddingPort.embed(question);

        // 3. 유사 문서 검색
        List<DocumentChunk> relevantDocs = vectorSearchPort.searchSimilar(
                queryEmbedding, ragProperties.getMaxResults(), ragProperties.getSimilarityThreshold());

        // 4. 컨텍스트 구성
        String context = relevantDocs.stream()
                .map(doc -> String.format("[%s] (유사도: %.2f) %s",
                        doc.getEntityType(), doc.getSimilarity(), doc.getContent()))
                .collect(Collectors.joining("\n"));

        // 5. 시스템 프롬프트 + 컨텍스트
        String systemPrompt = ragProperties.getSystemPrompt() + "\n\n### 참고 데이터:\n" + context;

        // 6. 대화 히스토리를 ChatMessage로 변환
        List<ChatPort.ChatMessage> chatMessages = new ArrayList<>();
        for (Conversation.Message msg : conversation.getMessages()) {
            chatMessages.add(new ChatPort.ChatMessage(msg.getRole(), msg.getContent()));
        }
        chatMessages.add(new ChatPort.ChatMessage("USER", question));

        // 7. 사용자 메시지 저장
        conversationPort.saveMessage(sessionId, "USER", question);

        // 8. LLM 스트리밍 호출 + 응답 수집
        StringBuilder fullResponse = new StringBuilder();
        chatPort.streamChat(systemPrompt, chatMessages, token -> {
            fullResponse.append(token);
            tokenConsumer.accept(token);
        });

        // 9. AI 응답 저장
        conversationPort.saveMessage(sessionId, "ASSISTANT", fullResponse.toString());

        log.info("RAG 질의 완료: sessionId={}, question={}, relevantDocs={}",
                sessionId, question, relevantDocs.size());
    }
}
