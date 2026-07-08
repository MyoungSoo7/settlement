package github.lms.lemuel.ai.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 대화 애그리게잇 루트 (순수 POJO).
 *
 * <p>사용자 소유의 메시지 스레드 메타데이터를 관리한다. 메시지 자체는 append-only 로
 * 별도 적재되며(설계 §4), 이 애그리게잇은 제목·카운트·최신성만 책임진다.
 * id 는 서버가 생성한 UUID — 클라이언트가 타인 대화를 추측할 수 없게 한다.
 */
public class Conversation {

    /** 제목 최대 길이 — chat_conversations.title VARCHAR(120) 과 일치. */
    static final int TITLE_MAX_LENGTH = 120;

    private final UUID id;
    private final Long userId;
    private final String title;
    private final Instant createdAt;
    private int messageCount;
    private Instant lastMessageAt;

    private Conversation(UUID id, Long userId, String title, int messageCount,
                         Instant lastMessageAt, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.title = Objects.requireNonNull(title, "title");
        this.messageCount = messageCount;
        this.lastMessageAt = Objects.requireNonNull(lastMessageAt, "lastMessageAt");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 첫 사용자 메시지로 새 대화를 시작한다 — 제목은 메시지 앞부분에서 파생. */
    public static Conversation start(Long userId, String firstUserMessage, Instant now) {
        return new Conversation(UUID.randomUUID(), userId, deriveTitle(firstUserMessage), 0, now, now);
    }

    /** 영속 상태 복원 (persistence adapter 전용). */
    public static Conversation restore(UUID id, Long userId, String title, int messageCount,
                                       Instant lastMessageAt, Instant createdAt) {
        return new Conversation(id, userId, title, messageCount, lastMessageAt, createdAt);
    }

    /** 사용자 발화 + LLM 응답 1왕복이 성공적으로 끝났음을 기록한다. */
    public void recordExchange(Instant at) {
        this.messageCount += 2;
        this.lastMessageAt = at;
    }

    /** 소유자 확인 — 타인 대화 접근 차단의 도메인 규칙. */
    public boolean isOwnedBy(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }

    static String deriveTitle(String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) {
            throw new IllegalArgumentException("첫 메시지는 비어 있을 수 없습니다");
        }
        String normalized = firstUserMessage.strip().replaceAll("\\s+", " ");
        return normalized.length() <= TITLE_MAX_LENGTH
                ? normalized
                : normalized.substring(0, TITLE_MAX_LENGTH);
    }

    public UUID id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public String title() {
        return title;
    }

    public int messageCount() {
        return messageCount;
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
