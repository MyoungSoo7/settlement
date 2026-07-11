---
name: project-ai-service-structure
description: ai-service(port 8096, Claude 챗봇 MSA) chat 바운디드 컨텍스트 구조와 2026-07-08 최초 헥사고날 검수 결과
metadata:
  type: project
---

ai-service(신설, 커밋 27ef5fa1 기준)는 `chat` 바운디드 컨텍스트 하나로 구성:
- `domain`: ChatCompletion/ChatMessage/Conversation/MessageRole — 전부 순수 record/POJO, 프레임워크 의존 0.
- `application/port/{in,out}` + `application/service` + `application/exception`
- `adapter/{in/web, out/llm, out/persistence, out/ratelimit}`

**2026-07-08 검수 결과 요약 (Critical 0건)**:
- 의존 방향·LLM(Spring AI) 격리·MSA 코드 경계 0 모두 ArchUnit(`AiArchitectureTest`)로 강제되고 실제 코드도 위반 없음. `AnthropicChatAdapter`(adapter.out.llm)에만 `org.springframework.ai.*` import 존재.
- `ChatController`/`ChatService` 가 `AiChatProperties`(config)를 직접 주입받는 것은 [[pattern_archunit_convention]] 에 정리한 프로젝트 선례라 위반 아님.
- Minor: `Bucket4jRateLimiter` 는 `app.ai.rate-limit.per-minute/per-day` 를 `@Value` 로 개별 주입받는 반면, 나머지 설정은 `AiChatProperties`(@ConfigurationProperties record, prefix=app.ai.chat)로 통합돼 있어 설정 방식이 서비스 내에서 일관되지 않음(둘 다 config 계층이라 헥사고날 위반은 아님, 컨벤션 일관성 이슈).
- Gap: `AiArchitectureTest` 에 도메인 순수성(프레임워크 애노테이션 부재) 을 직접 검증하는 규칙이 없음 — 방향성 규칙 4개뿐. 회귀 방지용으로 추가 권고했으나 미채택 상태(다른 세션이 결정할 사안).
- 포트/어댑터 대응 확인: `ChatController`→`ChatUseCase`(interface), `ConversationController`→`ConversationQuery`(interface) — 구현체 직접 의존 없음. 정상.

이 서비스는 order/settlement/loan/financial/economics/company/operation/market 어느 것도 import 하지 않음(ArchUnit 규칙 4로 강제 + 코드 확인 일치).
