# AI 경험 어필 — 면접용 정리

> Lemuel(이커머스 + 정산 MSA) 프로젝트에서 "AI 기반 서비스 개발 또는 운영" 경험을 어떻게 말할지 정리한 문서.
> 모든 항목은 저장소에 실제로 존재하는 코드 기준이며, 근거 경로를 §5 에 인덱스로 붙였다.
> **과장 금지 원칙** — 개인 프로젝트라는 사실과 미구현 범위를 §4 에서 먼저 밝힌다.

---

## 1. 30초 답변 (엘리베이터 피치)

> "AI 를 붙여본 경험보다, **AI 를 돈 다루는 시스템에 붙일 때 생기는 문제를 아키텍처로 막아본 경험**이 있습니다.
> 24개 서비스 MSA 안에 LLM 챗봇 서비스(`ai-service`)를 헥사고날로 하나 세웠는데, 거기서 실제로 해결한 건
> ① 외부 LLM 호출(최대 30초)이 DB 커넥션을 붙들어 풀을 고갈시키는 문제를 **트랜잭션 경계를 3단으로 쪼개**
> 해결했고, ② 사용자가 채팅창에 카드번호·주민번호를 붙여넣는 유출 경로를 **저장 전·전송 전 단일 초크포인트**로
> 막았고, ③ LLM 종량 과금 폭주를 **Bucket4j 사용자별 분/일 상한**으로 막았습니다.
> 그리고 뉴스 평판 서비스에서는 LLM 감성분석을 **포트 뒤에 두고 쿼터 초과 시 룰 기반으로 자동 폴백(fail-open)**
> 하게 만들어서, LLM 이 죽어도 도메인 기능은 계속 돌아가게 설계했습니다."

핵심 메시지 한 줄: **"LLM 은 느리고, 비싸고, 실패하고, 데이터를 밖으로 내보낸다 — 이 4가지를 각각 코드로 방어했다."**

---

## 2. 상세 — 4개 축

### A축. LLM 애플리케이션 개발 — `ai-service` (port 8096, DB `lemuel_ai`)

대화형 AI 챗봇을 **독립 마이크로서비스 + 헥사고날**로 구현. DB-per-service, 자체 Flyway 마이그레이션 소유.

| 관심사 | 구현 |
|---|---|
| **멀티 프로바이더** | `GeminiChatAdapter`(RestClient 직접) / `AnthropicChatAdapter`(Spring AI SDK) — `app.ai.provider` 로 `@ConditionalOnProperty` 하여 **정확히 하나만** 빈 등록. 애플리케이션 계층은 `ChatCompletionPort` 만 알고 프로바이더를 모름 |
| **아키텍처 격리** | LLM SDK 의존이 `adapter/out/llm` 패키지 밖으로 새지 않도록 **ArchUnit 으로 강제**(`AiArchitectureTest`) |
| **스트리밍** | SSE 델타 전송 (`Consumer<String> onDelta` 콜백으로 유스케이스와 전송 방식 분리) |
| **컨텍스트 관리** | `history-window: 10` — 직전 10개 메시지만 재주입, 비용 상한의 1차 장치 |
| **환각 방어** | 시스템 프롬프트에 "실제 주문·정산 금액은 조회 불가하므로 **지어내지 말고 '연동 예정'이라고 안내**" 명시. Function Calling 이 붙기 전까지 "모른다"가 정답이라는 정책 |

**면접에서 가장 잘 먹히는 3가지:**

1. **트랜잭션 경계 설계** — LLM 호출은 타임아웃 30초다. 이걸 `@Transactional` 안에서 하면 DB 커넥션을 30초간
   붙든 채 외부 API 를 기다리게 되어 HikariCP 풀이 고갈된다. 그래서 `ChatService` 를
   **조회(무tx) → LLM 호출(무tx) → 저장(단일 tx)** 3단으로 분리했다.
   부수 효과로 "LLM 실패 시 아무것도 저장하지 않는다"는 원칙이 트랜잭션 구조에서 자연히 성립한다.
   *(→ `ChatService.java` 클래스 주석에 설계 근거를 남겨둠)*

2. **PII 단일 초크포인트** — 돈을 다루는 플랫폼의 채팅창은 사용자가 실수로 카드번호를 붙여넣기 쉬운 유출 경로다.
   마스킹하지 않으면 세 갈래로 샌다: ① `chat_messages.content` 평문 저장 ② 대화 제목 ③ **외부 LLM 전송**.
   `PiiMasker`(프레임워크 의존 0 인 순수 도메인 클래스)를 `ChatService` 진입부에 한 번만 적용해 세 경로를 동시에 막았다.
   - 정책은 **차단이 아니라 마스킹** — 사용자 실수로도 대화는 이어지되 원문은 남기지 않는다.
   - 카드번호는 13~19자리 숫자를 후보로 잡되 **Luhn 체크섬을 통과한 것만** 마스킹해 오탐(주문번호 등)을 줄였다.
   - 주민번호는 성별/세기 자리(1~8) 제약으로 임의의 13자리 숫자 오탐을 걸러낸다.
   - 저장 시에는 추가로 컬럼 암호화(`ChatContentEncryptionConverter`)를 건다.

3. **비용 가드** — LLM 은 호출 = 실비용이다. JWT USER 이상 인증 필수 + Bucket4j 로 **사용자별 분 5회 / 일 100회**
   상한, 초과 시 `429 + Retry-After`. 여기에 `history-window`(컨텍스트 상한)와 `max-tokens` 로 건당 비용도 캡을 씌웠다.

**운영성 디테일:** 메시지 테이블 파티션 자동 유지보수(`PartitionMaintenanceRunner`), API 키 미설정 시
**부팅·이력 조회는 정상 동작하고 채팅만 503 안내**(키 없다고 서비스가 죽지 않음), LLM 실패 시 폴백 없이 503 + 무저장.

### B축. LLM 을 기존 도메인 파이프라인에 얹기 — `company-service` 뉴스 감성분석

이쪽이 오히려 **"AI 를 프로덕션에 붙인다"는 관점에서 더 어필되는 사례**다. 챗봇은 AI 가 곧 기능이지만,
여기서는 **AI 가 죽어도 도메인 기능(기업 평판 산정)은 계속 돌아가야 한다.**

- `AnalyzeSentimentPort` 라는 아웃바운드 포트 하나 뒤에 분석기 3종을 두고 설정으로 무중단 교체:
  - `KeywordSentimentAnalyzer` — 룰 기반(부정 키워드 → 이슈 카테고리 FINANCIAL→LEGAL→GOVERNANCE→LABOR→PRODUCT 우선순위). **기본값**
  - `GeminiSentimentAnalyzer` / `LlmSentimentAnalyzer` — LLM 기반
  - `QuotaGuardedSentimentAnalyzer` — `@Primary` 데코레이터로 LLM 앞에 끼어들어 **일일 호출 상한 + 분당 스로틀** 강제
- **fail-open 철학**: 쿼터를 넘겨도 예외를 던지지 않고 그날은 키워드 폴백으로 계속 산정한다.
  AI 가 부가 기능일 때는 "AI 실패 = 서비스 실패"가 되면 안 된다는 판단.
- **캐시로 호출량 자체를 줄임**: `SentimentCachePort` 로 신규 기사만 분석 → 쿼터 가드는 평상시 거의 닿지 않는 안전망.
- **의도적으로 감수한 트레이드오프**: 상한 도달분(대량 콜드스타트)은 키워드 결과로 캐시되고 이후 LLM 으로 재승격되지 않는다.
  **정확도보다 비용 예측 가능성을 우선**한 선택 — 면접에서 트레이드오프 질문이 오면 이 대목을 쓴다.

> A축과 B축을 같이 말하면 **"AI 가 주기능일 때(503 로 정직하게 실패)"와 "AI 가 부가기능일 때(폴백으로 계속)"의
> 실패 정책을 다르게 설계했다**는 판단력을 보여줄 수 있다. 이게 이 문서에서 가장 강한 카드다.

### C축. ML/통계 기반 분석 서비스 — Python 폴리글랏 3종 + 운영 이상탐지

JVM 이 잘 못 채우는 데이터/ML 공백을 Python 으로 분리 (Gradle 밖 standalone FastAPI, 별도 CI `polyglot-ci.yml`).

| 서비스 | 포트 | 기법 |
|---|---|---|
| `settlement-anomaly-service` | 8121 | 정산/payout 이상탐지 — **MAD z-score + IsolationForest 앙상블** (scikit-learn 1.6) |
| `forecast-service` | 8122 | 정산액·매출 시계열 예측 — **Holt-Winters + seasonal-naive 베이스라인** (statsmodels) |
| `screening-backtest-service` | 8120 | 투자 스크리닝 백테스트 (수익률·MDD·Sharpe·승률, pandas — ML 아님) |

- **언어 선택 근거를 설명할 수 있다는 게 포인트**: Python 은 데이터/ML/퀀트(pandas·scikit-learn·statsmodels),
  Go 는 저지연 동시성(SSE 스트리밍·웹훅), Kotlin 은 JVM 생태를 유지하며 이벤트 팬아웃. 폴리글랏을 취향이 아니라
  워크로드 특성으로 갈랐다.
- **통계 기법 선택도 근거가 있다**: 이상탐지를 ML 단독이 아니라 **MAD z-score(로버스트 통계) + IsolationForest 앙상블**로
  간 이유는, 정산 금액 분포가 외곽값에 민감해 평균/표준편차 기반 z-score 가 이상치 자체에 오염되기 때문.
  예측도 Holt-Winters 옆에 **seasonal-naive 베이스라인을 같이 둬서** 모델이 단순 베이스라인을 이기는지 비교 가능하게 했다.
- 추가로 Java 쪽 `operation-service/anomaly/` 는 **규칙·베이스라인 기반**(rolling window baseline → 임계 판정 →
  인시던트 자동 생성)의 상시 스케줄러 이상탐지다. **ML 모델이 아니라는 점을 정직하게 구분해서 말할 것.**

### D축. AI 에이전트 기반 운영 자동화 — MCP 도구 서버

정산 운영 질의를 LLM 에이전트가 **자연어로 받아 내부 API 를 도구 호출로 조회**하는 MCP 플러그인 3종을 만들어,
각 플러그인을 **호출 대상 서비스의 리소스 디렉터리에 함께 배치**했다.

- `settlement-copilot` (settlement-service) — 정산 정합성 MCP 도구 15종: 대사 실행, 원장 완전성, 홀드백 상태,
  outbox 적체, 프로젝션 지연, stuck 상태 조회 등
- `fashion-copilot` (order-service) — 커머스 4축(반품/드랍재고/리뷰/쿠폰)
- `pwc trusted-ceo-agent` (company-service) — 기업 신호 기반 분기 브리핑 자동 생성 → 문서함 업로드까지 배치 실행

여기서 말할 수 있는 것: **"에이전트에게 도구를 주는 쪽"의 설계** — 도구 반환 스키마를 좁게 고정하고,
보장·단정 표현을 가드로 차단하고, 스모크 테스트로 도구 개수를 하드 어서트해 계약 드리프트를 막았다.

---

## 3. 예상 질문 대비

**Q. LLM 응답이 느린데 사용자 경험은 어떻게 처리했나요?**
A. SSE 스트리밍으로 델타를 즉시 흘려보냅니다. 다만 더 중요한 건 서버 쪽인데, 30초 타임아웃 호출을 트랜잭션 안에서
하면 커넥션 풀이 먼저 죽습니다. 그래서 조회/호출/저장을 tx 경계로 3단 분리했습니다.

**Q. LLM 비용은 어떻게 통제했나요?**
A. 3중입니다. ① 사용자별 Bucket4j 분5/일100 (초과 429) ② 컨텍스트 윈도 10개 + max-tokens 로 건당 상한
③ 감성분석 쪽은 캐시로 신규 기사만 호출하고, 그 위에 일일 쿼터 + 분당 스로틀 데코레이터를 얹었습니다.

**Q. LLM 이 장애날 때는요?**
A. **기능 성격에 따라 정책을 다르게 뒀습니다.** 챗봇은 AI 가 곧 기능이라 폴백으로 그럴듯한 답을 만들지 않고
503 + 무저장으로 정직하게 실패시킵니다. 반면 뉴스 감성분석은 부가 기능이라 fail-open — 룰 기반 분석기로
자동 폴백해 평판 산정은 계속됩니다.

**Q. AI 에 회사 데이터가 새는 문제는요?**
A. 저장 전·외부 전송 전을 **하나의 초크포인트**로 합쳐서 마스킹했습니다. 여러 군데서 막으면 한 군데는 반드시 빠집니다.
카드번호는 Luhn 검증으로 오탐을 줄였고요. 나아가 실데이터 조회 도구를 안 붙인 현재 단계에서는 시스템 프롬프트로
"실제 금액은 지어내지 말라"를 강제해 환각으로 인한 잘못된 금액 안내를 원천 차단했습니다.

**Q. RAG 는 해봤나요?** (→ 정직하게)
A. 아직입니다. 다만 확장을 전제로 DB 이미지를 `pgvector/pgvector:pg17` 로 선점해뒀고(현재는 확장 미활성),
로드맵은 Function Calling(실데이터 조회 도구) → RAG 순입니다. 지금 단계에서 실데이터를 못 보는 상태이기 때문에
오히려 "모른다고 답하게 만드는 것"을 안전장치로 설계했습니다.

---

## 4. 정직하게 먼저 밝힐 한계 (묻기 전에 말하기)

- **개인 프로젝트이고 실운영 트래픽 경험은 아닙니다.** 실 API 키 연동과 로컬/홈랩 기동까지는 검증했지만
  프로덕션 사용자 트래픽을 받은 적은 없습니다.
- **Python 3종은 무영속 MVP** — 번들 샘플 데이터 기반이고, 실데이터 파이프라인·모델 학습·배포 차트는 각 README 의
  TODO 로 분리해뒀습니다. gateway 라우팅도 안 붙어 있습니다(폴리글랏 중 `market-stream`·`notification` 만 SSE 라우팅됨).
- **RAG·Function Calling 은 미구현(로드맵)**, 모델 파인튜닝·자체 모델 서빙 경험은 없습니다.
- `operation-service` 의 이상탐지는 **ML 이 아니라 통계·규칙 기반**입니다.

> 이 한계들을 먼저 말하는 편이 유리하다. 이 프로젝트의 강점은 "AI 를 많이 써봤다"가 아니라
> **"AI 를 시스템에 편입시킬 때의 실패·비용·유출 문제를 아키텍처로 다뤘다"** 이기 때문.

---

## 5. 근거 파일 인덱스

**A축 — ai-service**
- `ai-service/src/main/java/github/lms/lemuel/ai/chat/application/service/ChatService.java` — tx 경계 3단 분리 근거 주석
- `ai-service/src/main/java/github/lms/lemuel/ai/chat/domain/PiiMasker.java` — PII 단일 초크포인트, Luhn 검증
- `ai-service/src/main/java/github/lms/lemuel/ai/chat/adapter/out/llm/{Gemini,Anthropic}ChatAdapter.java` — 프로바이더 스위치
- `ai-service/src/main/java/github/lms/lemuel/ai/chat/adapter/out/ratelimit/Bucket4jRateLimiter.java` — 비용 가드
- `ai-service/src/main/java/github/lms/lemuel/ai/chat/adapter/out/persistence/ChatContentEncryptionConverter.java` — 컬럼 암호화
- `ai-service/src/main/java/github/lms/lemuel/ai/config/PartitionMaintenanceRunner.java` — 파티션 유지보수
- `ai-service/src/test/java/github/lms/lemuel/ai/AiArchitectureTest.java` — LLM 의존 격리 ArchUnit
- `ai-service/src/main/resources/application.yml` — provider/rate-limit/system-prompt/pgvector 이미지 주석

**B축 — company-service 감성분석**
- `company-service/src/main/java/github/lms/lemuel/company/adapter/out/analysis/QuotaGuardedSentimentAnalyzer.java` — 쿼터·스로틀·fail-open
- `.../analysis/KeywordSentimentAnalyzer.java` — 룰 기반 폴백
- `.../analysis/{Gemini,Llm}SentimentAnalyzer.java` · `application/port/out/AnalyzeSentimentPort.java` — 포트 뒤 무중단 교체
- `company-service/src/main/resources/db/migration/V20260719100000__article_sentiment_cache.sql` — 분석 결과 캐시

**C축 — ML/통계**
- `settlement-anomaly-service/` (MAD + IsolationForest) · `forecast-service/` (Holt-Winters) · `screening-backtest-service/`
- `operation-service/src/main/java/github/lms/lemuel/operation/anomaly/` — 통계·규칙 기반 상시 이상탐지
- `../polyglot-services.md` — 폴리글랏 7종 정본(언어 선택 근거·알려진 한계)

**D축 — MCP 에이전트 도구**
- `settlement-service/src/main/resources/settlement-copilot/` · `order-service/src/main/resources/fashion-copilot/` · `company-service/src/main/resources/pwc/`

**참고 문서**: `SPEC.md` §3.9(ai-service) · §3.x(company) · ADR 0023 · `docs/ARCHITECTURE.md`
