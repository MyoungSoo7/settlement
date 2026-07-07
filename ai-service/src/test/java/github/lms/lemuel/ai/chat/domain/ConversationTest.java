package github.lms.lemuel.ai.chat.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    @Test
    @DisplayName("start — 첫 메시지로 대화를 시작하면 제목이 파생되고 카운트 0으로 시작한다")
    void start() {
        Conversation conversation = Conversation.start(42L, "정산 주기가 어떻게 되나요?", NOW);

        assertThat(conversation.id()).isNotNull();
        assertThat(conversation.userId()).isEqualTo(42L);
        assertThat(conversation.title()).isEqualTo("정산 주기가 어떻게 되나요?");
        assertThat(conversation.messageCount()).isZero();
        assertThat(conversation.lastMessageAt()).isEqualTo(NOW);
        assertThat(conversation.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("deriveTitle — 공백 정규화 + 120자 초과는 잘린다")
    void deriveTitle_truncates() {
        String longMessage = "가".repeat(200);
        assertThat(Conversation.deriveTitle(longMessage)).hasSize(120);
        assertThat(Conversation.deriveTitle("  안녕   하세요\n반갑습니다  ")).isEqualTo("안녕 하세요 반갑습니다");
    }

    @Test
    @DisplayName("deriveTitle — 빈 첫 메시지는 거부한다")
    void deriveTitle_rejectsBlank() {
        assertThatThrownBy(() -> Conversation.deriveTitle("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recordExchange — 1왕복마다 메시지 카운트 +2, 최신 시각 갱신")
    void recordExchange() {
        Conversation conversation = Conversation.start(42L, "첫 질문", NOW);
        Instant later = NOW.plusSeconds(60);

        conversation.recordExchange(later);

        assertThat(conversation.messageCount()).isEqualTo(2);
        assertThat(conversation.lastMessageAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("isOwnedBy — 소유자 판정")
    void isOwnedBy() {
        Conversation conversation = Conversation.start(42L, "첫 질문", NOW);

        assertThat(conversation.isOwnedBy(42L)).isTrue();
        assertThat(conversation.isOwnedBy(7L)).isFalse();
    }
}
