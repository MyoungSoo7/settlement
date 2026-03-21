# RAG System Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring AI + pgvector 기반 RAG 시스템을 도입하여 상품/리뷰/주문/정산 데이터에 대한 자연어 질의를 SSE 스트리밍 + 멀티턴 채팅으로 제공한다.

**Architecture:** 헥사고날 아키텍처에 `rag` 도메인을 추가. Spring AI의 OpenAI 통합으로 임베딩/채팅을 처리하고, pgvector로 벡터 저장/검색. 프론트엔드에 플로팅 채팅 UI 추가.

**Tech Stack:** Spring AI (OpenAI), pgvector, SSE (SseEmitter), React, EventSource API

---

## File Structure

### Backend (새로 생성)
- `src/main/java/github/lms/lemuel/rag/adapter/in/web/RagController.java` — SSE 스트리밍 엔드포인트
- `src/main/java/github/lms/lemuel/rag/adapter/in/web/dto/RagQueryRequest.java` — 질의 요청 DTO
- `src/main/java/github/lms/lemuel/rag/adapter/in/web/dto/RagStreamEvent.java` — SSE 이벤트 DTO
- `src/main/java/github/lms/lemuel/rag/adapter/out/ai/OpenAiEmbeddingAdapter.java` — 임베딩 생성 어댑터
- `src/main/java/github/lms/lemuel/rag/adapter/out/ai/OpenAiChatAdapter.java` — LLM 채팅 어댑터
- `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/VectorStoreAdapter.java` — pgvector 검색/저장
- `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/ConversationJpaAdapter.java` — 대화 히스토리 저장
- `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/entity/DocumentEmbeddingEntity.java` — 벡터 테이블 JPA 엔티티
- `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/entity/ConversationEntity.java` — 대화 테이블 JPA 엔티티
- `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/DocumentEmbeddingJpaRepository.java` — Spring Data JPA 리포지토리
- `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/ConversationJpaRepository.java` — Spring Data JPA 리포지토리
- `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/VectorSearchJdbcRepository.java` — 네이티브 SQL 벡터 검색
- `src/main/java/github/lms/lemuel/rag/application/port/in/RagQueryUseCase.java` — 질의 유스케이스 인터페이스
- `src/main/java/github/lms/lemuel/rag/application/port/in/EmbeddingIndexUseCase.java` — 임베딩 인덱싱 유스케이스
- `src/main/java/github/lms/lemuel/rag/application/port/out/EmbeddingPort.java` — 임베딩 생성 포트
- `src/main/java/github/lms/lemuel/rag/application/port/out/ChatPort.java` — LLM 채팅 포트
- `src/main/java/github/lms/lemuel/rag/application/port/out/VectorSearchPort.java` — 벡터 검색 포트
- `src/main/java/github/lms/lemuel/rag/application/port/out/ConversationPort.java` — 대화 히스토리 포트
- `src/main/java/github/lms/lemuel/rag/application/service/RagQueryService.java` — RAG 파이프라인 핵심 로직
- `src/main/java/github/lms/lemuel/rag/application/service/EmbeddingIndexService.java` — 데이터 임베딩 서비스
- `src/main/java/github/lms/lemuel/rag/application/service/DocumentChunker.java` — 엔티티 → 텍스트 청크 변환
- `src/main/java/github/lms/lemuel/rag/domain/Conversation.java` — 대화 도메인 모델
- `src/main/java/github/lms/lemuel/rag/domain/DocumentChunk.java` — 문서 청크 도메인 모델
- `src/main/java/github/lms/lemuel/rag/domain/EntityType.java` — 엔티티 타입 enum

### DB Migration
- `src/main/resources/db/migration/V23__add_pgvector_and_rag_tables.sql`

### Frontend (새로 생성)
- `frontend/src/components/chat/ChatWidget.tsx` — 플로팅 채팅 위젯 (메인 컴포넌트)
- `frontend/src/components/chat/ChatMessage.tsx` — 메시지 버블 컴포넌트
- `frontend/src/components/chat/ChatInput.tsx` — 입력 폼 컴포넌트
- `frontend/src/api/rag.ts` — RAG API 호출 (SSE EventSource)

### Backend (수정)
- `build.gradle.kts` — Spring AI 의존성 추가
- `src/main/resources/application.yml` — OpenAI API key, RAG 설정 추가
- `src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java` — RAG 엔드포인트 인증 설정
- `frontend/src/App.tsx` — ChatWidget 추가

---

## Chunk 1: 인프라 설정 (의존성 + DB + 설정)

### Task 1: build.gradle.kts에 Spring AI + pgvector 의존성 추가

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Spring AI BOM 및 의존성 추가**

```kotlin
// repositories 블록에 추가
maven { url = uri("https://repo.spring.io/milestone") }

// dependencyManagement 블록 추가
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0-M6")
    }
}

// dependencies 블록에 추가
implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")
implementation("org.pgvector:pgvector:0.1.6")
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew dependencies --configuration compileClasspath | grep -i "spring-ai\|pgvector"`
Expected: spring-ai-openai 및 pgvector 의존성 표시

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add Spring AI and pgvector dependencies for RAG system"
```

### Task 2: Flyway 마이그레이션 — pgvector 확장 + RAG 테이블

**Files:**
- Create: `src/main/resources/db/migration/V23__add_pgvector_and_rag_tables.sql`

- [ ] **Step 1: 마이그레이션 SQL 작성**

```sql
-- pgvector 확장 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- 문서 임베딩 테이블
CREATE TABLE opslab.document_embedding (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   BIGINT       NOT NULL,
    content     TEXT         NOT NULL,
    embedding   vector(1536) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_embedding UNIQUE (entity_type, entity_id)
);

-- IVFFlat 인덱스 (코사인 유사도)
CREATE INDEX idx_embedding_vector ON opslab.document_embedding
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE INDEX idx_embedding_entity ON opslab.document_embedding (entity_type, entity_id);

-- 대화 히스토리 테이블
CREATE TABLE opslab.conversation (
    id         BIGSERIAL    PRIMARY KEY,
    session_id VARCHAR(64)  NOT NULL,
    role       VARCHAR(20)  NOT NULL,
    content    TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_conversation_session ON opslab.conversation (session_id, created_at);
```

- [ ] **Step 2: Flyway 마이그레이션 실행 확인**

Run: `./gradlew flywayMigrate`
Expected: V23 마이그레이션 성공

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V23__add_pgvector_and_rag_tables.sql
git commit -m "db: add pgvector extension and RAG tables (V23)"
```

### Task 3: application.yml에 OpenAI + RAG 설정 추가

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: RAG 관련 설정 추가**

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:}
      chat:
        options:
          model: gpt-4o
          temperature: 0.7
      embedding:
        options:
          model: text-embedding-3-small

app:
  rag:
    enabled: true
    max-results: 5
    similarity-threshold: 0.7
    system-prompt: |
      당신은 Lemuel 이커머스 시스템의 AI 어시스턴트입니다.
      상품, 리뷰, 주문, 정산 데이터를 기반으로 질문에 답합니다.
      제공된 컨텍스트 데이터만을 기반으로 정확하게 답변하세요.
      컨텍스트에 없는 정보는 "해당 정보를 찾을 수 없습니다"라고 답하세요.
      한국어로 답변하세요.
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: add OpenAI and RAG configuration"
```

---

## Chunk 2: 도메인 + 포트 레이어

### Task 4: 도메인 모델 생성

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/domain/EntityType.java`
- Create: `src/main/java/github/lms/lemuel/rag/domain/DocumentChunk.java`
- Create: `src/main/java/github/lms/lemuel/rag/domain/Conversation.java`

- [ ] **Step 1: EntityType enum**

```java
package github.lms.lemuel.rag.domain;

public enum EntityType {
    PRODUCT, REVIEW, ORDER, SETTLEMENT
}
```

- [ ] **Step 2: DocumentChunk 도메인 모델**

```java
package github.lms.lemuel.rag.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DocumentChunk {
    private final Long id;
    private final EntityType entityType;
    private final Long entityId;
    private final String content;
    private final float[] embedding;
    private final double similarity;
}
```

- [ ] **Step 3: Conversation 도메인 모델**

```java
package github.lms.lemuel.rag.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class Conversation {
    private final String sessionId;
    private final List<Message> messages;

    @Getter
    @Builder
    public static class Message {
        private final String role;  // USER, ASSISTANT
        private final String content;
        private final LocalDateTime createdAt;
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/domain/
git commit -m "feat(rag): add domain models - EntityType, DocumentChunk, Conversation"
```

### Task 5: 포트 인터페이스 생성

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/application/port/out/EmbeddingPort.java`
- Create: `src/main/java/github/lms/lemuel/rag/application/port/out/ChatPort.java`
- Create: `src/main/java/github/lms/lemuel/rag/application/port/out/VectorSearchPort.java`
- Create: `src/main/java/github/lms/lemuel/rag/application/port/out/ConversationPort.java`
- Create: `src/main/java/github/lms/lemuel/rag/application/port/in/RagQueryUseCase.java`
- Create: `src/main/java/github/lms/lemuel/rag/application/port/in/EmbeddingIndexUseCase.java`

- [ ] **Step 1: EmbeddingPort**

```java
package github.lms.lemuel.rag.application.port.out;

import java.util.List;

public interface EmbeddingPort {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
}
```

- [ ] **Step 2: ChatPort**

```java
package github.lms.lemuel.rag.application.port.out;

import java.util.List;
import java.util.function.Consumer;

public interface ChatPort {
    record ChatMessage(String role, String content) {}

    void streamChat(String systemPrompt, List<ChatMessage> messages, Consumer<String> tokenConsumer);
}
```

- [ ] **Step 3: VectorSearchPort**

```java
package github.lms.lemuel.rag.application.port.out;

import github.lms.lemuel.rag.domain.DocumentChunk;

import java.util.List;

public interface VectorSearchPort {
    List<DocumentChunk> searchSimilar(float[] queryEmbedding, int maxResults, double threshold);
    void save(DocumentChunk chunk);
    void saveAll(List<DocumentChunk> chunks);
    long count();
}
```

- [ ] **Step 4: ConversationPort**

```java
package github.lms.lemuel.rag.application.port.out;

import github.lms.lemuel.rag.domain.Conversation;

public interface ConversationPort {
    Conversation getConversation(String sessionId);
    void saveMessage(String sessionId, String role, String content);
}
```

- [ ] **Step 5: RagQueryUseCase**

```java
package github.lms.lemuel.rag.application.port.in;

import java.util.function.Consumer;

public interface RagQueryUseCase {
    void query(String sessionId, String question, Consumer<String> tokenConsumer);
}
```

- [ ] **Step 6: EmbeddingIndexUseCase**

```java
package github.lms.lemuel.rag.application.port.in;

public interface EmbeddingIndexUseCase {
    record IndexResult(int indexed, int skipped, int failed) {}
    IndexResult indexAll();
}
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/application/
git commit -m "feat(rag): add port interfaces - in/out use cases and adapters"
```

---

## Chunk 3: 어댑터 레이어 — 영속성 (pgvector + 대화)

### Task 6: JPA 엔티티 생성

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/entity/DocumentEmbeddingEntity.java`
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/entity/ConversationEntity.java`

- [ ] **Step 1: DocumentEmbeddingEntity**

```java
package github.lms.lemuel.rag.adapter.out.persistence.entity;

import github.lms.lemuel.rag.domain.EntityType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_embedding", schema = "opslab")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class DocumentEmbeddingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 50)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // pgvector는 JPA에서 직접 매핑 불가 — 네이티브 쿼리 사용
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: ConversationEntity**

```java
package github.lms.lemuel.rag.adapter.out.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversation", schema = "opslab")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/adapter/out/persistence/entity/
git commit -m "feat(rag): add JPA entities for document_embedding and conversation"
```

### Task 7: JPA 리포지토리 + 네이티브 벡터 검색

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/DocumentEmbeddingJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/ConversationJpaRepository.java`
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/VectorSearchJdbcRepository.java`

- [ ] **Step 1: DocumentEmbeddingJpaRepository**

```java
package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.adapter.out.persistence.entity.DocumentEmbeddingEntity;
import github.lms.lemuel.rag.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentEmbeddingJpaRepository extends JpaRepository<DocumentEmbeddingEntity, Long> {
    Optional<DocumentEmbeddingEntity> findByEntityTypeAndEntityId(EntityType entityType, Long entityId);
    long countByEntityType(EntityType entityType);
}
```

- [ ] **Step 2: ConversationJpaRepository**

```java
package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.adapter.out.persistence.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationJpaRepository extends JpaRepository<ConversationEntity, Long> {
    List<ConversationEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
```

- [ ] **Step 3: VectorSearchJdbcRepository — 네이티브 pgvector 쿼리**

```java
package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.domain.DocumentChunk;
import github.lms.lemuel.rag.domain.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class VectorSearchJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<DocumentChunk> findSimilar(float[] queryEmbedding, int maxResults, double threshold) {
        String vectorStr = Arrays.toString(queryEmbedding);
        String sql = """
            SELECT id, entity_type, entity_id, content,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM opslab.document_embedding
            WHERE 1 - (embedding <=> ?::vector) >= ?
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> mapToDocumentChunk(rs),
                vectorStr, vectorStr, threshold, vectorStr, maxResults);
    }

    public void insertWithEmbedding(EntityType entityType, Long entityId, String content, float[] embedding) {
        String vectorStr = Arrays.toString(embedding);
        String sql = """
            INSERT INTO opslab.document_embedding (entity_type, entity_id, content, embedding, created_at, updated_at)
            VALUES (?, ?, ?, ?::vector, now(), now())
            ON CONFLICT (entity_type, entity_id)
            DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding, updated_at = now()
            """;

        jdbcTemplate.update(sql, entityType.name(), entityId, content, vectorStr);
    }

    public long count() {
        Long result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM opslab.document_embedding", Long.class);
        return result != null ? result : 0;
    }

    private DocumentChunk mapToDocumentChunk(ResultSet rs) throws SQLException {
        return DocumentChunk.builder()
                .id(rs.getLong("id"))
                .entityType(EntityType.valueOf(rs.getString("entity_type")))
                .entityId(rs.getLong("entity_id"))
                .content(rs.getString("content"))
                .similarity(rs.getDouble("similarity"))
                .build();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/adapter/out/persistence/
git commit -m "feat(rag): add JPA repositories and native pgvector search"
```

### Task 8: 영속성 어댑터 구현

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/VectorStoreAdapter.java`
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/persistence/ConversationJpaAdapter.java`

- [ ] **Step 1: VectorStoreAdapter**

```java
package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.application.port.out.VectorSearchPort;
import github.lms.lemuel.rag.domain.DocumentChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class VectorStoreAdapter implements VectorSearchPort {

    private final VectorSearchJdbcRepository vectorSearchJdbcRepository;

    @Override
    public List<DocumentChunk> searchSimilar(float[] queryEmbedding, int maxResults, double threshold) {
        return vectorSearchJdbcRepository.findSimilar(queryEmbedding, maxResults, threshold);
    }

    @Override
    public void save(DocumentChunk chunk) {
        vectorSearchJdbcRepository.insertWithEmbedding(
                chunk.getEntityType(), chunk.getEntityId(), chunk.getContent(), chunk.getEmbedding());
    }

    @Override
    public void saveAll(List<DocumentChunk> chunks) {
        chunks.forEach(this::save);
    }

    @Override
    public long count() {
        return vectorSearchJdbcRepository.count();
    }
}
```

- [ ] **Step 2: ConversationJpaAdapter**

```java
package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.adapter.out.persistence.entity.ConversationEntity;
import github.lms.lemuel.rag.application.port.out.ConversationPort;
import github.lms.lemuel.rag.domain.Conversation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ConversationJpaAdapter implements ConversationPort {

    private final ConversationJpaRepository conversationJpaRepository;

    @Override
    public Conversation getConversation(String sessionId) {
        List<ConversationEntity> entities =
                conversationJpaRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        List<Conversation.Message> messages = entities.stream()
                .map(e -> Conversation.Message.builder()
                        .role(e.getRole())
                        .content(e.getContent())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();

        return Conversation.builder()
                .sessionId(sessionId)
                .messages(messages)
                .build();
    }

    @Override
    public void saveMessage(String sessionId, String role, String content) {
        ConversationEntity entity = ConversationEntity.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .build();
        conversationJpaRepository.save(entity);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/adapter/out/persistence/
git commit -m "feat(rag): implement VectorStoreAdapter and ConversationJpaAdapter"
```

---

## Chunk 4: AI 어댑터 (OpenAI 임베딩 + 채팅)

### Task 9: OpenAI 어댑터 구현

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/ai/OpenAiEmbeddingAdapter.java`
- Create: `src/main/java/github/lms/lemuel/rag/adapter/out/ai/OpenAiChatAdapter.java`

- [ ] **Step 1: OpenAiEmbeddingAdapter**

```java
package github.lms.lemuel.rag.adapter.out.ai;

import github.lms.lemuel.rag.application.port.out.EmbeddingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OpenAiEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel embeddingModel;

    @Override
    public float[] embed(String text) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
        return response.getResult().getOutput();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        EmbeddingResponse response = embeddingModel.embedForResponse(texts);
        return response.getResults().stream()
                .map(r -> r.getOutput())
                .toList();
    }
}
```

- [ ] **Step 2: OpenAiChatAdapter**

```java
package github.lms.lemuel.rag.adapter.out.ai;

import github.lms.lemuel.rag.application.port.out.ChatPort;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class OpenAiChatAdapter implements ChatPort {

    private final ChatModel chatModel;

    @Override
    public void streamChat(String systemPrompt, List<ChatMessage> chatMessages, Consumer<String> tokenConsumer) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        for (ChatMessage msg : chatMessages) {
            if ("USER".equalsIgnoreCase(msg.role())) {
                messages.add(new UserMessage(msg.content()));
            } else {
                messages.add(new AssistantMessage(msg.content()));
            }
        }

        Prompt prompt = new Prompt(messages);
        Flux<String> stream = chatModel.stream(prompt)
                .map(response -> {
                    if (response.getResult() != null && response.getResult().getOutput() != null) {
                        String text = response.getResult().getOutput().getText();
                        return text != null ? text : "";
                    }
                    return "";
                })
                .filter(text -> !text.isEmpty());

        stream.doOnNext(tokenConsumer).blockLast();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/adapter/out/ai/
git commit -m "feat(rag): implement OpenAI embedding and chat adapters"
```

---

## Chunk 5: 서비스 레이어 (RAG 파이프라인)

### Task 10: DocumentChunker — 엔티티를 텍스트로 변환

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/application/service/DocumentChunker.java`

- [ ] **Step 1: DocumentChunker 구현**

```java
package github.lms.lemuel.rag.application.service;

import github.lms.lemuel.rag.domain.EntityType;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DocumentChunker {

    private final JdbcTemplate jdbcTemplate;

    public record ChunkData(EntityType entityType, Long entityId, String content) {}

    public List<ChunkData> chunkAll() {
        List<ChunkData> chunks = new ArrayList<>();
        chunks.addAll(chunkProducts());
        chunks.addAll(chunkReviews());
        chunks.addAll(chunkOrders());
        chunks.addAll(chunkSettlements());
        return chunks;
    }

    private List<ChunkData> chunkProducts() {
        String sql = "SELECT id, name, description, price, stock_quantity, status FROM opslab.products";
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.PRODUCT, rs.getLong("id"),
                        String.format("[상품] 이름: %s, 설명: %s, 가격: %d원, 재고: %d, 상태: %s",
                                rs.getString("name"),
                                rs.getString("description") != null ? rs.getString("description") : "없음",
                                rs.getLong("price"),
                                rs.getInt("stock_quantity"),
                                rs.getString("status"))));
    }

    private List<ChunkData> chunkReviews() {
        String sql = """
            SELECT r.id, r.rating, r.content, p.name AS product_name
            FROM opslab.reviews r
            JOIN opslab.products p ON r.product_id = p.id
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.REVIEW, rs.getLong("id"),
                        String.format("[리뷰] 상품: %s, 평점: %d/5, 내용: %s",
                                rs.getString("product_name"),
                                rs.getInt("rating"),
                                rs.getString("content") != null ? rs.getString("content") : "없음")));
    }

    private List<ChunkData> chunkOrders() {
        String sql = """
            SELECT o.id, o.amount, o.status, o.created_at, p.name AS product_name
            FROM opslab.orders o
            LEFT JOIN opslab.products p ON o.product_id = p.id
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.ORDER, rs.getLong("id"),
                        String.format("[주문] 주문번호: %d, 상품: %s, 금액: %d원, 상태: %s, 일시: %s",
                                rs.getLong("id"),
                                rs.getString("product_name") != null ? rs.getString("product_name") : "미지정",
                                rs.getLong("amount"),
                                rs.getString("status"),
                                rs.getTimestamp("created_at"))));
    }

    private List<ChunkData> chunkSettlements() {
        String sql = """
            SELECT s.id, s.amount, s.status, s.settlement_date, s.created_at
            FROM opslab.settlements s
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) ->
                new ChunkData(EntityType.SETTLEMENT, rs.getLong("id"),
                        String.format("[정산] 정산번호: %d, 금액: %d원, 상태: %s, 정산일: %s",
                                rs.getLong("id"),
                                rs.getLong("amount"),
                                rs.getString("status"),
                                rs.getDate("settlement_date"))));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/application/service/DocumentChunker.java
git commit -m "feat(rag): add DocumentChunker for entity-to-text conversion"
```

### Task 11: EmbeddingIndexService — 임베딩 배치 생성

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/application/service/EmbeddingIndexService.java`

- [ ] **Step 1: EmbeddingIndexService 구현**

```java
package github.lms.lemuel.rag.application.service;

import github.lms.lemuel.rag.application.port.in.EmbeddingIndexUseCase;
import github.lms.lemuel.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.rag.application.port.out.VectorSearchPort;
import github.lms.lemuel.rag.domain.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingIndexService implements EmbeddingIndexUseCase {

    private final DocumentChunker documentChunker;
    private final EmbeddingPort embeddingPort;
    private final VectorSearchPort vectorSearchPort;

    @Override
    public IndexResult indexAll() {
        List<DocumentChunker.ChunkData> chunks = documentChunker.chunkAll();
        log.info("RAG 인덱싱 시작: 총 {}건", chunks.size());

        AtomicInteger indexed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        // 배치 사이즈 20으로 나눠서 처리
        int batchSize = 20;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<DocumentChunker.ChunkData> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            try {
                List<String> texts = batch.stream().map(DocumentChunker.ChunkData::content).toList();
                List<float[]> embeddings = embeddingPort.embedBatch(texts);

                for (int j = 0; j < batch.size(); j++) {
                    DocumentChunker.ChunkData chunk = batch.get(j);
                    DocumentChunk docChunk = DocumentChunk.builder()
                            .entityType(chunk.entityType())
                            .entityId(chunk.entityId())
                            .content(chunk.content())
                            .embedding(embeddings.get(j))
                            .build();
                    vectorSearchPort.save(docChunk);
                    indexed.incrementAndGet();
                }
            } catch (Exception e) {
                log.error("배치 임베딩 실패 (index {}~{}): {}", i, i + batchSize, e.getMessage());
                failed.addAndGet(batch.size());
            }
        }

        log.info("RAG 인덱싱 완료: indexed={}, failed={}", indexed.get(), failed.get());
        return new IndexResult(indexed.get(), 0, failed.get());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/application/service/EmbeddingIndexService.java
git commit -m "feat(rag): add EmbeddingIndexService for batch embedding generation"
```

### Task 12: RagQueryService — RAG 파이프라인 핵심 로직

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/application/service/RagQueryService.java`
- Create: `src/main/java/github/lms/lemuel/rag/application/service/RagProperties.java`

- [ ] **Step 1: RagProperties 설정 바인딩**

```java
package github.lms.lemuel.rag.application.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag")
@Getter
@Setter
public class RagProperties {
    private boolean enabled = true;
    private int maxResults = 5;
    private double similarityThreshold = 0.7;
    private String systemPrompt = "당신은 Lemuel 이커머스 시스템의 AI 어시스턴트입니다.";
}
```

- [ ] **Step 2: RagQueryService 구현**

```java
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
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/application/service/
git commit -m "feat(rag): implement RagQueryService with full RAG pipeline"
```

---

## Chunk 6: 웹 어댑터 (SSE 컨트롤러)

### Task 13: DTO + SSE 컨트롤러 구현

**Files:**
- Create: `src/main/java/github/lms/lemuel/rag/adapter/in/web/dto/RagQueryRequest.java`
- Create: `src/main/java/github/lms/lemuel/rag/adapter/in/web/dto/RagStreamEvent.java`
- Create: `src/main/java/github/lms/lemuel/rag/adapter/in/web/RagController.java`

- [ ] **Step 1: RagQueryRequest DTO**

```java
package github.lms.lemuel.rag.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RagQueryRequest(
        @NotBlank String sessionId,
        @NotBlank String question
) {}
```

- [ ] **Step 2: RagStreamEvent DTO**

```java
package github.lms.lemuel.rag.adapter.in.web.dto;

public record RagStreamEvent(String token, boolean done) {

    public static RagStreamEvent token(String token) {
        return new RagStreamEvent(token, false);
    }

    public static RagStreamEvent done() {
        return new RagStreamEvent(null, true);
    }
}
```

- [ ] **Step 3: RagController — SSE 스트리밍**

```java
package github.lms.lemuel.rag.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.rag.adapter.in.web.dto.RagQueryRequest;
import github.lms.lemuel.rag.adapter.in.web.dto.RagStreamEvent;
import github.lms.lemuel.rag.application.port.in.EmbeddingIndexUseCase;
import github.lms.lemuel.rag.application.port.in.RagQueryUseCase;
import github.lms.lemuel.rag.application.port.out.ConversationPort;
import github.lms.lemuel.rag.domain.Conversation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagQueryUseCase ragQueryUseCase;
    private final EmbeddingIndexUseCase embeddingIndexUseCase;
    private final ConversationPort conversationPort;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * SSE 스트리밍 질의
     */
    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@Valid @RequestBody RagQueryRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L); // 60초 타임아웃

        executor.execute(() -> {
            try {
                ragQueryUseCase.query(request.sessionId(), request.question(), token -> {
                    try {
                        String json = objectMapper.writeValueAsString(RagStreamEvent.token(token));
                        emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        log.error("SSE 전송 실패", e);
                    }
                });

                // 완료 이벤트
                String doneJson = objectMapper.writeValueAsString(RagStreamEvent.done());
                emitter.send(SseEmitter.event().data(doneJson, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.error("RAG 질의 실패: sessionId={}", request.sessionId(), e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 새 세션 생성
     */
    @PostMapping("/sessions")
    public ResponseEntity<Map<String, String>> createSession() {
        String sessionId = UUID.randomUUID().toString();
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * 대화 히스토리 조회
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Conversation> getConversation(@PathVariable String sessionId) {
        return ResponseEntity.ok(conversationPort.getConversation(sessionId));
    }

    /**
     * 수동 인덱싱 트리거 (관리자용)
     */
    @PostMapping("/index")
    public ResponseEntity<EmbeddingIndexUseCase.IndexResult> indexAll() {
        return ResponseEntity.ok(embeddingIndexUseCase.indexAll());
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/github/lms/lemuel/rag/adapter/in/web/
git commit -m "feat(rag): add RagController with SSE streaming endpoint"
```

### Task 14: SecurityConfig에 RAG 엔드포인트 추가

**Files:**
- Modify: `src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java`

- [ ] **Step 1: RAG 엔드포인트 인증 설정 추가**

SecurityConfig의 `authorizeHttpRequests` 블록에 추가:

```java
// RAG API (인증 사용자)
.requestMatchers("/api/rag/query", "/api/rag/sessions", "/api/rag/sessions/**").authenticated()
// RAG 인덱싱 (관리자 전용)
.requestMatchers(HttpMethod.POST, "/api/rag/index").hasRole("ADMIN")
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/common/config/jwt/SecurityConfig.java
git commit -m "feat(rag): add RAG endpoints to SecurityConfig"
```

---

## Chunk 7: 프론트엔드 — 채팅 UI

### Task 15: RAG API 클라이언트

**Files:**
- Create: `frontend/src/api/rag.ts`

- [ ] **Step 1: rag.ts API 모듈**

```typescript
import api from './axios';

const BASE = '/api/rag';

export interface RagMessage {
  role: string;
  content: string;
  createdAt: string;
}

export interface RagConversation {
  sessionId: string;
  messages: RagMessage[];
}

export const ragApi = {
  createSession: async (): Promise<{ sessionId: string }> => {
    const res = await api.post(`${BASE}/sessions`);
    return res.data;
  },

  getConversation: async (sessionId: string): Promise<RagConversation> => {
    const res = await api.get(`${BASE}/sessions/${sessionId}`);
    return res.data;
  },

  queryStream: (sessionId: string, question: string, onToken: (token: string) => void, onDone: () => void, onError: (error: string) => void) => {
    const token = localStorage.getItem('access_token');
    const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

    fetch(`${baseURL}${BASE}/query`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ sessionId, question }),
    }).then(async (response) => {
      if (!response.ok) {
        onError(`서버 오류: ${response.status}`);
        return;
      }
      const reader = response.body?.getReader();
      if (!reader) {
        onError('스트림을 읽을 수 없습니다.');
        return;
      }

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const jsonStr = line.slice(5).trim();
            if (!jsonStr) continue;
            try {
              const event = JSON.parse(jsonStr);
              if (event.done) {
                onDone();
              } else if (event.token) {
                onToken(event.token);
              }
            } catch {
              // 파싱 실패 무시
            }
          }
        }
      }
      onDone();
    }).catch((err) => {
      onError(err.message || '네트워크 오류');
    });
  },

  indexAll: async (): Promise<{ indexed: number; skipped: number; failed: number }> => {
    const res = await api.post(`${BASE}/index`);
    return res.data;
  },
};
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/api/rag.ts
git commit -m "feat(rag): add RAG API client with SSE streaming support"
```

### Task 16: ChatMessage 컴포넌트

**Files:**
- Create: `frontend/src/components/chat/ChatMessage.tsx`

- [ ] **Step 1: ChatMessage 컴포넌트 구현**

```tsx
import React from 'react';

interface ChatMessageProps {
  role: 'USER' | 'ASSISTANT';
  content: string;
  isStreaming?: boolean;
}

const ChatMessage: React.FC<ChatMessageProps> = ({ role, content, isStreaming }) => {
  const isUser = role === 'USER';

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} mb-3`}>
      <div className={`max-w-[80%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
        isUser
          ? 'bg-blue-600 text-white rounded-br-md'
          : 'bg-gray-100 text-gray-800 rounded-bl-md'
      }`}>
        <p className="whitespace-pre-wrap">{content}</p>
        {isStreaming && (
          <span className="inline-block w-1.5 h-4 bg-gray-400 animate-pulse ml-0.5 align-middle" />
        )}
      </div>
    </div>
  );
};

export default ChatMessage;
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/chat/ChatMessage.tsx
git commit -m "feat(rag): add ChatMessage bubble component"
```

### Task 17: ChatInput 컴포넌트

**Files:**
- Create: `frontend/src/components/chat/ChatInput.tsx`

- [ ] **Step 1: ChatInput 컴포넌트 구현**

```tsx
import React, { useState } from 'react';

interface ChatInputProps {
  onSend: (message: string) => void;
  disabled?: boolean;
}

const ChatInput: React.FC<ChatInputProps> = ({ onSend, disabled }) => {
  const [input, setInput] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = input.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setInput('');
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="flex gap-2 p-3 border-t bg-white">
      <textarea
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="질문을 입력하세요..."
        disabled={disabled}
        rows={1}
        className="flex-1 resize-none rounded-xl border border-gray-300 px-4 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:bg-gray-50"
      />
      <button
        type="submit"
        disabled={disabled || !input.trim()}
        className="px-4 py-2.5 bg-blue-600 text-white rounded-xl text-sm font-medium hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed transition-colors"
      >
        전송
      </button>
    </form>
  );
};

export default ChatInput;
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/chat/ChatInput.tsx
git commit -m "feat(rag): add ChatInput component"
```

### Task 18: ChatWidget — 메인 플로팅 채팅 위젯

**Files:**
- Create: `frontend/src/components/chat/ChatWidget.tsx`

- [ ] **Step 1: ChatWidget 구현**

```tsx
import React, { useState, useRef, useEffect, useCallback } from 'react';
import ChatMessage from './ChatMessage';
import ChatInput from './ChatInput';
import { ragApi } from '@/api/rag';

interface Message {
  role: 'USER' | 'ASSISTANT';
  content: string;
  isStreaming?: boolean;
}

const ChatWidget: React.FC = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  const initSession = async () => {
    if (!sessionId) {
      const { sessionId: newId } = await ragApi.createSession();
      setSessionId(newId);
      return newId;
    }
    return sessionId;
  };

  const handleSend = async (question: string) => {
    const sid = await initSession();
    setMessages(prev => [...prev, { role: 'USER', content: question }]);
    setMessages(prev => [...prev, { role: 'ASSISTANT', content: '', isStreaming: true }]);
    setIsLoading(true);

    ragApi.queryStream(
      sid,
      question,
      (token) => {
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last.role === 'ASSISTANT') {
            updated[updated.length - 1] = { ...last, content: last.content + token };
          }
          return updated;
        });
      },
      () => {
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last.role === 'ASSISTANT') {
            updated[updated.length - 1] = { ...last, isStreaming: false };
          }
          return updated;
        });
        setIsLoading(false);
      },
      (error) => {
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          if (last.role === 'ASSISTANT') {
            updated[updated.length - 1] = { ...last, content: `오류: ${error}`, isStreaming: false };
          }
          return updated;
        });
        setIsLoading(false);
      }
    );
  };

  const handleNewChat = () => {
    setSessionId(null);
    setMessages([]);
  };

  return (
    <>
      {/* 플로팅 버튼 */}
      {!isOpen && (
        <button
          onClick={() => setIsOpen(true)}
          className="fixed bottom-6 right-6 w-14 h-14 bg-blue-600 text-white rounded-full shadow-lg hover:bg-blue-700 transition-all hover:scale-110 flex items-center justify-center z-50"
          title="AI 어시스턴트"
        >
          <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
              d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
          </svg>
        </button>
      )}

      {/* 채팅 패널 */}
      {isOpen && (
        <div className="fixed bottom-6 right-6 w-96 h-[32rem] bg-white rounded-2xl shadow-2xl flex flex-col z-50 border border-gray-200 overflow-hidden">
          {/* 헤더 */}
          <div className="flex items-center justify-between px-4 py-3 bg-blue-600 text-white">
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2"
                  d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
              <span className="font-semibold text-sm">Lemuel AI</span>
            </div>
            <div className="flex items-center gap-1">
              <button onClick={handleNewChat} className="p-1.5 hover:bg-blue-700 rounded-lg transition-colors" title="새 대화">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M12 4v16m8-8H4" />
                </svg>
              </button>
              <button onClick={() => setIsOpen(false)} className="p-1.5 hover:bg-blue-700 rounded-lg transition-colors" title="닫기">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
          </div>

          {/* 메시지 영역 */}
          <div className="flex-1 overflow-y-auto p-4">
            {messages.length === 0 && (
              <div className="text-center text-gray-400 text-sm mt-12">
                <p className="mb-2">Lemuel AI 어시스턴트입니다.</p>
                <p>상품, 리뷰, 주문, 정산에 대해 물어보세요!</p>
              </div>
            )}
            {messages.map((msg, i) => (
              <ChatMessage key={i} role={msg.role} content={msg.content} isStreaming={msg.isStreaming} />
            ))}
            <div ref={messagesEndRef} />
          </div>

          {/* 입력 */}
          <ChatInput onSend={handleSend} disabled={isLoading} />
        </div>
      )}
    </>
  );
};

export default ChatWidget;
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/components/chat/
git commit -m "feat(rag): add ChatWidget floating chat UI with SSE streaming"
```

### Task 19: App.tsx에 ChatWidget 추가

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: ChatWidget import 및 렌더링 추가**

App.tsx의 `<BrowserRouter>` 안에 `ChatWidget` 추가:

```tsx
import ChatWidget from './components/chat/ChatWidget';

// ... 기존 코드 ...

// </Routes> 뒤, </Suspense> 앞에 추가:
<ChatWidget />
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(rag): integrate ChatWidget into App.tsx"
```

---

## Chunk 8: 통합 및 검증

### Task 20: 전체 빌드 확인

- [ ] **Step 1: 백엔드 빌드**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 프론트엔드 빌드**

Run: `cd frontend && npm run build`
Expected: 빌드 성공

- [ ] **Step 3: 서버 구동 후 인덱싱 테스트**

```bash
# 서버 구동 후
curl -X POST http://localhost:8080/api/rag/index \
  -H "Authorization: Bearer <admin_token>"
```
Expected: `{"indexed": N, "skipped": 0, "failed": 0}`

- [ ] **Step 4: RAG 질의 테스트**

```bash
# 세션 생성
curl -X POST http://localhost:8080/api/rag/sessions \
  -H "Authorization: Bearer <token>"

# 질의
curl -X POST http://localhost:8080/api/rag/query \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<session_id>","question":"가장 비싼 상품은?"}'
```
Expected: SSE 스트리밍 응답

- [ ] **Step 5: 최종 Commit**

```bash
git add -A
git commit -m "feat(rag): complete RAG system with Spring AI + pgvector + SSE streaming"
```
