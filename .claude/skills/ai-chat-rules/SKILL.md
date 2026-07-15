---
name: ai-chat-rules
description: AI 챗봇 규칙 — PII 마스킹 단일 초크포인트, provider 스위치(정확히 하나)·out/llm 격리(ArchUnit), JWT+비용가드(분5/일100), LLM 실패 폴백 없음(503+무저장). ai-service 로직 작성·리뷰 시 로드.
---

# AI 챗봇 규칙 (ai-service)

대화형 AI 챗봇 MSA(port 8096, lemuel_ai). LLM 실비용이라 인증·비용가드·PII 보호가 핵심. shared-common 은 `common.config.jwt` 만 제한 스캔.

## 도메인

- `MessageRole`: `USER`/`ASSISTANT`(2값, LLM role 과 1:1). SYSTEM 은 도메인 enum 없음(시스템 프롬프트는 properties 로만).
- `Conversation`(POJO 애그리거트): id=`UUID.randomUUID()`(**IDOR 방어** — 타인 대화 추측 차단), `isOwnedBy` 소유검증,
  `recordExchange` 는 messageCount **+2**(1왕복), 제목은 첫 메시지 파생 `TITLE_MAX=120`.
- `ChatMessage`(불변 record, append-only): content blank 예외. **model·inputTokens·outputTokens 는 ASSISTANT 에만**(USER 는 null) — 비용 집계 근거.
- `ChatCompletion`(불변 record): text blank 예외, 토큰 usage 는 미제공 시 null 허용.
- SSE(`POST /api/ai/chat/stream`): delta→done/error. 가상스레드 실행, 클라이언트 이탈은 `UncheckedIOException`(미완료 왕복 무저장).
  에러 노출은 알려진 도메인 예외만, 그 외 제네릭 문구로 치환(내부정보 차단).

## PII 마스킹 (`PiiMasker` — 단일 초크포인트, 핵심)

- 순수 도메인(정규식만). `ChatService.chat` 진입부에서 **딱 한 번** `PiiMasker.mask(command.message())` 통과 후,
  이후 **마스킹본만** 사용 → 세 유출경로 동시 차단: ① `chat_messages.content` 저장 ② 대화 제목 ③ 외부 LLM 전송.
- 주민번호 `(?<!\d)\d{6}[- ]?[1-8]\d{6}(?!\d)` → `[주민번호 마스킹됨]`. 카드번호 13~19자리 **Luhn 통과분만** → `[카드번호 마스킹됨]`.
  처리순서: 주민번호 먼저 → 카드. **차단이 아니라 마스킹**(대화는 이어지되 원문 미저장).

## Provider 스위치 + out/llm 격리

- 포트 `ChatCompletionPort`(isConfigured/complete/stream). `app.ai.provider` 로 상호배타:
  - `GeminiChatAdapter` `@ConditionalOnProperty(havingValue="gemini", matchIfMissing=true)` = **기본**(RestClient 직접 호출).
  - `AnthropicChatAdapter` `havingValue="anthropic"`(Spring AI 2.0). → **정확히 하나만 등록**(미설정 시 gemini).
- **★ ArchUnit**: `org.springframework.ai..`(및 벤더 SDK) 는 **`ai.chat.adapter.out.llm` 밖에서 참조 금지**. 상위는 포트·도메인 VO 만 안다.
- 두 어댑터 공통: 빈 응답/파싱실패/5xx/타임아웃 → 전부 `AiUnavailableException`(503). 키 미설정은 **부팅 성공** + isConfigured=false(채팅만 503).

## 인증·비용 가드

- `/api/ai/**` 전용 체인 `@Order(1)`: `hasAnyRole("USER","MANAGER","ADMIN")` — 익명 개방 불가(LLM 실비용). 미인증 401, 권한부족 403.
- bucket4j 사용자별 **분 5 / 일 100**(`per-minute:5`/`per-day:100`), 초과 429+`Retry-After`.
  **refund**: LLM 이 과금 없이 실패하면 소비 토큰 1개 환불(best-effort). **스트리밍 이탈은 이미 과금 → 환불 안 함**.
- **LLM 실패 폴백 없음**: LLM 호출은 저장 tx **밖**에서 먼저 → 실패 시 **아무것도 저장 안 됨**. 자유대화는 룰 폴백 불가라 명시적 503 이 정답.

## 안티패턴 (발견 시 지적)

- **PII 마스킹 우회**: `command.message()` 원문을 저장/제목/LLM 에 직접 사용, 마스킹을 컨트롤러·어댑터로 분산(초크포인트 1곳 유지).
- **LLM/SDK 타입을 out/llm 밖에 참조**(ArchUnit 위반 + 벤더 교체 불가) / provider 둘 다 등록·둘 다 미등록.
- **폴백 응답 생성**: LLM 실패 시 가짜/룰 응답을 200 으로 반환 금지 — 503+무저장이 계약(빈 응답도 AiUnavailable).
- 도메인에 프레임워크 애노테이션(ArchUnit) / application 이 adapter 의존 / 타 서비스 코드 import.
- LLM 호출을 트랜잭션 안에서(커넥션 붙든 채 외부 대기 → 풀 고갈) / 키 미설정을 부팅 실패로 처리.
- 정상 이탈(UncheckedIOException)을 LLM 실패로 오분류·환불 / 에러 원문 클라이언트 노출.
