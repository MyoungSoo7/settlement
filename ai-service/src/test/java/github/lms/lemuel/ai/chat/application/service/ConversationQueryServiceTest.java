package github.lms.lemuel.ai.chat.application.service;

import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.port.in.ConversationQuery.ConversationDetail;
import github.lms.lemuel.ai.chat.application.port.in.ConversationQuery.ConversationList;
import github.lms.lemuel.ai.chat.application.port.out.LoadConversationPort;
import github.lms.lemuel.ai.chat.application.port.out.SaveConversationPort;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final Long USER_ID = 42L;

    @Mock LoadConversationPort loadConversationPort;
    @Mock SaveConversationPort saveConversationPort;

    @InjectMocks ConversationQueryService service;

    @Test
    @DisplayName("list — 페이지 파라미터를 안전 범위로 보정한다")
    void list_clampsParameters() {
        when(loadConversationPort.listByUser(USER_ID, 0, 100)).thenReturn(List.of());
        when(loadConversationPort.countByUser(USER_ID)).thenReturn(0L);

        ConversationList result = service.list(USER_ID, -1, 999);

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("get — 소유 대화면 메시지 전체와 함께 반환한다")
    void get_owned() {
        UUID id = UUID.randomUUID();
        Conversation conversation = Conversation.restore(id, USER_ID, "제목", 2, NOW, NOW);
        when(loadConversationPort.findOwned(id, USER_ID)).thenReturn(Optional.of(conversation));
        when(loadConversationPort.findAllMessages(id)).thenReturn(List.of(ChatMessage.user("질문", NOW)));

        ConversationDetail detail = service.get(USER_ID, id);

        assertThat(detail.conversation().id()).isEqualTo(id);
        assertThat(detail.messages()).hasSize(1);
    }

    @Test
    @DisplayName("get/delete — 없는(또는 타인) 대화는 404 예외")
    void notFound() {
        UUID id = UUID.randomUUID();
        when(loadConversationPort.findOwned(id, USER_ID)).thenReturn(Optional.empty());
        when(saveConversationPort.delete(id, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.get(USER_ID, id)).isInstanceOf(ConversationNotFoundException.class);
        assertThatThrownBy(() -> service.delete(USER_ID, id)).isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("delete — 소유 대화 삭제 성공")
    void delete_owned() {
        UUID id = UUID.randomUUID();
        when(saveConversationPort.delete(id, USER_ID)).thenReturn(true);

        service.delete(USER_ID, id);   // 예외 없음
    }
}
