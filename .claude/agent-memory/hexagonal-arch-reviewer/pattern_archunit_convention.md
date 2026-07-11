---
name: pattern-archunit-convention
description: 서비스별 ArchUnit 테스트 위치·규칙 패턴과 "config 계층은 application 의존 허용" 프로젝트 전역 선례
metadata:
  type: project
---

각 서비스는 `{service}/src/test/java/.../{Service}ArchitectureTest.java` 에 ArchUnit(1.4.x, Java25 파싱 지원 — CLAUDE.md 확인됨) 규칙을 둔다. ai-service 의 `AiArchitectureTest.java` 가 확인된 예:

1. `noClasses().that().resideInAPackage("..{svc}..domain..").should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..", "..config..")` — 도메인 순수성(패키지 의존 방향만 체크, 프레임워크 애노테이션 존재 여부는 체크 안 함).
2. `application` → `adapter` 금지.
3. (서비스별 특수) 벤더 SDK 격리 — 예: ai-service 는 `org.springframework.ai..` 를 `adapter.out.llm` 밖에서 금지.
4. `ai 는 타 서비스에 코드의존하지 않는다` — MSA 코드 경계 0 을 패키지명 리스트로 명시 검증(신규 서비스 추가 시 이 리스트에 추가 필요 — 안 하면 새 서비스로의 실수 의존이 뚫림. 검수 시 이 리스트가 최신인지 확인할 것).

**중요 선례 — config 계층은 application 의존 허용**: `AiChatProperties`(@ConfigurationProperties record, `github.lms.lemuel.ai.config`)를 `ChatService`(application.service)와 `ChatController`(adapter.in.web) 양쪽에서 직접 주입받는다. 코드 주석에 "config 는 조립 계층이지 adapter 가 아니다 (operation 의 OpsProperties 선례)"라고 명시돼 있고 ArchUnit 규칙 1도 domain 만 config 의존을 금지하지 application 의 config 의존은 허용한다. **이건 위반이 아니라 이 프로젝트의 의도된 컨벤션** — 헥사고날 원칙주의 관점에서 이견이 있을 수 있으나 (a) 여러 서비스에 반복되는 선례이고 (b) config 레코드에 비즈니스 로직이 없는 순수 값 객체라 이 리포에서는 Critical/Major 로 잡지 말고 참고 노트로만 남길 것.

**발견된 갭 — 도메인 순수성(프레임워크 애노테이션) 미검증**: ArchUnit 규칙은 패키지 의존 "방향"만 검사하고, domain 클래스에 `@Entity`/`@Column`/`@JsonProperty` 등 프레임워크 애노테이션이 실수로 붙는 것은 잡지 않는다(import 없이 애노테이션만 잘못 배치하는 경우는 드물어 실무 리스크는 낮지만, 리뷰 시 육안 확인 필요). ai-service 검수 시점(2026-07-08)엔 domain 클래스 전부 순수 POJO/record 확인됨.
