package github.lms.lemuel.ai.chat.adapter.in.web.dto;

import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatResult;
import github.lms.lemuel.ai.chat.application.port.in.ConversationQuery.ConversationDetail;
import github.lms.lemuel.ai.chat.application.port.in.ConversationQuery.ConversationList;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** /api/ai/** 요청·응답 DTO 모음. */
public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatRequest(
            UUID conversationId,
            @NotBlank(message = "message 는 비어 있을 수 없습니다")
            @Size(max = 4000, message = "message 는 4000자 이하여야 합니다")
            String message
    ) {
    }

    public record UsageResponse(Integer inputTokens, Integer outputTokens) {
    }

    public record ChatResponse(UUID conversationId, String reply, String model, UsageResponse usage) {

        public static ChatResponse from(ChatResult result) {
            return new ChatResponse(result.conversationId(), result.reply(), result.model(),
                    new UsageResponse(result.inputTokens(), result.outputTokens()));
        }
    }

    public record ConversationSummaryResponse(UUID id, String title, int messageCount,
                                              Instant lastMessageAt, Instant createdAt) {

        public static ConversationSummaryResponse from(Conversation c) {
            return new ConversationSummaryResponse(c.id(), c.title(), c.messageCount(),
                    c.lastMessageAt(), c.createdAt());
        }
    }

    public record ConversationListResponse(List<ConversationSummaryResponse> content,
                                           int page, int size, long totalElements) {

        public static ConversationListResponse from(ConversationList list) {
            return new ConversationListResponse(
                    list.content().stream().map(ConversationSummaryResponse::from).toList(),
                    list.page(), list.size(), list.totalElements());
        }
    }

    public record MessageResponse(String role, String content, String model,
                                  Integer inputTokens, Integer outputTokens, Instant createdAt) {

        public static MessageResponse from(ChatMessage m) {
            return new MessageResponse(m.role().name(), m.content(), m.model(),
                    m.inputTokens(), m.outputTokens(), m.createdAt());
        }
    }

    public record ConversationDetailResponse(UUID id, String title, Instant createdAt,
                                             List<MessageResponse> messages) {

        public static ConversationDetailResponse from(ConversationDetail detail) {
            return new ConversationDetailResponse(
                    detail.conversation().id(), detail.conversation().title(),
                    detail.conversation().createdAt(),
                    detail.messages().stream().map(MessageResponse::from).toList());
        }
    }

    public record ErrorResponse(String message) {
    }

    /**
     * SSE 스트림 도중 실패 시 error 이벤트 페이로드.
     * code 로 클라이언트가 사유(레이트리밋/미구성/미존재/일반)를 구분한다 —
     * HTTP 상태(이미 200)가 소실되는 스트리밍 경로의 대체 신호.
     * message 는 화이트리스트된 안전 문구만 담는다(원본 예외 메시지 노출 금지).
     */
    public record StreamError(String code, String message) {
    }
}
