# Seed — ai-service 챗봇 as-is 사양

> 상태: CONFIRMED (settlement/account/market seed 와 동일 방식 — 역산 결정화)
> 관련 스킬: `ai-chat-rules`(강제 규칙 정본)

## Goal (한 줄)

**ai-service(AI 챗봇 — PII 마스킹 초크포인트·provider 스위치·비용 가드·무폴백)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 경계 방어 근거 ·
면접/포트폴리오 문서로 쓴다.**

## 범위

**포함**

- PII 마스킹 단일 초크포인트와 마스킹 규칙(주민번호·카드번호)
- provider 스위치(gemini/anthropic)와 어댑터 격리
- 비용 가드(사용자별 분·일 이중 대역폭)
- LLM 실패 시 폴백 부재 정책과 저장 경계
- 대화 내용 저장 시 봉투 암호화

**제외**

- 각 LLM 공급자의 요청/응답 스키마 상세(어댑터 사정)
- RAG·임베딩 경로(별도 Seed 대상 — `Embedding`·pgvector 계열)

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `ai-service/src/main/java/github/lms/lemuel/ai/chat/`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **PII 마스킹은 단일 초크포인트** — 사용자 입력이 LLM 으로 나가기 전 한 지점에서만 마스킹된다 | `application/service/ChatService.java:84` (`PiiMasker.mask`) |
| 2 | **주민번호 마스킹** — 6자리 + 성별코드 1~8 + 6자리 패턴, 앞뒤 숫자 경계 확인 | `domain/PiiMasker.java:23,28,40` |
| 3 | **카드번호는 Luhn 검증 후에만 마스킹** — 13~19자리 후보 중 Luhn 통과한 것만 치환한다(전화번호·주문번호 오탐 방지) | `PiiMasker.java:26,49-51` |
| 4 | **provider 는 정확히 하나** — `app.ai.provider` 값으로 어댑터가 배타 등록된다(gemini 기본, anthropic 선택) | `adapter/out/llm/GeminiChatAdapter.java:38` (`matchIfMissing = true`) · `AnthropicChatAdapter.java:43` |
| 5 | **LLM 은 포트 뒤에 격리** — 도메인·서비스는 `ChatCompletionPort` 만 알고 공급자를 모른다 | `application/port/out/ChatCompletionPort.java` + `AiArchitectureTest` |
| 6 | **비용 가드는 이중 대역폭** — 사용자별 버킷 하나에 분당·일일 한도를 함께 걸고, 어느 쪽이든 먼저 소진되면 거부한다(기본 분 5회) | `adapter/out/ratelimit/Bucket4jRateLimiter.java:16-20,34` |
| 7 | **미설정과 장애를 구분한다** — 키 미설정은 `AiNotConfiguredException`, 호출 실패는 `AiUnavailableException` 으로 나뉘어 응답이 달라진다 | `ChatService.java:78,107` + `adapter/in/web/GlobalExceptionHandler.java:28-30` |
| 8 | **폴백 없음** — LLM 실패 시 대체 응답을 만들지 않고 예외를 그대로 올린다. 실패한 교환은 저장되지 않는다(저장은 성공 경로에서만) | `ChatService.java:107-111,118` |
| 9 | **대화 내용은 봉투 암호화 저장** — AES-GCM(256), `enc:v1:` 접두 + `Base64(IV ‖ ciphertext+tag)`. 키는 env `CHAT_ENC_KEY`(정확히 32바이트) | `adapter/out/persistence/ChatContentEncryptionConverter.java:22-23,37-38,54` |
| 10 | **3단 분리** — 마스킹 → LLM 호출 → 저장(단일 tx)이 분리돼 있어 외부 호출이 트랜잭션을 잡고 있지 않다 | `ChatService.java:33` |

## 이벤트 계약

**발행 0 · 소비 0** — Kafka 미사용. shared-common 은 JWT 만 제한 스캔.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 마스킹 없이 LLM 으로 나가는 경로가 없다 | `./gradlew :ai-service:test` — `ChatServiceTest` |
| AC-2 | 주민번호·카드번호 마스킹 규칙(Luhn 포함)이 경계까지 일치한다 | `PiiMasker` 단위 테스트 |
| AC-3 | provider 가 동시에 둘 등록되지 않는다 | `GeminiChatAdapterTest` · `AnthropicChatAdapterTest` |
| AC-4 | 분·일 한도 초과가 429 로 변환된다 | `Bucket4jRateLimiterTest` |
| AC-5 | LLM 실패 시 폴백 응답이 만들어지지 않고 저장도 되지 않는다 | `ChatServiceTest` · `ChatFlowIntegrationTest` |
| AC-6 | 저장 내용이 평문으로 남지 않는다 | `ChatContentEncryptionConverterTest` |
| AC-7 | 헥사고날·LLM 격리 위반 0 | `AiArchitectureTest` |
| AC-8 | 커버리지 LINE >= 90% | `./gradlew :ai-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** 레이트리밋이 **인메모리 단일 인스턴스 전제**다(`Bucket4jRateLimiter.java:19-20` 주석이 명시).
  스케일아웃하면 인스턴스 수만큼 한도가 곱해져 비용 가드가 사실상 무력해진다. 교체 지점(Redis ProxyManager)은
  포트 뒤라 계약은 불변. → `disposition: by-design-documented` (스케일아웃 시 필수 교체)
- **KI-2** 마스킹 대상이 **주민번호·카드번호 2종뿐**이다(`PiiMasker.java:23,26`). 계좌번호·전화번호·이메일은
  그대로 LLM 에 전달된다. 초크포인트 구조는 있으니 규칙 추가는 한 곳에서 끝나지만, 현재 커버리지는 2종이다.
  → `disposition: recorded-not-fixed` (범위 한계)
- **KI-3** 카드 마스킹이 Luhn 을 통과한 숫자열만 치환하므로, **Luhn 을 통과하는 비카드 숫자열**(일부 주문번호 등)은
  오탐으로 마스킹되고 **Luhn 을 통과하지 못하는 실제 카드 입력 오타**는 통과한다. 정밀도/재현율 트레이드오프가
  명시적 선택인지 문서에 남아 있지 않다. → `disposition: recorded-not-verified`
- **KI-4** 대화 저장은 암호화되지만 **마스킹된 사용자 입력**이 저장된다 — 즉 마스킹이 저장 이전에 일어나므로
  원문 복구가 불가능하다. 감사·분쟁 대응에서 "무엇이 입력됐는가"를 되짚을 수 없다는 뜻이며, 이 트레이드오프가
  의도된 것인지 확인하지 않았다. → `disposition: recorded-not-verified`
