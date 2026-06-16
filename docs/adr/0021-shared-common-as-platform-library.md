# ADR 0021 — shared-common 을 버전드 플랫폼 라이브러리로 (서비스별 번들 + 인프라 테이블 소유)

- 상태: Proposed (DB-per-service 전환(ADR 0020)과 함께 단계 적용)
- 일자: 2026-06-16

## 컨텍스트

`shared-common` 은 order/settlement/reservation 이 공통으로 빌드 의존하는 monorepo `java-library`
모듈이다. 내용은 **전부 기술 cross-cutting** 이며 **비즈니스 도메인 코드는 0** 이다(분산 모놀리스 아님):

| 분류 | 포함 |
|---|---|
| 순수 기술 | observability(MDC·traceId·PII·AOP), jwt(인증), ratelimit, pdf, cache(2-tier), kafka config, exception/GlobalExceptionHandler, ApiResponse |
| 공유 JPA 엔티티 + 테이블 ⚠️ | `OutboxEventJpaEntity`(outbox_events), `ProcessedEventJpaEntity`(processed_events), `AuditLogJpaEntity`(audit_logs) |

DB-per-service + 독립 배포(ADR 0020)로 가면 다음 마찰이 **실재화**된다:

1. **배포 락스텝**: monorepo 공동 빌드라 shared-common 한 줄 변경이 전 서비스 재빌드/재배포를 유발 — 독립 배포 정신에 위배.
2. **인프라 테이블 소유권**: outbox/processed_events/audit 마이그레이션이 order-service 에 몰려 있음(V28·V29·V34). DB 를 나누면 각 서비스가 자기 DB 에 이 테이블을 가져야 한다.
3. **뚱뚱한 의존**: kafka·jpa·querydsl·pdf·security 를 한 라이브러리가 끌고 와, PDF/QueryDSL 이 불필요한 서비스(reservation 등)도 전부 끌어옴.

## 결정

### 원칙: "각 서비스에 들어간다 = 바이너리로 번들" (소스 복제 아님)
독립 배포되는 각 서비스는 shared-common 을 **자기 jar 에 번들**해 자족적으로 뜬다. 단 **소스는 단일 출처**로 유지하고 **버전드 아티팩트로 발행**해 각 서비스가 핀된 버전에 의존한다 → 배포 락스텝 해제 + 중복/drift 없음.

### 1. focused 플랫폼 라이브러리로 분할
하나의 뚱뚱한 모듈 → 관심사별 라이브러리. 각 서비스는 **필요한 것만** 의존(Spring Boot starter 스타일).

| 라이브러리 | 내용 | 주 소비자 |
|---|---|---|
| `platform-web` | ApiResponse, GlobalExceptionHandler, OpenApi, WebMvc | 전 서비스 |
| `platform-observability` | MDC·traceId·PII·AOP trace | 전 서비스 |
| `platform-security` | jwt(JwtUtil·필터·SecurityConfig·AuthPrincipal) | 전 서비스 |
| `platform-ratelimit` | Bucket4j | 게이트웨이 경유 서비스 |
| `platform-messaging-outbox` | Outbox 프로듀서/컨슈머 + OutboxEvent·ProcessedEvent 엔티티 | 이벤트 주고받는 서비스 |
| `platform-audit` | AuditLog 엔티티 + Aspect | 감사 필요한 서비스 |
| `platform-pdf` | Ghostscript | settlement 등 PDF 서비스만 |
| `platform-cache` | 2-tier 캐시 | 캐시 쓰는 서비스만 |

### 2. 인프라 테이블 소유권을 서비스별로 이관
**엔티티 클래스는 공유 유지**(스키마 모양 동일), 그러나 **테이블 DDL(Flyway)은 각 서비스 DB 가 소유**:
- order_db: `outbox_events`(프로듀서)
- settlement_db: `processed_events`(컨슈머) + 자기 `outbox_events`(자기 발행분) + `audit_logs`
- reservation_db / loan_db: 각자 필요한 outbox/processed_events/audit
→ "엔티티 공유 OK, 테이블·DB 인스턴스 공유 금지".

### 3. 아티팩트 발행 (락스텝 해제)
focused 라이브러리를 내부 Maven 레지스트리(또는 GitHub Packages)에 **semver 로 발행**. 각 서비스가 버전 핀.
(완전 분리 저장소 전까지는 monorepo 안에서 모듈 분할 + composite build 로 시작해도 됨.)

### 레드라인 (불변 규칙)
**어떤 플랫폼 라이브러리에도 비즈니스 도메인 엔티티/로직을 넣지 않는다.** (예: Settlement·Order·Loan 엔티티가 들어오는 순간 진짜 분산 모놀리스가 된다.) 현재 도메인 코드 0 상태를 유지한다.

## 결과

### 좋아지는 점
- 독립 배포(버전 핀) — shared-common 변경이 전 서비스 강제 재배포로 번지지 않음
- 서비스별 의존 표면 최소화(불필요한 kafka/pdf/querydsl 제거)
- 단일 소스 유지 → 버그 1회 수정, drift 없음
- "각 서비스 자족적 번들" 달성하면서 중복 복제 회피

### 트레이드오프
- 아티팩트 발행 인프라 + semver 규율 필요
- 모듈 수 증가(관리 비용)
- 인프라 테이블 DDL 소유권 이관은 실제 마이그레이션 작업

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| monorepo 공동 빌드 모듈 유지(현행) | △ | 지금은 동작. 단 독립 배포 시 락스텝 — 진짜 독립 배포 필요 시점까지만 |
| **소스를 각 서비스에 복사(중복)** | ✗ | drift·N배 버그 수정·단일 출처 상실 — 기술 공통 코드엔 최악 |
| 단일 뚱뚱한 버전드 라이브러리 | △ | 락스텝은 풀리나 모든 서비스가 미사용 의존까지 끌어옴 |
| **focused 버전드 플랫폼 라이브러리 (본 결정)** | ✓ | 독립 배포 + 최소 의존 표면 + 단일 출처 |
| 일부 cross-cutting 을 인프라로 위임(service mesh) | 보류 | tracing/ratelimit 를 사이드카로 뺄 수 있으나 현 규모엔 과함 |

## 마이그레이션 스케치 (Strangler)
1. monorepo 안에서 shared-common 을 focused gradle 모듈로 **내부 분할** (소비자 의존만 좁힘)
2. outbox/processed_events/audit **마이그레이션을 각 서비스 DB 로 이관** (ADR 0020 Phase 4 와 연동)
3. 아티팩트 **발행 파이프라인**(semver) 구성 — 저장소 완전 분리 시 필수
4. 각 서비스가 focused 라이브러리 **버전 핀** 으로 의존 선언

## 참조
- [0001 — Hexagonal Architecture](0001-hexagonal-architecture.md)
- [0009 — Spring Boot 4.0 모듈 분리 대응](0009-boot4-migration-module-split.md)
- [0020 — order ↔ settlement DB 물리 분리](0020-order-settlement-db-split.md)
- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0012 — Outbox 경계 분산 트레이싱](0012-distributed-tracing-across-outbox.md)
