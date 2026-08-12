# ADR 0034 — ai-service RAG 지식베이스 (pgvector + 시스템 프롬프트 증강)

- 상태: **Proposed** — 1단계 구현 완료, `app.ai.rag.enabled=false` 로 비활성 배포
  - **무행동 착지**: 기본값이 꺼짐이고, 켜도 지식베이스가 비어 있으면 LLM 에 실려 가는 바이트가
    Phase 1 과 **완전히 동일**하다. 지식을 넣기 전에는 어떤 답변도 달라지지 않는다.
- 일자: 2026-08-12
- 관련: ADR 0032(수수료율 유효기간 정책 — "정책을 코드에서 데이터로" 라는 같은 동기),
  ADR 0020(order↔settlement DB 물리 분리 — 서비스별 DB 소유 원칙)
  - ai-service 챗봇 Phase 1 자체는 **ADR 이 없다**(코드와 `application.yml` 이 유일한 기록).
    본 ADR 이 그 확장이므로, 컨텍스트 절에 Phase 1 의 현재 상태를 직접 인용해 둔다.
- 배경: Phase 1 챗봇은 시스템 프롬프트에 정책을 **하드코딩**해 두고, 그 밖의 질문에는
  "지어내지 말고 모른다고 답하라"로 막고 있다. 정책이 바뀌면 배포해야 하고, 문서 한 편 분량은
  프롬프트에 들어가지 않는다.

## 컨텍스트

현재 챗봇이 아는 도메인 지식의 전부는 `application.yml` 의 시스템 프롬프트 한 문단이다.

```yaml
# ai-service/src/main/resources/application.yml (Phase 1)
system-prompt: >-
  Lemuel 은 주문/결제, 셀러 정산(등급별 수수료 NORMAL 3.5%/VIP 2.5%/STRATEGIC 2.0%,
  정산주기 T+7/T+3/T+1 영업일), 선정산 대출 … 을 제공합니다.
```

### 한계 4가지

| 요구                                    | 현재 가능?                                                                      |
| --------------------------------------- | ------------------------------------------------------------------------------- |
| "정산 정책 문서 전문을 근거로 답해라"   | ✗ — 프롬프트 길이·비용 상한에 걸린다                                            |
| "수수료율이 바뀌었으니 답변도 바뀌어라" | ✗ — **yml 수정 + 배포**. ADR 0032 가 요율을 데이터로 옮긴 것과 정확히 같은 문제 |
| "무슨 근거로 그렇게 답했나"             | ✗ — 프롬프트 전체가 근거라 특정할 수 없다                                       |
| "문서에 없는 건 없다고 답해라"          | △ — 지시는 있지만 대조할 자료가 없어 검증 불가                                  |

두 번째가 특히 나쁘다. ADR 0032 로 수수료율은 이미 데이터가 됐는데, **챗봇이 말하는 수수료율은
여전히 코드에 박힌 문자열**이다. 즉 지금 이 순간에도 두 값이 어긋날 수 있다.

## 결정 포인트

### 1. 벡터 저장소를 무엇으로?

→ **PostgreSQL + pgvector.** 전용 벡터 DB(Qdrant·Milvus 등)를 새로 띄우지 않는다.
현재 지식베이스 규모는 내부 문서 수십 편(청크 수천 건)이고, 이 규모에서 전용 엔진의 이점은
운영 대상 1개 추가·백업 체계 이중화·네트워크 홉 추가 비용을 넘지 못한다. ai-service 는 이미
자기 DB(`lemuel_ai`)를 소유하며(서비스별 DB 소유 — ADR 0020 의 연장), 그 인스턴스에는
`vector 0.8.6` 이 **이미 설치돼 있다**(2026-08-12 실측). 즉 인프라 변경분이 0 이다.

### 2. 어느 DB 에 넣는가? (★ 이 결정이 가장 중요하다)

→ **ai-service 의 `lemuel_ai`. settlement-service 의 `lemuel_settlement` 에는 넣지 않는다.**

기술 취향이 아니라 **권한 문제**다. pgvector 는 _trusted extension_ 이 아니다 — `vector.control` 에
`trusted = true` 가 없고, 실제 인스턴스의 `pg_available_extension_versions` 도
`superuser=t, trusted=f` 로 보고한다. 즉 `CREATE EXTENSION vector` 는 **슈퍼유저만** 할 수 있다.

| DB (인스턴스)                      | 앱 계정      | 슈퍼유저?          | vector 확장              |
| ---------------------------------- | ------------ | ------------------ | ------------------------ |
| `lemuel_ai` (ai-postgres)          | `settlement` | **예** (DB 소유자) | **0.8.6 설치됨**         |
| `lemuel_settlement` (jen-postgres) | `settlement` | 아니오             | 사용 가능하나 **미설치** |

settlement-service 의 마이그레이션에 벡터 DDL 을 넣으면 `insufficient_privilege` 로 Flyway 가 실패하고
**정산 서비스 전체가 CrashLoop** 에 빠진다. 정산은 돈을 다루는 경로다 — 부가 기능인 챗봇 지식베이스가
그 부팅 경로에 위험을 추가할 이유가 없다.

### 3. 확장 생성 실패를 경고로 낮출 것인가?

→ **낮추지 않는다. 예외로 부팅을 멈춘다.**

리포에는 선례가 있다 — `V20260807130000__pg_stat_statements_extension.sql` 은 권한 부족·파일 부재를
`RAISE WARNING` 으로 강등한다. 그 판단은 그 맥락에서 옳다: `pg_stat_statements` 가 없어도 애플리케이션은
정상 동작하고 관측만 빈다. 여기는 다르다. 확장 생성을 조용히 건너뛰면 **바로 다음 문장**이
`type "vector" does not exist` 로 죽는다 — 진짜 원인(권한/이미지)과 무관한 메시지로.
그래서 원인을 아는 지점에서 원인을 말하며 실패한다. 실패 메시지에 해결 방법(`HINT`)을 함께 넣었다.

### 4. 임베딩 차원을 몇으로?

→ **768.** 모델 기본값 3072 를 쓰지 않는다.

`gemini-embedding-001` 의 기본 출력은 3072 차원이다. 그런데 pgvector 의 `vector` 타입은
**2,000 차원까지만 인덱싱**된다(공식 README). 3072 로 테이블은 만들어지지만 매 질의가 전량 스캔이 된다.
이 모델은 MRL(Matryoshka Representation Learning)로 학습돼 앞쪽 차원만 잘라 써도 의미가 보존되며,
Google 문서가 768/1536/3072 절단을 권고한다.

단, 문서는 **`gemini-embedding-001` 을 3072 아닌 차원으로 쓸 때 L2 정규화를 직접 해야 한다**고 명시한다.
잘린 벡터는 단위벡터가 아니므로:

$$\|v\|_2 = \sqrt{\sum_i v_i^2}, \qquad \hat{v} = \frac{v}{\|v\|_2}$$

정규화를 빼먹으면 코사인 유사도가 벡터 길이에 오염돼 **예외 없이 순위만 틀린다.** 어댑터의
성실함에 맡기지 않고 값 객체(`Embedding`)의 연산으로 못박았다.

### 5. 근거를 사용자 메시지에 붙이는가, 시스템 프롬프트에 붙이는가?

→ **시스템 프롬프트.**

사용자 발화에 근거를 끼워 넣는 편이 구현은 쉽다(포트 하나를 데코레이터로 감싸면 된다). 그러나 그
합성 텍스트가 `chat_messages` 에 **영구 저장**되고, 다음 턴의 히스토리 윈도(10건)에 실려 컨텍스트를
잠식하며, 이력 조회 UI 에 참고 자료가 그대로 노출된다. 시스템 프롬프트는 매 요청 새로 조립되고
저장되지 않으므로 근거가 그 턴에만 살아 있다.

### 6. 검색이 실패하면?

→ **경고 로그 + 근거 0건으로 강등.** LLM 실패(503)와 **정책을 다르게 한다.**

LLM 이 죽으면 답 자체가 없지만, 검색이 죽어도 *근거 없는 답*은 여전히 가능하다. 부가 기능의 장애가
챗봇 전체를 멈추게 두지 않는다. 반대로 임베딩 **키 미설정**은 적재 시 503 이다 — 적재는 "저장했다"는
약속이므로 조용히 실패하면 안 된다.

### 7. 적재 권한은?

→ **ADMIN 전용.** 검색 조회는 USER 이상.

적재된 텍스트는 이후 **모든 사용자**의 답변 근거로 프롬프트에 실린다. 즉 적재는 사실상 *챗봇의 발언
내용에 대한 쓰기 권한*이다. USER 에게 열면 **영구 저장되는 프롬프트 인젝션 창구**가 된다.
검색 조회를 USER 에게 열어 두는 것은 진단 목적이다 — 답이 이상할 때 검색이 무엇을 찾았는지 봐야
원인을 검색 단계와 LLM 단계로 나눌 수 있다.

## 설계

### 1. 스키마 (`lemuel_ai`, `V20260812150000__rag_knowledge_base.sql`)

```sql
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE EXCEPTION 'pgvector 확장 생성 권한이 없습니다 …' USING HINT = '슈퍼유저로 먼저 CREATE EXTENSION vector; …';
    WHEN undefined_file THEN
        RAISE EXCEPTION 'pgvector 확장 파일이 없습니다 …' USING HINT = '이미지를 pgvector/pgvector:pg17 로 교체할 것 …';
END
$$;

CREATE TABLE knowledge_documents (
    id            UUID         PRIMARY KEY,
    title         VARCHAR(200) NOT NULL,
    source_uri    VARCHAR(500) NOT NULL,
    content_hash  CHAR(64)     NOT NULL,          -- SHA-256 hex. 같으면 재임베딩 스킵
    chunk_count   INTEGER      NOT NULL DEFAULT 0,
    ingested_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_knowledge_documents_source UNIQUE (source_uri)
);

CREATE TABLE knowledge_chunks (
    id              BIGSERIAL   PRIMARY KEY,
    document_id     UUID        NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index     INTEGER     NOT NULL,
    content         TEXT        NOT NULL,
    embedding       vector(768) NOT NULL,
    embedding_model VARCHAR(60) NOT NULL,          -- 벡터 공간의 출처
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_knowledge_chunks_doc_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_knowledge_chunks_embedding_hnsw
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);
```

`embedding_model` 을 청크마다 저장하는 것이 조용한 오답을 막는 핵심이다. 모델을 갈면 벡터 공간이
달라지는데, 옛 벡터와 새 질의 벡터를 섞어 비교해도 **에러가 나지 않고 순위만 무의미해진다.**
검색은 항상 현재 모델로 필터하므로, 모델 교체 시 결과가 0건이 되어 근거 없는 답(= Phase 1 동작)으로
안전하게 착지한다.

### 2. 패키지 구조

```
ai-service/src/main/java/github/lms/lemuel/ai/
├── rag/
│   ├── domain/            Embedding · TextChunker · ContentHash · KnowledgeDocument
│   │                      EmbeddedChunk · RetrievedChunk
│   ├── application/
│   │   ├── port/in/       IngestKnowledgeUseCase · SearchKnowledgeUseCase
│   │   ├── port/out/      EmbeddingPort · KnowledgeBasePort
│   │   └── service/       IngestKnowledgeService · KnowledgeRetrievalService
│   └── adapter/
│       ├── in/web/        KnowledgeController (+ DTO)
│       ├── out/llm/       GeminiEmbeddingAdapter (RestClient)
│       └── out/persistence/ KnowledgeBaseJdbcAdapter (JdbcTemplate)
└── chat/
    ├── domain/            RetrievedContext · KnowledgePrompt   (신규)
    └── application/port/out/ RetrieveContextPort               (신규)
```

**의존 방향:** `chat` 은 `rag` 를 모른다. chat 이 선언한 `RetrieveContextPort` 에
`KnowledgeRetrievalService` 가 꽂힌다(의존성 역전). chat 을 rag 없이도 그대로 컴파일·테스트할 수 있다.

`KnowledgeRetrievalService` 는 `SearchKnowledgeUseCase` 와 `RetrieveContextPort` 를 **한 클래스로**
구현한다. 검색 API 가 보여주는 결과와 챗봇이 실제로 근거로 쓴 결과가 같은 코드에서 나와야
"API 로는 잘 찾는데 챗봇은 왜 못 찾나" 같은 재현 불가 상황이 생기지 않는다.

### 3. JPA 를 쓰지 않는 이유

ai-service 는 `spring.jpa.hibernate.ddl-auto: validate` 로 뜬다. Hibernate 가 모르는 `vector` 타입
컬럼을 `@Entity` 로 매핑하면 스키마 검증 단계에서 **부팅이 실패**하고, 통과시키려면 커스텀
`UserType` + 방언 확장이 필요하다. 벡터 검색은 어차피 `<=>` 연산자와 HNSW 인덱스를 직접 다뤄야 하는
네이티브 영역이라 `JdbcTemplate` 이 정직한 선택이다(선례: `config/PartitionMaintenanceRunner`).

Spring AI 를 쓰지 않은 것도 의도다 — ArchUnit 이 `org.springframework.ai..` 를
`..ai.chat.adapter.out.llm..` 안으로 제한하고 있고, 임베딩 호출은 REST 한 번이라 `RestClient` 로 충분하다.

### 4. 검색 쿼리

```sql
SELECT d.title, d.source_uri, c.content, 1 - (c.embedding <=> ?::vector) AS similarity
  FROM knowledge_chunks c
  JOIN knowledge_documents d ON d.id = c.document_id
 WHERE c.embedding_model = ?
 ORDER BY c.embedding <=> ?::vector
 LIMIT ?
```

`<=>` 는 코사인 **거리**(0=동일)이므로 $\text{similarity} = 1 - \text{distance}$ 로 바꿔 올린다.
유사도 하한 필터는 **SQL 이 아니라 애플리케이션에서** 한다 — 거리식을 `WHERE` 로 옮기면
HNSW 인덱스의 `ORDER BY … LIMIT` 경로를 벗어날 수 있다. 인덱스로 top-k 를 먼저 뽑고 나서 걸러낸다.

### 5. 적재 파이프라인

```
본문 → PiiMasker.mask → ContentHash.of → (해시 동일? → 스킵)
     → TextChunker.chunk(1200자/200자 겹침) → embedDocuments(RETRIEVAL_DOCUMENT)
     → [단일 트랜잭션] 문서 UPSERT + 청크 전량 교체
```

- **PII 마스킹을 여기서도 한다.** 청크는 프롬프트에 실려 외부 LLM 으로 나가는 **새로운 유출 경로**다.
  관리자가 넣는 내부 문서라 해도 계좌·카드번호가 섞일 수 있다. 규칙을 복제하지 않고 채팅과 같은
  `PiiMasker` 를 재사용한다 — 규칙이 두 벌이 되면 한쪽만 갱신되는 것이 가장 나쁜 결과다.
- **임베딩은 트랜잭션 밖.** 청크 수만큼 외부 호출이라 수십 초가 걸릴 수 있고, 그 사이 커넥션을 붙들면
  풀이 고갈된다(Hikari 최대 10). ChatService 와 같은 규율이다.
- **청크는 전량 교체.** 일부만 갱신된 상태는 옛 답과 새 답이 섞인 근거라 어떤 예외보다 나쁘다.
- **`task_type` 비대칭.** 문서는 `RETRIEVAL_DOCUMENT`, 질의는 `RETRIEVAL_QUERY` 로 임베딩한다.
  이 값을 잘못 주면 에러 없이 검색 품질만 떨어지므로, 포트 시그니처를
  `embedDocument`/`embedQuery` 로 **나눠** 호출자가 고를 수 없게 했다.

### 6. 무행동 착지 3중 장치

| 층          | 조건                                  | 결과                                          |
| ----------- | ------------------------------------- | --------------------------------------------- |
| 1. 빈 없음  | `app.ai.rag.enabled=false` (**기본**) | `RagWiringConfig` 의 "근거 0건" 포트가 꽂힌다 |
| 2. 키 없음  | 임베딩 API 키 미설정                  | 검색이 빈 리스트                              |
| 3. 지식 0건 | 이 모델의 청크 없음                   | **임베딩 호출조차 하지 않고** 빈 리스트       |

세 경우 모두 `KnowledgePrompt.augment` 가 원본 프롬프트를 **그대로(동일 객체)** 반환한다.
LLM 에 실려 가는 바이트가 Phase 1 과 동일하다는 뜻이다.

### 7. 불변식 (테스트로 못박은 것)

1. 근거 0건이면 시스템 프롬프트가 **바이트 동일**하다.
2. 근거는 시스템 프롬프트에만 실린다 — 사용자 메시지·저장된 이력은 오염되지 않는다.
3. 검색 실패는 대화를 중단시키지 않는다(503 아님, rate limit 환불도 없음).
4. 임베딩 벡터는 항상 L2 정규화된다. 영벡터는 거부(NaN 이 DB 로 들어가는 것을 막는다).
5. 청크 수 ≠ 임베딩 수 이면 **저장 전에** 실패한다(어긋난 벡터 = 조용한 오답).
6. 본문 해시가 같으면 재임베딩하지 않는다.
7. 유사도 하한 미달 근거는 버린다 — 무관한 근거는 없는 것보다 나쁘다(환각을 부추긴다).
8. 청크 겹침 ≥ 청크 길이 설정은 **부팅 시점에** 거부한다(런타임 무한 루프 방지).
9. 적재/삭제는 ADMIN, 무인증은 401, USER 적재는 403.

### 8. API

```
POST   /api/ai/knowledge/documents               # 적재/교체 (ADMIN)
DELETE /api/ai/knowledge/documents?sourceUri=    # 삭제 → 204 / 404 (ADMIN)
GET    /api/ai/knowledge/search?q=&topK=         # 검색 확인 (USER 이상)
```

## 결과

### 좋아지는 점

- 챗봇 지식이 **배포 없이** 갱신된다 — ADR 0032 가 수수료율에 한 것을 답변 근거에 한다.
- 답변이 어떤 문서를 근거로 삼았는지 제목으로 드러난다(환각 금지 지시와 함께).
- 프롬프트 길이 상한과 무관하게 문서 분량을 늘릴 수 있다.
- 새 인프라 0개 — 기존 `lemuel_ai` 안에서 끝난다.

### 트레이드오프 / 리스크

- **적재가 곧 챗봇 발언에 대한 쓰기 권한**이다. ADMIN 제한 + PII 마스킹으로 좁혔지만,
  잘못된 문서를 넣으면 챗봇이 그 잘못을 근거로 답한다. 운영 절차(검토 후 적재)가 필요하다.
- 임베딩 호출이 실비용이다. `content` 200KB 상한(≈170청크)이 요청 1건의 비용 천장이다.
- 배치 임베딩 엔드포인트(`:batchEmbedContents`)를 쓰지 않고 순차 호출한다 — 문서로 확인하지 못한
  API 를 추측으로 붙이지 않았다. 대량 적재 시 느리다(2단계 개선 후보).
- HNSW 는 근사 검색이다. `hnsw.ef_search`(기본 40) 튜닝은 지식이 쌓인 뒤 실측으로 결정한다.
- 768 차원 절단은 3072 대비 품질 손실이 있을 수 있다. 중립 벤치마크로 검증하지 않았다 —
  절단을 택한 근거는 품질이 아니라 **인덱스 가능성**이다.

## 대안 검토

| 옵션                                          | 채택?          | 이유                                                                                                                    |
| --------------------------------------------- | -------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **pgvector + `vector(768)` + HNSW (본 결정)** | ✓              | 인프라 추가 0 · 인덱스 가능 · 무행동 착지                                                                               |
| `halfvec(3072)`                               | ✗ (2단계 후보) | 4,000 차원까지 인덱스 가능해 절단이 불필요하지만, 지금 품질 문제가 관측되지 않았다. 실측 없이 복잡도를 먼저 사지 않는다 |
| `vector(3072)` 인덱스 없이                    | ✗              | 매 질의 전량 스캔. 청크 수천 건에서 이미 못 쓴다                                                                        |
| 전용 벡터 DB (Qdrant 등)                      | ✗              | 운영 대상 +1 · 백업 체계 이중화 · 네트워크 홉. 현 규모에서 이점 없음                                                    |
| settlement-service DB 에 적재                 | ✗              | 앱 계정이 슈퍼유저가 아니라 `CREATE EXTENSION` 실패 → **정산 CrashLoop**(결정 포인트 2)                                 |
| 프롬프트에 문서 전문 삽입 (RAG 없음)          | ✗              | 컨텍스트·비용 상한. 문서가 늘면 즉시 파탄                                                                               |
| 파인튜닝                                      | ✗              | 정책 변경 반영이 재학습 주기에 묶인다. 근거 추적도 불가                                                                 |
| Spring AI VectorStore 추상화                  | ✗              | ArchUnit 이 `org.springframework.ai..` 를 chat LLM 어댑터로 제한 · 호출 1개에 프레임워크 결합 불필요                    |
| 근거를 사용자 메시지에 prepend                | ✗              | 근거가 이력에 영구 저장되고 히스토리 윈도를 잠식(결정 포인트 5)                                                         |

## 구현 체크리스트

- [x] 확장 가용성·권한 실측 — `lemuel_ai` 는 `vector 0.8.6` 설치 + 앱 계정 슈퍼유저,
      `lemuel_settlement` 는 미설치 + 비슈퍼유저(2026-08-12 실측). pgvector 는 `trusted=f`
- [x] Flyway `V20260812150000__rag_knowledge_base.sql` — 확장 + 2테이블 + HNSW 인덱스
- [x] `rag` 헥사곤 — 도메인 6종, 포트 4종, 서비스 2종, 어댑터 2종, 컨트롤러
- [x] chat 결선 — `RetrieveContextPort` · `KnowledgePrompt` · `ChatService` 증강
- [x] ADMIN 경로 매처 (`AiSecurityConfig`)
- [x] 도메인 단위 테스트 — 청킹 불변식 8건, `Embedding` 정규화/불변성 10건, 해시·값 객체 8건
- [x] 서비스 단위 테스트 — 적재 9건(스킵·마스킹·개수 불일치·503), 검색 8건(하한·모델 교체·무행동)
- [x] chat 회귀 테스트 — 근거 0건 시 프롬프트 동일, 검색 예외 시 대화 계속
- [ ] **통합 테스트 — 실 pgvector 로 SQL·HNSW·CASCADE 검증** (`RagKnowledgeIntegrationTest`).
      작성 완료했으나 **로컬 Docker 데몬 부재로 실행되지 않았다(skip). CI 에서 확인 필요** —
      이 항목이 열려 있는 동안 마이그레이션과 검색 SQL 은 실물로 검증되지 않은 상태다
- [x] `ChatFlowIntegrationTest` 컨테이너 이미지 → `pgvector/pgvector:pg17`
- [x] `mirror-testcontainers.yml` 에 `pgvector/pgvector:pg17` 추가
      (⚠️ 워크플로를 한 번 실행해 미러에 올려야 CI 가 당겨올 수 있다)
- [x] `./gradlew :ai-service:test :ai-service:jacocoTestCoverageVerification` 통과
      (2026-08-12: 145 테스트 · 실패 0 · 제외경로 적용 LINE 97.6%)
- [ ] 운영 활성화 — `APP_AI_RAG_ENABLED=true` + `knowledge_documents` 적재 (별도 승인 후)

## 참고 (1차 출처)

- pgvector README — 차원 상한(`vector` 2,000 / `halfvec` 4,000), 거리 연산자, HNSW 기본값:
  <https://github.com/pgvector/pgvector>
- Gemini Embeddings 문서 — 3072 기본 차원, MRL 절단 권고(768/1536/3072),
  **비기본 차원 사용 시 L2 정규화 필요**, `task_type` 값:
  <https://ai.google.dev/gemini-api/docs/embeddings>
- PostgreSQL 문서 — trusted extension 개념(`CREATE EXTENSION` 권한):
  <https://www.postgresql.org/docs/17/sql-createextension.html>
