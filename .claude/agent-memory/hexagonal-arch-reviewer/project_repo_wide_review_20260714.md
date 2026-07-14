---
name: project-repo-wide-review-20260714
description: 2026-07-14 리포 전체 헥사고날 준수도 평가(9.5/10) 결과 — 위반 0건, organization-service 문서 드리프트 발견
metadata:
  type: project
---

2026-07-14 사용자 요청으로 12개 서비스(+gateway+shared-common) 전체 헥사고날 준수도를 평가(10점 만점, 9.5/10 부여). settlement/order/loan/account/investment 5개는 딥다이브, financial/economics/company/operation/market/ai/common-data 7개는 구조 스캔.

**핵심 결론 — Critical/Major 위반 0건**:
- domain 프레임워크 오염(Spring/JPA/Jackson import) 12개 서비스 전부 0건 (grep 전수 확인).
- domain→adapter 역방향 의존 0건.
- 포트 우회(application.service 가 Repository/JPA 직접 주입) 0건 — 전 서비스 port 인터페이스 경유 확인.
- adapter.in.web 컨트롤러는 DTO(`XxxResponse.from(domain)`)로 감싸 리턴, domain 직접 노출 없음.
- settlement↔order MSA 코드 경계 100% — `settlement-service/build.gradle.kts` 에 `project(":order-service")` 없음, 양방향 import 0건. [[pattern_archunit_convention]] 의 ADR 0020 이벤트 프로젝션(`adapter/in/kafka` 컨슈머 9종 + `adapter/out/readmodel` 4개 View + `recon/OrderReconClient.java`→`/internal/recon/*`)이 실제 코드로 확인됨.
- account-service 는 CLAUDE.md 가드레일대로 이벤트 발행 코드(Outbox/KafkaTemplate/adapter.out.event) 0건 — 소비 전용 확인.

**ArchUnit 인벤토리**: 13개 서비스(organization-service 포함) 전부 보유, 커버리지 갭 없음. order-service 의 `HexagonalArchitectureTest`가 가장 강력 — 전체 멀티모듈(`github.lms.lemuel` 루트)을 임포트해 `adaptersShouldNotDirectlyReferenceOtherDomainsPersistence`(타 서비스 adapter.out.persistence 크로스 참조 금지, 리포 전역 검사)와 `applicationServiceShouldNotUseJpaRepositoryDirectly`(포트 우회 직접 검증)를 강제. ai-service 는 도메인 프레임워크 애노테이션 부재까지 검증하는 유일한 서비스([[pattern_archunit_convention]] 참조).

**발견된 갭(감점 요인)**:
1. **CLAUDE.md 문서 드리프트**: `organization-service` 가 실존(자체 ArchUnit 테스트 `OrganizationArchitectureTest.java` 보유)하지만 CLAUDE.md 의 "12개 서비스" 목록·모듈 구조 트리에 없음. SPEC.md 도 미반영 가능성 — 문서 갱신 필요.
2. company/financial/economics/market/commondata 5개 서비스가 문자 그대로 동일한 3규칙 ArchUnit 템플릿을 중복 보유(shared-common 에 공통 베이스 없음) — 위반은 아니나 유지보수성 이슈.
3. shared-common 에 ArchUnit 공통 유틸/베이스 클래스 부재 — 신규 서비스 추가 시 매번 수기 복붙 필요.

**Why**: 다음 세션에서 이 리포의 헥사고날 재검토 요청 시 이 스냅샷을 재검증 없이 그대로 믿지 말 것 — 코드는 계속 변경되므로 grep/파일존재 재확인 필수. 특히 organization-service 는 후속 세션에서 CLAUDE.md 갱신 여부 확인.

**추가 발견(같은 세션, 직접 검증) — 최종 점수는 9/10 으로 하향 조정됨**:
4. `order-service/src/main/java/github/lms/lemuel/config/PersistenceConfig.java:18-37,38-57` — `@EntityScan`/`@EnableJpaRepositories` basePackages 에 `github.lms.lemuel.settlement`, `.payout`, `.chargeback`, `.ledger`, `.pgreconciliation` 등 order-service 안에 **실재하지 않는(Glob 0건 확인) 죽은 패키지명**이 남아있음 — ADR 0020 으로 settlement 를 독립 서비스로 분리하기 전 모놀리스 잔재. 런타임 무해하지만, 향후 실수로 `github.lms.lemuel.settlement` 패키지가 order-service 안에 재생성되면 ArchUnit 은 "import" 만 검사하므로 이 죽은 스캔 설정이 그 클래스를 조용히 JPA 엔티티로 활성화시켜 MSA 경계 위반을 무검출로 통과시킬 잠재 트랩.
5. `order-service/src/test/java/github/lms/lemuel/architecture/HexagonalArchitectureTest.java:65-76` — `adaptersShouldNotDirectlyReferenceOtherDomainsPersistence` 규칙의 이름 기반 예외 목록(`SettlementSearchDocumentMapper`, `SettlementQueryRepositoryImpl`, `CapturedPaymentsAdapter`)이 order-service 안에 존재하지 않는 클래스명(앞 둘은 settlement-service 에만 존재, 셋째는 repo 전체 0건) — 4번과 동일한 분리 이전 잔재, 죽은 예외 목록. 다음 감사 시 이 두 파일이 정리됐는지 재확인 권장.
