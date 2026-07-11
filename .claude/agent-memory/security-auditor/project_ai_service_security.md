---
name: project_ai_service_security
description: ai-service(AI 챗봇 MSA, port 8096) 보안 감사에서 발견한 아키텍처 수준 이슈 — 재검토 시 참고
metadata:
  type: project
---

2026-07-08 커밋 27ef5fa1 기준 ai-service 최초 보안 감사에서 확인한 사실들.

## SSE 스트리밍 에러 핸들러가 원본 예외 메시지를 그대로 클라이언트에 노출
`ChatController.chatStream()` (ai-service/src/main/java/github/lms/lemuel/ai/chat/adapter/in/web/ChatController.java:74-82) 의
`catch (Exception e)` 블록이 `e.getMessage()` 를 SSE "error" 이벤트로 그대로 전송한다.
동기 `/api/ai/chat` 엔드포인트는 `GlobalExceptionHandler` 가 안전한 고정 메시지로 매핑하지만,
SSE 스트림은 별도 가상 스레드에서 실행되어 `GlobalExceptionHandler` 를 거치지 않는다.
`ChatService.chat()` 내부에서 DB/영속성 계층(JPA, DataAccessException 등)이나 예기치 못한
RuntimeException 이 터지면 원본 메시지(내부 스키마/컬럼명 등 포함 가능)가 클라이언트로 직행한다.
→ **재발 패턴**: 이 프로젝트에서 SSE/스트리밍 엔드포인트를 새로 만들 때는 항상 catch 블록에서
사용자 대상 고정 메시지로 치환하는 로직이 있는지 확인할 것 (동기 엔드포인트의 안전성만 보고
스트리밍 엔드포인트를 놓치기 쉬움).

## PII/결제정보 마스킹은 프롬프트 안내뿐, 기술적 통제 없음
시스템 프롬프트(`application.yml` `app.ai.chat.system-prompt`)에 "카드번호·주민번호 입력하지
말라"는 안내만 있고, 서버 측 정규식 스캔/마스킹/차단 로직은 없다. 사용자가 입력하면:
- `chat_messages.content` (TEXT, 평문) 에 그대로 저장
- `chat_conversations.title` (첫 메시지 앞 120자, 평문) 에도 그대로 저장되어 대화 목록 API 응답에 노출
- Anthropic API(외부 서드파티)로 그대로 전송
이 패턴은 이 서비스만의 문제가 아니라 LLM 통합 서비스 전반에 해당하는 구조적 리스크 —
Phase 2/3 확장(Function Calling, RAG) 검토 시 재확인 필요.

## Rate limit 은 인스턴스 로컬 메모리 (Caffeine) — 다중 파드에서 사용자별 한도 우회
`Bucket4jRateLimiter` 는 코드 주석에서 이미 "단일 인스턴스 전제, 스케일아웃 시 Redis
ProxyManager 로 교체" 라고 명시한 알려진 기술부채. k8s 로 여러 replica 배포 시 사용자별
분당/일일 한도가 파드 수만큼 곱연산으로 우회된다 (LLM 비용 가드가 목적이라 실비용 영향 있음).

## shared-common 전역 SecurityConfig 를 제한 스캔으로 상속받는 서비스는 actuator/swagger 노출 확인 필수
ai-service는 `@SpringBootApplication(scanBasePackages = {"github.lms.lemuel.ai",
"github.lms.lemuel.common.config.jwt"})` 로 shared-common의 JWT 스택만 물고 있는데,
이 패키지에 shared-common의 `SecurityConfig`(정산/주문 서비스용 라우팅 규칙이 잔뜩 들어있는
전역 체인)가 함께 딸려온다. `AiSecurityConfig` 는 `@Order(1)` + `securityMatcher("/api/ai/**")`
로 자기 경로만 가로채고, 나머지(actuator, swagger-ui, v3/api-docs 등)는 shared-common
SecurityConfig 가 처리 — 거기서 `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/prometheus`,
`/actuator/health`, `/actuator/info` 가 permitAll 로 열려 있다. 이건 플랫폼 전체의 의도된
패턴(다른 서비스도 동일)이라 CRITICAL은 아니지만, 새 서비스 감사할 때마다 "이 서비스는 어떤
scanBasePackages를 쓰고, 그로 인해 어떤 전역 시큐리티 규칙을 상속하는지" 반드시 추적해야
놓치지 않는다. [[pattern_satellite_scanbasepackages]]
