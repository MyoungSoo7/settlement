# ai-service Phase 1 상세 설계 — 골격 + 대화형 AI 챗봇 (대화 이력 유지)

> 상태: **구현 완료** (2026-07-08). 설계 대비 변경분은 문서 말미 "구현 노트" 참조.
> 선행 문서: 없음 (ai-service 도입 제안 — 전체 로드맵 Phase 1~3 중 1단계)
> 관련 ADR: 0001(헥사고날), 0009(멀티모듈), 0021(shared-common), 0023(위성 서비스 패턴 선례)
> 참고 구현: [MyoungSoo7/sparta-msa-project](https://github.com/MyoungSoo7/sparta-msa-project) 의
> product-service 내 `ai/agent/function/rag` 패키지 (Spring AI 1.0-M6 + Gemini/Ollama + pgvector)

## 0. 전체 로드맵

| Phase | 내용 | 이 문서 |
|-------|------|---------|
| **1** | 모듈 골격 + Claude 대화 챗봇(컨텍스트 유지) + 대화 이력 저장 + SSE 스트리밍 | ★ |
| 2 | Function Calling — 챗봇이 주문/정산/재무/경제지표를 **각 서비스 API 호출**로 답변 (`X-Internal-Api-Key` 패턴, cross-DB 0 유지) | 후속 |
| 3 | RAG — pgvector 임베딩 기반 문서 QA (FAQ·정책·상품 설명) | 후속 |

sparta-msa-project 와의 차이: 원본은 챗봇이 product-service **내부 패키지**지만, 우리는 기존
경계 원칙(DB-per-service, 서비스 간 Kafka/내부 API 연계만)에 맞춰 **독립 위성 서비스**로 만든다.

## 1. 범위

Phase 1 은 **"로그인 사용자가 대화 컨텍스트가 유지되는 AI 챗봇과 대화하고, 이력을 다시 볼 수 있는
최소 동작 서비스"** 까지다.

| 포함 | 제외 (후속 Phase) |
|------|------|
| 모듈 골격 (`ai-service`, port 8096) | Function Calling / 타 서비스 데이터 응답 (Phase 2) |
| 자체 DB `lemuel_ai` + Flyway V1 (대화·메시지) | pgvector 임베딩 / RAG (Phase 3) |
| Claude 채팅 (Spring AI 2.0 Anthropic) + 컨텍스트 윈도 | Kafka 이벤트 발행/구독 (필요 시 Phase 2+) |
| 대화 CRUD API (목록/이력/삭제) + SSE 스트리밍 | 에이전트(ReAct 등)·이미지 분석 |
| 사용자별 rate limit (LLM 비용 가드) | 프론트 채팅 UI 고도화 (Phase 1 은 최소 페이지 1개) |
| compose · gateway 라우팅 · .env 항목 | 운영 대시보드/토큰 비용 리포트 |

## 2. 핵심 결정

### 2.1 LLM 스택 — Spring AI 2.0 (결정)

| 옵션 | 평가 |
|------|------|
| **A. Spring AI 2.0 GA (`spring-ai-starter-model-anthropic`)** ★채택 | Boot 4 전용 GA(2026-05) — 현 스택(Boot 4.0.4) 정합. ChatClient·스트리밍·(Phase 2) `@Tool`·(Phase 3) VectorStore 가 같은 추상화로 이어짐. AI 가 **핵심 도메인**인 서비스라 도입 정당 |
| B. RestClient 직접 호출 (company `LlmSentimentAnalyzer` 패턴) | 단건 호출엔 검증된 패턴이지만 대화 히스토리·스트리밍·툴콜을 손으로 구현하게 됨. Phase 2/3 에서 재작성 비용 발생 |

단, Spring AI 는 **`adapter/out/llm` 안에만** 존재한다. application/domain 은
`ChatCompletionPort` 인터페이스만 알고, ArchUnit 으로 `org.springframework.ai..` 의존을
adapter.out.llm 외부에서 금지한다 — 벤더/프레임워크 교체 가능성 유지(헥사고날 원칙).

### 2.2 인증 — shared-common JWT (결정)

economics/financial/company 는 "공개 read-only" 라 shared-common 미의존이었지만, ai-service 는

- LLM 호출마다 **실비용**이 발생하고 (익명 개방 = 비용 폭탄),
- 대화 이력이 **사용자별 소유**라 사용자 식별이 필수다.

→ loan/operation 패턴을 따라 `github.lms.lemuel:shared-common:1.0.0` 의존 + JWT 인증(USER 이상).
rate limit 도 shared-common bucket4j 재사용.

### 2.3 DB 이미지 — 처음부터 pgvector (결정)

Phase 3 에서 pgvector 확장이 필요하다. 볼륨 마이그레이션을 피하기 위해 ai-postgres 컨테이너를
처음부터 `pgvector/pgvector:pg17` 이미지로 띄운다 (postgres:17 완전 호환, Phase 1 은 확장을
활성화하지 않고 이미지만 선점).

### 2.4 LLM 장애 처리 — 폴백 없음, 명시적 실패 (결정)

감성분석(키워드 폴백)과 달리 자유 대화는 룰 기반 폴백이 불가능하다. Resilience4j
timeout(30s)/retry(1회) 후에도 실패하면 **503 + 안내 메시지**를 반환하고, 실패한 교환은
대화 이력에 저장하지 않는다(사용자 메시지도 롤백 — 재전송 유도).

## 3. 모듈 골격

### 3.1 배치

```
ai-service/                           # 🤖 AI 챗봇 (로컬 port 8096, 컨테이너 내부 8080)
└── src/main/java/github/lms/lemuel/ai/
    ├── chat/                         # Phase 1 유일 BC
    │   ├── domain/
    │   │   ├── Conversation.java             # 애그리게잇 루트 (POJO)
    │   │   ├── ChatMessage.java              # 대화 메시지 (VO — role, content, tokens)
    │   │   ├── MessageRole.java              # USER / ASSISTANT
    │   │   └── ChatCompletion.java           # LLM 응답 결과 (VO — text, usage, model)
    │   ├── application/
    │   │   ├── port/in/
    │   │   │   ├── ChatUseCase.java                  # 메시지 전송 → 응답 (동기/스트림)
    │   │   │   └── ConversationQuery.java            # 대화 목록/이력 조회·삭제
    │   │   ├── port/out/
    │   │   │   ├── ChatCompletionPort.java           # LLM 호출 추상화 (★ Spring AI 격리 지점)
    │   │   │   ├── LoadConversationPort.java
    │   │   │   └── SaveConversationPort.java
    │   │   └── service/
    │   │       ├── ChatService.java                  # 윈도 구성→LLM 호출→이력 저장 (핵심)
    │   │       └── ConversationQueryService.java
    │   └── adapter/
    │       ├── in/web/
    │       │   ├── ChatController.java               # POST /api/ai/chat, /chat/stream(SSE)
    │       │   ├── ConversationController.java       # GET/DELETE /api/ai/conversations
    │       │   └── dto/  (ChatRequest, ChatResponse, ConversationSummaryResponse, ...)
    │       ├── out/llm/
    │       │   ├── AnthropicChatAdapter.java         # Spring AI ChatClient → ChatCompletionPort
    │       │   └── AiChatProperties.java             # model/max-tokens/window/system-prompt
    │       └── out/persistence/
    │           ├── ConversationJpaEntity.java / ChatMessageJpaEntity.java
    │           ├── ConversationRepository.java / ChatMessageRepository.java
    │           └── ConversationPersistenceAdapter.java
    ├── config/
    │   ├── AiSecurityConfig.java             # shared-common JWT 필터 조립 (loan 패턴)
    │   └── AiRateLimitConfig.java            # bucket4j — 사용자별 채팅 rate limit
    └── AiServiceApplication.java             # 자체 @SpringBootApplication
```

- **의존**: `github.lms.lemuel:shared-common:1.0.0` 만. 타 서비스 import 0 (기존 경계 원칙).
- Jackson: 제한 스캔 서비스이므로 `JacksonCompatConfig` `@Import` 필요 여부 확인 (기존 트랩).

### 3.2 settings.gradle.kts

```kotlin
include(
    // ... 기존 8개 ...
    "ai-service",                 // ★ 추가
)
```

### 3.3 build.gradle.kts (Phase 1 의존성)

loan-service 복사 기반에서:

- **추가**: `org.springframework.ai:spring-ai-starter-model-anthropic` (+ BOM `spring-ai-bom:2.0.x`),
  `spring-boot-starter-webflux` 는 불필요 — SSE 는 MVC `SseEmitter`/`Flux` 반환으로 처리
  (Spring AI streaming 은 Reactor 를 transitively 제공).
- **제외**: Kafka(Phase 1 이벤트 없음), QueryDSL(파생 쿼리로 충분), iText/ES.
- **포함**: web, data-jpa, security, validation, actuator, flyway(+postgresql), springdoc,
  caffeine, bucket4j(shared-common 경유), micrometer-prometheus, postgresql, lombok,
  testcontainers(postgresql), archunit 1.4.x.

## 4. 데이터 모델 — Flyway `V1__chat_core.sql`

```sql
-- V1: ai-service 자체 DB(lemuel_ai) — 대화 코어
-- 대화(conversation)는 사용자 소유의 메시지 스레드. 메시지는 불변 append-only.

CREATE TABLE chat_conversations (
    id               UUID         PRIMARY KEY,           -- 서버 생성 (클라이언트 추측 불가)
    user_id          BIGINT       NOT NULL,               -- JWT subject (order-service users.id)
    title            VARCHAR(120) NOT NULL,               -- 첫 사용자 메시지 앞 120자
    message_count    INTEGER      NOT NULL DEFAULT 0,
    last_message_at  TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversations_user_recent
    ON chat_conversations (user_id, last_message_at DESC);

CREATE TABLE chat_messages (
    id               BIGSERIAL    PRIMARY KEY,
    conversation_id  UUID         NOT NULL REFERENCES chat_conversations(id) ON DELETE CASCADE,
    role             VARCHAR(10)  NOT NULL,               -- USER / ASSISTANT
    content          TEXT         NOT NULL,
    model            VARCHAR(60),                         -- ASSISTANT 만 (응답 생성 모델 스냅샷)
    input_tokens     INTEGER,                             -- ASSISTANT 만 (usage 기록 → 비용 추적)
    output_tokens    INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX idx_messages_conversation ON chat_messages (conversation_id, id);
```

- `user_id` 는 **비즈니스 키 참조**만 (FK 없음 — DB-per-service). financial↔company 의
  stockCode 공유와 동일한 원칙.
- 토큰 usage 를 메시지 단위로 스냅샷 → 추후 사용자/일자별 비용 집계 쿼리 가능.

## 5. API 설계

모든 엔드포인트 JWT 필수(USER 이상). gateway 경유 시 `/api/ai/**`.

### 5.1 채팅

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/ai/chat` | 메시지 전송 → 완성 응답. `conversationId` 없으면 새 대화 생성 |
| POST | `/api/ai/chat/stream` | 동일 입력, **SSE 스트리밍** 응답 (`text/event-stream`) |

```jsonc
// POST /api/ai/chat  요청
{ "conversationId": "b1a2...(선택)", "message": "정산 주기가 어떻게 되나요?" }

// 응답
{
  "conversationId": "b1a2...",
  "reply": "Lemuel 의 정산 주기는 셀러 등급별로 ...",
  "model": "claude-opus-4-8",
  "usage": { "inputTokens": 412, "outputTokens": 180 }
}
```

SSE 이벤트: `event: delta` (텍스트 청크) 반복 → `event: done` (conversationId + usage).
스트리밍 완료 시점에 전체 응답을 이력에 저장(중간 끊김 = 미저장, 2.4 원칙과 동일).

### 5.2 대화 이력

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/ai/conversations?page=0&size=20` | **내** 대화 목록 (최근순) |
| GET | `/api/ai/conversations/{id}` | 대화 1건 + 메시지 전체 (소유자 검증 — 타인 것 404) |
| DELETE | `/api/ai/conversations/{id}` | 대화 삭제 (CASCADE) |

### 5.3 컨텍스트 윈도 구성 (ChatService 핵심 로직)

```
system prompt (고정: Lemuel 플랫폼 소개 + 답변 규칙 + "모르는 실데이터는 모른다고 답하라")
  + 해당 대화의 최근 N개 메시지 (기본 10, app.ai.chat.history-window)
  + 이번 사용자 메시지
→ ChatCompletionPort.complete(...)
```

Phase 1 챗봇의 지식 범위는 **일반 지식 + Lemuel 서비스 안내**다. 실계좌/주문 데이터 질문에는
"Phase 2 에서 연동 예정" 톤으로 답하도록 system prompt 에 명시한다 — **환각으로 금액을 지어내는
것을 금지**하는 문구가 포트폴리오 관점에서 중요 (돈 다루는 플랫폼의 AI 안전 장치).

### 5.4 비용 가드 (bucket4j)

| 한도 | 기본값 | 설정 키 |
|------|--------|---------|
| 사용자별 분당 채팅 | 5회 | `app.ai.rate-limit.per-minute` |
| 사용자별 일일 채팅 | 100회 | `app.ai.rate-limit.per-day` |
| 응답 max_tokens | 1024 | `app.ai.chat.max-tokens` |

초과 시 429 + `Retry-After`.

## 6. 설정 (application.yml 발췌)

```yaml
app:
  ai:
    chat:
      api-key: ${ANTHROPIC_API_KEY:}          # company-service 와 동일 env var 재사용
      model: ${APP_AI_CHAT_MODEL:claude-opus-4-8}
      max-tokens: 1024
      history-window: 10
      timeout-seconds: 30
    rate-limit:
      per-minute: 5
      per-day: 100
```

`ANTHROPIC_API_KEY` 미설정 시: 부팅은 성공하되 채팅 API 가 503 + "AI 미구성" 응답
(economics/financial 의 "키 미설정 → 수집 비활성, 시드 조회는 가능" 패턴과 동일한 철학 —
키 없이도 대화 **이력 조회**는 동작).

## 7. 인프라 변경

### 7.1 docker-compose.yml

```yaml
  # ★ DB-per-service: ai-service 전용 PostgreSQL (pgvector 이미지 — Phase 3 RAG 선점)
  ai-postgres:
    image: pgvector/pgvector:pg17
    container_name: lemuel-ai-db
    environment:
      POSTGRES_DB: lemuel_ai
      ...
    ports:
      - "127.0.0.1:5442:5432"

  ai-service:
    build: { context: ., args: { MODULE: ai-service } }
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://ai-postgres:5432/lemuel_ai
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:-}
      JWT_SECRET: ${JWT_SECRET}                 # order-service 와 동일 시크릿 (토큰 검증)
    ports:
      - "127.0.0.1:8096:8080"
```

### 7.2 gateway-service application.yml

```yaml
            - id: ai-service
              uri: ${AI_SERVICE_URI:http://localhost:8096}
              predicates:
                - Path=/api/ai/**
```

### 7.3 .env.example

`ANTHROPIC_API_KEY=` 항목 추가 (주석: company 감성분석 + ai-service 챗봇 공용).

## 8. 시퀀스 (동기 채팅)

```
Client ─POST /api/ai/chat (JWT)→ Gateway → ai-service ChatController
  → ChatService
      1. rate limit 확인 (bucket4j, userId 키)
      2. conversationId 있으면 로드+소유자 검증 / 없으면 Conversation 생성
      3. 최근 10개 메시지 + system prompt 로 윈도 구성
      4. ChatCompletionPort.complete()  ── AnthropicChatAdapter → Claude API
      5. (성공) USER + ASSISTANT 메시지 append, usage 기록, last_message_at 갱신  [단일 tx]
      6. (실패) tx 롤백 → 503 (이력 무흔적)
  ← ChatResponse
```

## 9. 테스트 전략

| 레벨 | 대상 |
|------|------|
| 도메인 단위 | Conversation append/title 생성, 윈도 슬라이싱 |
| 서비스 단위 | ChatService — 포트 mock: 신규/기존 대화, LLM 실패 롤백, 소유자 불일치 |
| 어댑터 | AnthropicChatAdapter — Spring AI ChatClient mock (실 API 미호출) |
| 컨트롤러 | webmvc-test — 인증 없는 요청 401, rate limit 429, DTO 검증 |
| 통합 | Testcontainers(postgres) — 대화 생성→메시지 왕복→이력 조회 (LLM 은 stub 포트 주입) |
| ArchUnit | 헥사고날 방향 + `org.springframework.ai..` 는 adapter.out.llm 만 허용 |

CI 게이트: 기존 JaCoCo 정책 준수 (`ai.chat.domain` INSTRUCTION 80%).

## 10. 프론트 (Phase 1 최소)

- `/ai/chat` 페이지 1개 — 좌측 대화 목록 + 우측 채팅창, SSE 스트리밍 렌더.
- vite dev 프록시에 `/api/ai` 추가 (기존 위성 서비스 프록시 항목과 동일 패턴).
- 고도화(플로팅 위젯, CEO 브리핑 연계)는 Phase 2 이후.

## 11. 수용 기준 (Definition of Done)

1. `./gradlew :ai-service:build` 그린 (JaCoCo 게이트 포함).
2. `docker compose up -d` 후 `/actuator/health` UP, gateway 경유 `/api/ai/**` 라우팅 동작.
3. JWT 로그인 사용자가 채팅 왕복 → 같은 conversationId 로 후속 질문 시 **직전 문맥을 기억**.
4. 대화 목록/이력 조회·삭제 동작, 타인 대화 접근 404.
5. `ANTHROPIC_API_KEY` 미설정 상태에서 부팅·이력 조회 정상 + 채팅 503 안내.
6. rate limit 초과 시 429.
7. ArchUnit — Spring AI 의존 격리 규칙 통과.

## 12. 리스크 / 오픈 이슈

| 항목 | 내용 | 대응 |
|------|------|------|
| Spring AI 2.0 신생 GA | Boot 4.0.4 와의 마이너 비호환 가능 | 포트 뒤 격리 — 문제 시 RestClient 직접 호출 어댑터로 교체 (계약 동일) |
| LLM 비용 | 데모 중 과금 폭주 | rate limit + max-tokens + usage 스냅샷(§4)으로 관측 |
| PII | 사용자가 대화에 개인정보 입력 가능 | Phase 1 은 system prompt 로 수집 억제 문구만. 마스킹/보존 정책은 Phase 2 에서 shared-common audit 연계 검토 |
| JWT 시크릿 공유 | ai-service 가 order 발급 토큰을 검증 | 기존 loan/operation 과 동일한 공유 시크릿 모델 — 신규 리스크 아님 |

## 13. 구현 노트 (2026-07-08 구현 완료 — 설계 대비 변경분)

- **Spring AI 2.0 내부 구조 변화**: 2.0 은 1.x 의 자체 `AnthropicApi`(RestClient) 를 버리고
  **공식 Anthropic Java SDK**(`com.anthropic:anthropic-java-core`, OkHttp) 위에 재작성됐다.
  수동 조립 진입점은 `AnthropicSetup.setupSyncClient(baseUrl, apiKey, timeout, maxRetries, ...)`
  — 설계의 "타임아웃 30s + 재시도 1회"가 SDK 클라이언트 레벨 옵션으로 흡수돼 어댑터 수동
  재시도 코드가 필요 없어졌다.
- **재시도 정책**: 동기·스트리밍 모두 SDK maxRetries=1. 어댑터 수준 추가 재시도는 제거
  (스트리밍은 청크가 이미 나간 뒤 재시도하면 응답 중복이라 원래도 불가).
- **rate limiter 배치**: bucket4j 구현을 `config` 가 아닌 `adapter/out/ratelimit`(RateLimitPort 구현)로
  — 인메모리 단일 인스턴스 전제, 스케일아웃 시 Redis ProxyManager 교체 지점 명시.
- **검증 결과**: `:ai-service:build` 그린(테스트 36개 — 도메인/서비스/컨트롤러/ArchUnit/Testcontainers
  통합 포함), 실 부팅 + Flyway V1(pgvector pg17 컨테이너) + 무인증 401 + 실 Anthropic API 호출까지
  확인. 유료 크레딧 부족 계정에서는 503 + 이력 무저장(§2.4)이 설계대로 동작함을 실호출로 검증.
- **로컬 bootRun 주의**: dotenv 는 JVM cwd 의 `.env` 를 읽으므로 `./gradlew :ai-service:bootRun` 은
  루트 `.env` 를 못 볼 수 있다 — 셸에서 `set -a; . ./.env; set +a` 후 실행하거나 compose 로 기동
  (loan/operation 과 동일한 운영 특성).
