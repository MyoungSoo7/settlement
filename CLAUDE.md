# Lemuel — 이커머스 + 정산 MSA 플랫폼

> 이 문서는 **에이전트용 작업 지침** — 아키텍처 경계, 코딩 컨벤션, 빌드·보안에 집중한다.
> - **기능·API·유스케이스 상세** → [`SPEC.md`](./SPEC.md) (사람용 기능명세)
> - **서비스별 강제 도메인 규칙**(상태머신·수수료·정책 등) → `*-domain-rules` / `*-rules` 스킬(온디맨드 로드:
>   order-commerce·settlement-domain·loan-domain·investment-domain·account-domain·financial-data·economics-data·
>   market-quotes·company-news·commondata-connector·operation-signal·ai-chat)
> - **사용자 문서** → [`README.md`](./README.md) · **아키텍처 결정** → [`docs/adr/`](./docs/adr/)
> - **기술 스택·빌드 커맨드·인프라·작업 이력** → [`docs/DEVELOPMENT.md`](./docs/DEVELOPMENT.md) (참조성 — 필요 시 조회)

## 🚫 핵심 가드레일 (위반 시 아키텍처·회계 손상 — 절대 금지)

- **MSA 경계**: `settlement` 에서 `order` 를 import·DB 조인 **금지**. Order/Payment/User/Product 는 Kafka 프로젝션
  (`settlement_*_view`) + 내부 대사 API(`/internal/recon`)로만. `settlement-service/build.gradle.kts` 에
  `implementation(project(":order-service"))` 넣지 말 것.
- **헥사고날**: 도메인(`domain/`)이 어댑터(`adapter/`)를 import **금지**. 포트 우회 **금지**(ArchUnit 강제).
- **금액**: 금액에 `double`/`float` **금지** — `BigDecimal` 강제, 라운딩 정책 보존.
- **원장(GL)**: 한쪽 계정만 있는 반쪽 전표 삽입 **금지** — 반드시 차1·대1 균형 팩토리로. `POSTED` 전표 수정 금지(역분개만).
- **account**: 이벤트 **발행** 코드 추가 **금지**(소비 전용 — Outbox 발행 머시너리 배제됨).
- **market**: PER/PBR 계산 **금지**(시세·시총만 서빙, 밸류에이션 조인은 소비측 몫).
- **인가(IDOR)**: 셀러 리소스 식별자를 요청 파라미터로 신뢰 **금지** — JWT 주체(userId)에서 파생 + 소유권 대조(403).
- **커밋**: `hackathon/`·`pwc/` 커밋 **금지**(.gitignore 의도됨, `add -f` 우회 금지). `main` 직접 push **금지**(보호 브랜치).

> 위 가드레일은 **기계로 강제된다**(문서 규율 아님): 저장소 추적 가드 `scripts/harness/guard.mjs` 가 실시간
> PreToolUse(exit 2 차단)·git pre-commit(`node scripts/harness/install-hooks.mjs`)·CI(`.github/workflows/harness-guard.yml`) 3중으로
> 위반을 차단한다(플러그인 독립). 하네스 자기 진단은 `node scripts/harness/harness-audit.mjs`(또는 `/harness-check`) —
> 문서 드리프트·라우팅·가드 무결성까지 검증. 하네스 구성 정본은 [`HARNESS.md`](./HARNESS.md).

## 프로젝트 개요

주문·결제·정산·선정산/기업대출·투자·계정계·재무제표·경제지표·기업뉴스평판·운영관제·주식시세·AI챗봇·공공데이터를
**12개 마이크로서비스 + API Gateway** 로 분리한 헥사고날 백엔드. 원래 모놀리스였으나 Bounded Context 로 분리.

- **12개 서비스 모두 DB-per-service** — order=opslab, settlement=settlement_db, loan=lemuel_loan,
  financial=lemuel_financial, economics=lemuel_economics, company=lemuel_company, operation=lemuel_operation,
  market=lemuel_market, ai=lemuel_ai, commondata=lemuel_commondata, investment=lemuel_investment, account=lemuel_account.
- 서비스 간 연계는 **Kafka 이벤트로만** (코드·DB 직접 의존 0).
- order↔settlement 는 settlement 가 자체 DB 에 **이벤트 드리븐 프로젝션**(`settlement_*_view`)을 적재하는 CQRS 로 분리
  (ADR 0020), 대사는 order 내부 API(`/internal/recon`) 호출로 cross-DB 연결 0 유지.

## 기술 스택 (요지)

**Java 25 · Spring Boot 4.0.4 · Gradle(Kotlin DSL) 멀티모듈 · PostgreSQL 17 · Kafka(Redpanda) · Flyway.**
전체 표(검색·PG·배치·캐시·PDF·관측·RateLimit 등) → [`docs/DEVELOPMENT.md`](./docs/DEVELOPMENT.md).

## 모듈 구조

```
settlement/                       # Gradle 멀티 모듈 루트
├── settings.gradle.kts           # 12 서비스 모듈 선언 (shared-common 은 composite build)
├── build.gradle.kts              # 부모 빌드 (subprojects 공통 설정)
├── shared-common/                # 📦 java-library: common.{audit, config, exception, outbox, ratelimit, pdf}
├── order-service/                # 🛒 Commerce (8088, opslab) — user·order·payment·cart·shipping·product·category·coupon·review·game·(menu·rbac·commoncode·recon·projectionbackfill)
├── settlement-service/           # 💰 Settlement (8082, settlement_db, standalone) — settlement·payout·ledger·chargeback·pgreconciliation·report·recon
├── loan-service/                 # 💸 Loan (8084, lemuel_loan) — 선정산 + 기업대출(CEO). shared-common 의존
├── financial-statements-service/ # 📊 Financial (8086, lemuel_financial) — 재무제표 공개조회. ★shared-common 미의존
├── economics-service/            # 📈 Economics (8087, lemuel_economics) — ECOS 지표 공개조회. ★shared-common 미의존
├── company-service/              # 📰 Company (8090, lemuel_company, ADR 0023) — 뉴스·평판·문서함. shared-common 의존(Outbox + 문서 JWT 게이트)
├── operation-service/            # 🖥️ Operation (8092, lemuel_operation) — 운영 관제(인시던트·신호·이상탐지). shared-common 제한 스캔
├── market-service/               # 📉 Market (8094, lemuel_market) — KRX 시세·시총 공개조회. ★shared-common 미의존
├── ai-service/                   # 🤖 AI (8096, lemuel_ai) — 챗봇. shared-common JWT 만 제한 스캔
├── common-data-service/          # 🗂️ Common-Data (8098, lemuel_commondata) — data.go.kr 범용 커넥터. ★shared-common 미의존
├── investment-service/           # 📈 Investment (8100, lemuel_investment) — CEO 투자하기. shared-common 의존
├── account-service/              # 🏦 Account (8102, lemuel_account) — 계정계 GL 집계. shared-common 제한 스캔(소비 전용)
└── gateway-service/              # 🚪 API Gateway (8080) — 라우팅만(자체 인증 필터 없음)
```

- **공개 read-only 위성 서비스**(위 트리 `★` = shared-common 미의존): GET 공개, 수집 트리거 `/admin/**` 는
  `AdminApiKeyFilter`(X-Internal-Api-Key) 게이트. company·operation·ai·account 는 의존이되 제한 스캔(트리 주석 참조).
- 각 서비스의 **책임·API·유스케이스는 [`SPEC.md`](./SPEC.md), 강제 규칙은 `*-rules` 스킬** 참조.

## 헥사고날 아키텍처 (각 서비스 내부)

```
{service}/src/main/java/github/lms/lemuel/{domain}/
├── domain/                 # 도메인 모델 (순수 POJO, 프레임워크 의존 0)
├── application/port/{in,out}/ · application/service/   # UseCase 인터페이스·포트·구현
└── adapter/
    ├── in/{web,kafka,batch}/       # REST · Kafka 컨슈머 · Spring Batch
    └── out/{persistence,external,event,readmodel,search,pdf}/  # JPA · PG · Outbox 발행 · 프로젝션 뷰 · ES · PDF
```

- **헥사고날 강제**: ArchUnit 으로 패키지 의존 방향 검증. 도메인은 어댑터를 import 하지 않는다.

## ★ 이벤트 드리븐 프로젝션 패턴 (핵심) — ADR 0020

`settlement-service` 가 `order-service` 코드를 **import 하지 않고 DB 도 공유하지 않으면서** Order/Payment/User/Product
를 조회하는 기법. settlement 가 **자체 DB(settlement_db)에 소유하는 프로젝션 테이블**(`adapter/out/readmodel/`)을 두고,
order Kafka 이벤트를 컨슈머(`adapter/in/kafka/`)가 받아 로컬 적재한다.

- 뷰: `settlement_{order,payment,user,product}_view` ← `lemuel.{order.created,payment.captured/refunded,user.registered,product.changed}`
- **대사**: `recon.OrderReconClient` 가 order `/internal/recon`(공유 시크릿 `X-Internal-Api-Key`) 호출 — 양측 자기 DB 만 읽음.
- **백필**: order 의 `projectionbackfill` 모듈.
- → `settlement-service/build.gradle.kts` 에 `implementation(project(":order-service"))` **없음**. MSA 코드·데이터 경계 100%.
- 뷰 신규 추가·드리프트 조사·백필 절차 → `projection-view-ops` 스킬.

## 이벤트·멱등 (Outbox + Kafka)

- **Outbox**: DB tx 안에서 `outbox_events` INSERT → 멀티워커 폴러(FOR UPDATE SKIP LOCKED, 기본 2s) 가 Kafka 발행.
- **3단 멱등 방어**: ① `outbox_events.event_id UUID UNIQUE` → ② 컨슈머 `processed_events (consumer_group, event_id)` PK →
  ③ 도메인 UNIQUE(예: `settlements.payment_id`, account `(source_topic, ref_type, ref_id)`).
- **이벤트 계약-as-code (ADR 0024)**: cross-service 10개 토픽의 JSON Schema + 정본 샘플이
  `shared-common/src/testFixtures/resources/contracts/events/` 에 단일 출처. 프로듀서·컨슈머 **양방향 계약 테스트**로
  드리프트를 빌드 시점 차단. 소비: `testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))`.
- 토픽 목록·프로듀서/컨슈머 매핑 → [`SPEC.md`](./SPEC.md) §5. 이벤트/멱등 코드 작성 규칙 → `idempotency-and-events` 스킬.
  토픽 추가·페이로드 변경 절차(스키마·샘플·양방향 테스트 배선) → `event-contract-change` 스킬.

## 도메인 규칙 (요지 — 상세는 SPEC.md §4 + `*-rules` 스킬)

- **상태머신은 도메인이 강제한다** — 비정상 전이 차단(예: `OrderStatus.canTransitionTo()` → `Order.transitionTo()`).
  전이도 표: SPEC.md §4 (Payment/Order/Settlement/Payout/Chargeback/Ledger/PgRecon/CorporateLoan/Investment).
- **금액은 BigDecimal 강제**, 라운딩 정책 보존. 원장 전표는 차변1·대변1·금액1 **구성적 균형**, `PENDING→POSTED→REVERSED`(역분개).
- **정산 정책**(등급별): 수수료 NORMAL 3.5%/VIP 2.5%/STRATEGIC 2.0%(정산시점 `commission_rate` 영구보존),
  주기 T+7/T+3/T+1, 홀드백 30%/30일·10%/14일·0%. 상세·정본은 `settlement-domain-rules` 스킬.
- **소유권(IDOR 방지)**: 셀러 리소스 식별자는 요청이 아니라 JWT 주체(userId)에서 파생, 집행/취소는 소유권 대조(403).

## 코딩 컨벤션

- 헥사고날(Ports & Adapters), 도메인 순수 POJO, in/out 포트 분리.
- **DB 마이그레이션**: Flyway, 신규는 `V{timestamp}__` 명명 권장(예: `V20260611110000__`).
- **테스트**: 도메인 → 서비스 → 컨트롤러 → 통합 순. settlement 통합은 Testcontainers(→ `settlement-integration-test` 스킬).
- **MSA 경계**: settlement ↔ order 코드·DB 의존 0 (Kafka 프로젝션 + `/internal/recon` 만).
- **커버리지 게이트**: JaCoCo CI **LINE 최소 90%**, 핵심 도메인 패키지 INSTRUCTION 80% 강제(`build.gradle.kts`).
  adapter in/out 서브패키지는 게이트 제외(통합 테스트로 별도 검증). 측정은 게이트 태스크가 정답.
- **OO 구조 게이트**: 도메인 public setter·@Setter/@Data 금지, 금융 5서비스 도메인 generic IAE 금지,
  코어 애그리거트는 rehydrate/팩토리 전용 — `guard.mjs` OO-* 규칙(실시간)과 `oo-gate.test.mjs`(CI 전수)가
  기계 강제. 5축 점수 재채점(패널 중앙값 ≥9.5)은 `oo-score` 스킬.

## 작업 프로토콜 / Definition-of-Done

- **완료 판정은 테스트·게이트가 정답**(LLM 판단 아님): `./gradlew :<module>:test` +
  `:<module>:jacocoTestCoverageVerification`(LINE 90%) 통과를 확인한 뒤 완료를 선언한다.
- **커밋**: develop 에 항목별 개별 커밋(리뷰·롤백 용이). `main` 은 보호 브랜치 — PR 필수, **squash 만**, 필수 CI 2종.
  `hackathon/`·`pwc/`·세션 로그는 커밋 대상 아님(가드레일 참조). PowerShell 에서 커밋 메시지는 `git commit -F <file>`(here-string `@` 누수 회피).
- **흔한 함정**:
  - `JWT_SECRET` 은 운영 필수(기본값 없음, ≥32바이트). 테스트는 부모 `build.gradle.kts` 의 test env 로 주입됨.
  - settlement 통합테스트는 Testcontainers(Docker 필요 — 없으면 skip). settlement 는 자체 DB + 자체 Flyway.
  - shared-common 은 composite build 로 로컬 치환 — 변경이 의존 서비스에 즉시 반영(별도 publish 불필요).
  - 제한 스캔 서비스(company/ai/account 등)에 shared-common 빈(JwtUtil·필터 등) 추가 시 `@Import` 필요(전역 스캔 안 됨).
  - 새 도메인/서비스는 코드만으론 안 붙는다 — 스캔·JPA·gateway·nginx·Dockerfile 5곳 배선(→ `msa-service-wiring` 스킬).
  - CRLF 파일을 `sed -i` 로 편집하면 전체 라인엔딩이 churn — Edit 도구로 해당 줄만 수정.

## 보안

| 항목 | 설정 |
|------|------|
| 인증 | JWT (HS256) — `shared-common.common.config.jwt`. `JWT_SECRET` **운영 필수**(기본값 없음 → 미설정 시 기동 실패, ≥32바이트) |
| 인가 | 역할 ADMIN/MANAGER/USER + 경로별 `hasRole`. 셀러 리소스는 소유권 대조(IDOR 방지) |
| 내부/관리 API 키 필터 | `AdminApiKeyFilter`/`InternalApiKeyFilter` — 키 미설정 시 개발 통과, `app.security.internal-key-required=true`(운영) 면 **fail-closed** |
| 비밀번호 / CORS / RateLimit | BCrypt(cost=12) / 환경변수 화이트리스트 / Bucket4j |
| Actuator | 인증 필수, `health.show-details=when-authorized`(미인증엔 상태만) |
| Audit / 환불 동시성 | PII 마스킹 + 감사로그 / Pessimistic Lock + Idempotency-Key |
| SSRF | commondata 데이터소스 등록 시 내부/사설/메타데이터 IP 차단 |

> 운영 배포 필수 주입: 강한 `JWT_SECRET`, `app.security.internal-key-required=true`, 각 서비스 외부 API 키.

> **인프라·빌드/실행 커맨드·작업 이력(브랜치·MSA 분리·TPS 개선)** → [`docs/DEVELOPMENT.md`](./docs/DEVELOPMENT.md).
