# Lemuel — 이커머스 + 정산 MSA 플랫폼

## 프로젝트 개요

주문·결제·정산·선정산대출·재무제표조회·경제지표조회·기업뉴스평판·운영관제·주식시세조회·AI챗봇·공공데이터조회를 **10개 마이크로서비스 + API Gateway** 로 분리한 헥사고날 아키텍처 백엔드.
원래 단일 모놀리스였으나 Bounded Context 로 분리. **10개 서비스 모두 DB-per-service**(order=opslab, settlement=settlement_db,
loan=lemuel_loan, financial=lemuel_financial, economics=lemuel_economics, company=lemuel_company, operation=lemuel_operation, market=lemuel_market, ai=lemuel_ai, commondata=lemuel_commondata) 로 물리 분리돼 있고, 서비스 간 연계는 **Kafka 이벤트로만** 한다.
order↔settlement 는 settlement 가 자체 DB 에 **이벤트 드리븐 프로젝션**(`settlement_*_view`)을 적재하는 CQRS 로 분리하고
(ADR 0020 완료), 대사(reconciliation)는 order 의 내부 API(`/internal/recon`)를 호출해 cross-DB 연결 0 을 유지한다.
자세한 사용자용 문서는 [`README.md`](./README.md) 참조.

## 기술 스택

| 구분 | 기술 |
|------|------|
| 언어 | Java 25 |
| 프레임워크 | Spring Boot 4.0.4 |
| 빌드 | Gradle Multi-module (Kotlin DSL) |
| API Gateway | Spring Cloud Gateway 2025 |
| 데이터베이스 | PostgreSQL 17 |
| 검색 엔진 | Elasticsearch 8.17 |
| 메시지 브로커 | Kafka (Redpanda 호환) |
| PG 연동 | Toss Payments |
| 배치 처리 | Spring Batch |
| 캐시 | Caffeine (L1) + 선택적 Redis L2 (2-tier, opt-in) |
| PDF 생성 | iText 8 |
| 모니터링 | Micrometer + Prometheus |
| 마이그레이션 | Flyway (초기 V1~V50, 이후 `V{timestamp}__` 명명 혼재) |
| 회복탄력성 | Resilience4j |
| Rate Limiting | Bucket4j |

## 모듈 구조

```
settlement/                       # Gradle 멀티 모듈 루트
├── settings.gradle.kts           # 10 서비스 모듈 선언 (shared-common 은 composite build)
├── build.gradle.kts              # 부모 빌드 (subprojects 공통 설정)
├── shared-common/                # 📦 java-library: 전 서비스가 의존
│   └── github.lms.lemuel.common.{audit, config, exception, outbox, ratelimit, pdf}
├── order-service/                # 🛒 Commerce 서비스 (port 8088)
│   └── github.lms.lemuel.{user, order, payment, cart, shipping, product, category, coupon, review, game}
├── settlement-service/           # 💰 Settlement 서비스 (port 8082, standalone — 자체 DB settlement_db)
│   └── github.lms.lemuel.{settlement, payout, ledger, chargeback, pgreconciliation, report}
├── loan-service/                 # 💸 Loan 서비스 (port 8084, 자체 DB lemuel_loan) — 선정산 대출
│   └── github.lms.lemuel.loan.*
├── financial-statements-service/ # 📊 Financial 서비스 (port 8086, 자체 DB lemuel_financial) — 코스피 재무제표 조회
├── economics-service/            # 📈 Economics 서비스 (port 8087, 자체 DB lemuel_economics) — ECOS 경제지표 공개 조회
│   └── github.lms.lemuel.economics.*   # ★ shared-common 미의존 (공개 read-only, 자체 SecurityConfig)
│   └── github.lms.lemuel.financial.*   # ★ shared-common 미의존 (공개 read-only, 자체 SecurityConfig)
├── company-service/              # 📰 Company 서비스 (port 8090, 자체 DB lemuel_company) — 기업 뉴스·평판 (ADR 0023)
│   └── github.lms.lemuel.company.*     # ★ shared-common 미의존 (Phase 3 outbox 이벤트 발행 시 추가 예정)
├── operation-service/            # 🖥️ Operation 서비스 (port 8092, 자체 DB lemuel_operation) — 운영 관제 (인시던트)
│   └── github.lms.lemuel.operation.*   # Phase 1: Alertmanager webhook → 인시던트 라이프사이클 (docs/design/operation-service-phase1.md)
├── market-service/               # 📉 Market 서비스 (port 8094, 자체 DB lemuel_market) — KRX 상장사 시세·시가총액 조회
│   └── github.lms.lemuel.market.*      # ★ shared-common 미의존 (공개 read-only, 자체 SecurityConfig)
├── ai-service/                   # 🤖 AI 서비스 (port 8096, 자체 DB lemuel_ai) — 대화형 AI 챗봇 (Spring AI 2.0 + Claude)
│   └── github.lms.lemuel.ai.*          # shared-common JWT 만 제한 스캔 (LLM 실비용 → 인증 필수, docs/design/ai-service-phase1.md)
├── common-data-service/          # 🗂️ Common-Data 서비스 (port 8098, 자체 DB lemuel_commondata) — 공공데이터포털 범용 커넥터
│   └── github.lms.lemuel.commondata.*  # ★ shared-common 미의존 (공개 read-only, 자체 SecurityConfig)
└── gateway-service/              # 🚪 API Gateway (port 8080)
```

### 서비스별 책임

| 서비스 | 패키지 | 책임 |
|--------|--------|------|
| **order-service** | `user, order, payment, cart, shipping, product, category, coupon, review, game` (+ `recon`, `projectionbackfill` — ADR 0020 내부 대사 API/프로젝션 백필) | 회원·상품·장바구니·주문·결제·배송 — 거래 컨텍스트. opslab DB 소유, 자기 합계를 `/internal/recon` 으로 노출 |
| **settlement-service** | `settlement, payout, ledger, chargeback, pgreconciliation, report` (+ `recon` — `OrderReconClient`) | 정산 생성/확정, 지급(payout), 복식부기 원장(ledger), 차지백, PG 대사, ES 색인, PDF, 캐시플로우 리포트. **자체 DB settlement_db** — order/payment/user/product 는 Kafka 이벤트로 적재하는 자체 프로젝션(`settlement_*_view`)으로 조회(코드·DB 의존 0) |
| **loan-service** | `loan` | 선정산 대출 — 셀러의 미확정 정산금을 담보로 선지급. 독립 배포 + 자체 DB(lemuel_loan) + 자체 복식부기 원장. settlement 정산 데이터는 Kafka 이벤트(`settlement.created/confirmed`)로만 수신, 상환은 이벤트 saga 로 연계(코드·DB 의존 0) |
| **financial-statements-service** | `financial` | 코스피 상장사(~800) 요약 재무제표 공개 조회 — DART OpenAPI 수집(기업/재무제표 배치, `DART_API_KEY`) + Flyway 시드 폴백. 자체 DB(lemuel_financial), 타 서비스와 코드·DB·이벤트 의존 0. shared-common 미의존(자체 최소 SecurityConfig — GET 공개, `/admin/financial/**` 는 X-Internal-Api-Key 게이트) |
| **economics-service** | `economics` | ECOS 경제지표 공개 조회 — 한국은행 ECOS OpenAPI 수집(기준금리·국고채3년·USD/KRW·CPI, `ECOS_API_KEY`) + Flyway 시드 폴백. 자체 DB(lemuel_economics), 타 서비스와 코드·DB·이벤트 의존 0. shared-common 미의존(자체 최소 SecurityConfig — GET 공개, `/admin/economics/**` 는 X-Internal-Api-Key 게이트) |
| **company-service** | `company` | 기업 뉴스 기사 수집(네이버 뉴스 API, `NAVER_CLIENT_ID/SECRET`)·조회 + Phase 2 평판 스코어 — ADR 0023. 자체 DB(lemuel_company), 기업 식별자(stockCode/corpCode)는 financial 과 공용 비즈니스 키. 기사 본문 미저장(저작권 — 제목·요약·링크만), `url_hash` UNIQUE 멱등 수집. shared-common 미의존(자체 SecurityConfig — GET 공개, `/admin/company/**` 는 X-Internal-Api-Key 게이트) |
| **operation-service** | `operation` | 운영 관제 — Alertmanager 알람을 webhook(Bearer=INTERNAL_API_KEY)으로 받아 인시던트(OPEN→ACKNOWLEDGED→RESOLVED/FALSE_POSITIVE)로 적재·관리. `(source, correlation_key)` partial unique index 로 활성 중복 0, repeat firing 은 refire 병합(+낙관적 락 재시도). 자체 DB(lemuel_operation, opslab 스키마 재사용 — loan 과 동일 이유), 콘솔 `/api/ops/**` 는 JWT ADMIN 전용. **Phase 2 완료**: `signal` BC — (2a) 도메인 성공 이벤트(order/payment/settlement.created) 구독=분모 + Prometheus 폴링(kafka lag/redis/deadlock/http)=인프라 게이지를 `ops_metric_bucket`(5분, ON CONFLICT UPSERT)에 적재; (2b) 실패 이벤트=분자 — shared-common `common.opssignal`(best-effort Kafka emitter, **절대 throw 안 함·Outbox 미사용**)를 PayoutSingleExecutor(settlement.failed)·RefundLifecycle(payment.failed)·재고 차감 서비스(stock.depleted)·신규 ShippingDelayScanner(shipping.delayed)에 배선, operation 이 `lemuel.ops.*.failed` 구독으로 count_signal 적재 → failure_rate=signal/total 성립. (order.failed 는 단일 실패 지점 부재로 소비자만 준비·미배선). 남은 로드맵: 3 베이스라인 이상탐지 → 4 AI 브리핑 (docs/design/operation-service-phase1.md) |
| **market-service** | `market` | KRX 상장사 일별 시세·시가총액 공개 조회 — 공공데이터포털 금융위 주식시세정보(getStockPriceInfo) 수집(`KRX_API_KEY`, 날짜 기준 배치 — 하루치 전 종목 페이지네이션) + Flyway 시드 폴백. 자체 DB(lemuel_market), 타 서비스와 코드·DB·이벤트 의존 0. stockCode(6자리)는 financial/company 와 공용 비즈니스 키 — **PER/PBR 은 계산하지 않고 시세·시총만 서빙**(financial import/DB조인 금지, 밸류에이션 조인은 소비측 CEO 브리핑/invest-copilot 몫). shared-common 미의존(자체 최소 SecurityConfig — GET 공개, `/admin/market/**` 는 X-Internal-Api-Key 게이트) |
| **ai-service** | `ai.chat` | 대화형 AI 챗봇 — Spring AI 2.0(공식 Anthropic SDK 기반) + Claude, 컨텍스트 유지 채팅(`/api/ai/chat`, SSE 스트리밍)·대화 이력 CRUD. 자체 DB(lemuel_ai, `pgvector/pg17` 이미지 — Phase 3 RAG 선점). LLM 실비용이라 **JWT USER 이상 필수** + bucket4j 비용 가드(분5/일100). Spring AI 는 `adapter/out/llm` 에만 격리(ArchUnit 강제), LLM 실패 시 폴백 없이 503+이력 무저장. shared-common 은 `common.config.jwt` 만 제한 스캔. 로드맵: Phase 2 Function Calling(내부 API 호출) → Phase 3 RAG (docs/design/ai-service-phase1.md) |
| **common-data-service** | `commondata` | 공공데이터포털(data.go.kr) **범용 커넥터** — 임의의 OpenAPI 를 코드 변경 없이 "데이터소스"로 등록(`POST /admin/commondata/sources`: endpoint·defaultParams·keyFields)하면 표준 봉투(`response.header.resultCode`/`body.items.item[]`/totalCount 페이지네이션)를 파싱해 `data_records` 에 `(source, record_key)` UNIQUE upsert 로 수집(재수집 멱등, payload JSON 원문 보존). 인증키는 계정당 1개인 `DATA_GO_KR_API_KEY` 공용 + Flyway 시드 폴백(한국천문연구원 특일정보=공휴일 예시). 자체 DB(lemuel_commondata), 타 서비스와 코드·DB·이벤트 의존 0. shared-common 미의존(자체 최소 SecurityConfig — GET 공개, `/admin/commondata/**` 는 X-Internal-Api-Key 게이트) (docs/design/common-data-service-phase1.md) |
| **gateway-service** | (Spring Cloud Gateway) | 라우팅, 인증 필터 |
| **shared-common** | `common.*` | 전 서비스 공유 — 감사·관측·예외·Outbox·rate limit·JWT·PDF |

## 헥사고날 아키텍처 (각 서비스 내부)

```
{service}/src/main/java/github/lms/lemuel/{domain}/
├── domain/              # 도메인 모델 (POJO)
├── application/
│   ├── port/in/         # 인바운드 포트 (UseCase 인터페이스)
│   ├── port/out/        # 아웃바운드 포트 (영속성/외부 서비스)
│   └── service/         # UseCase 구현
└── adapter/
    ├── in/web/          # REST 컨트롤러
    ├── in/kafka/        # Kafka 컨슈머 (settlement-service)
    ├── in/batch/        # Spring Batch (settlement-service)
    ├── out/persistence/ # JPA 리포지토리, 엔티티
    ├── out/external/    # PG 클라이언트 (Toss)
    ├── out/event/       # Outbox-backed Kafka publisher
    ├── out/readmodel/   # ★ 이벤트 드리븐 프로젝션 뷰 (settlement-service 전용, 자체 DB)
    ├── out/search/      # ES 색인
    └── out/pdf/         # iText PDF
```

## ★ 이벤트 드리븐 프로젝션 패턴 (핵심) — ADR 0020 완료

`settlement-service` 가 `order-service` 코드를 **import 하지 않고 DB 도 공유하지 않으면서** Order/Payment/User/Product
데이터를 조회하기 위한 분리 기법. settlement 가 **자체 DB(settlement_db)에 소유하는 프로젝션 테이블**을 두고,
order 가 발행하는 Kafka 이벤트를 컨슈머가 받아 **로컬에 적재**한다. (과거 opslab 의 같은 테이블을 `@Immutable` 로
read-only 매핑하던 방식에서 진화 — 이제 cross-DB 연결 0.)

```
settlement-service/.../adapter/out/readmodel/   (settlement_db 소유 프로젝션 테이블)
├── SettlementOrderViewJpaEntity      (settlement_order_view   ← lemuel.order.created)
├── SettlementPaymentViewJpaEntity    (settlement_payment_view ← lemuel.payment.captured/refunded)
├── SettlementUserViewJpaEntity       (settlement_user_view    ← lemuel.user.registered)
├── SettlementProductViewJpaEntity    (settlement_product_view ← lemuel.product.changed)
└── SettlementProjectionGauges        (프로젝션 적재 상태 메트릭)

settlement-service/.../adapter/in/kafka/        (프로젝션 적재 컨슈머)
├── OrderEventKafkaConsumer · PaymentEventKafkaConsumer · PaymentRefundedViewConsumer
├── ProductEventKafkaConsumer · UserRegisteredEventConsumer
```

- **대사(reconciliation)**: settlement 의 `recon.OrderReconClient` 가 order 의 내부 API `/internal/recon`
  (공유 시크릿 `X-Internal-Api-Key`)을 호출해 합계를 비교 — 양측 모두 자기 DB 만 읽는다(cross-DB 0).
- **백필**: 초기 적재/복구는 order 의 `projectionbackfill` 모듈이 담당.

→ `settlement-service/build.gradle.kts` 에 `implementation(project(":order-service"))` **없음**.
→ MSA 의 코드 경계 + 데이터 경계 100% 확립.

## 도메인 규칙

### Payment 상태
```
READY → AUTHORIZED → CAPTURED → REFUNDED
              ↘ FAILED        ↘ CANCELED (승인취소)
```

### Settlement 상태
```
REQUESTED → PROCESSING → DONE
                       → FAILED
                       → CANCELED
```

### Order 상태
```
CREATED → PAID → REFUNDED
              → CANCELED
```
실제 enum 은 더 세분화된 라이프사이클 보유:
`SHIPPING_PENDING, IN_TRANSIT, DELIVERED,
CANCELLATION_REQUESTED/APPROVED, REFUND_REQUESTED/COMPLETED` (배송·취소·환불 단계).
전이 규칙은 `OrderStatus.canTransitionTo()` 상태머신에 명시되어 `Order.transitionTo()` 가 강제(비정상 전이 차단).

### Payout 상태 (셀러 지급)
```
REQUESTED → SENDING → COMPLETED
                    → FAILED
                    → CANCELED
```

### Chargeback 상태 (지급 거절/분쟁)
```
OPEN → ACCEPTED
     → REJECTED
```

### Ledger 상태 (복식부기 원장)
```
PENDING → POSTED → REVERSED
```

### PG 대사 실행 상태 (PgReconciliation)
```
RUNNING → COMPLETED
        → FAILED
```

### 수수료
- 셀러 등급별 차등 수수료율 (V32 `commission_rate` 스냅샷, `SellerTier`):
  **NORMAL 3.5% / VIP 2.5% / STRATEGIC 2.0%**
  (레거시 기본 3% 는 `Settlement.COMMISSION_RATE` 상수로만 보존 — 운영 rate 는 등급 기준)
- 정산 시점의 `commission_rate` 영구 보존 (이력 보존 — 추후 요율 변경 영향 없음)
- 정산 주기도 등급별 차등 (`SellerTier.defaultCycle`, `users.settlement_cycle` 우선):
  **NORMAL T+7 / VIP T+3 / STRATEGIC T+1** 영업일
- 홀드백(holdback) 정책: `HoldbackPolicy.forTier` —
  **NORMAL 30%/30일, VIP 10%/14일, STRATEGIC 0%** 보류 후 `holdbackReleaseDate` 에 해제

## 이벤트 흐름 (Outbox + Kafka)

```
[order-service] Payment.capture() (DB tx)
    ├─ payments.status = CAPTURED
    └─ outbox_events INSERT (PaymentCaptured)
                     ↓ (멀티워커 폴러 — FOR UPDATE SKIP LOCKED claim, 기본 2s, 비동기 배치 발행)
                 Kafka topic: lemuel.payment.captured
                     ↓
[settlement-service] PaymentEventKafkaConsumer
    ├─ processed_events (group, event_id) PK 멱등 체크
    └─ Settlement.createFromPayment() (DB tx)
```

**3단 멱등 방어**:
1. `outbox_events.event_id UUID UNIQUE`
2. `processed_events PK (consumer_group, event_id)`
3. `settlements.payment_id UNIQUE`

> 같은 Outbox+Kafka 경로로 `order.created`/`user.registered`/`product.changed`/`payment.refunded` 이벤트도
> 흐르며, settlement 의 프로젝션 컨슈머가 `settlement_*_view` 에 적재한다(위 ★ 프로젝션 패턴).

**이벤트 계약(contract-as-code, ADR 0024)**: cross-service 8개 토픽(`payment.captured/refunded`,
`settlement.created/confirmed`, `loan.repayment_applied`, `order.created`, `user.registered`,
`product.changed`)의 JSON Schema + 정본 샘플이
`shared-common/src/testFixtures/resources/contracts/events/` 에 단일 출처로 존재.
프로듀서(실제 발행 페이로드→스키마 검증)·컨슈머(정본 샘플→실제 파싱 코드) **양방향 계약 테스트**가
계약 드리프트를 런타임(DLT/무성 null)이 아닌 빌드 시점에 차단한다. 소비 좌표:
`testImplementation(testFixtures("github.lms.lemuel:shared-common:1.0.0"))`. Schema Registry(ADR 0022)의 경량 선행 단계.

## 코딩 컨벤션

- **아키텍처**: 헥사고날 (Ports & Adapters)
- **도메인 모델**: 순수 POJO, 프레임워크 의존성 없음
- **포트/어댑터**: in/out 명확히 분리
- **DB 마이그레이션**: Flyway, 초기 V1~V50 + `V{timestamp}__` 명명 혼재 (예: `V20260611110000__`). 신규는 timestamp 명명 권장
- **테스트**: 도메인 단위 → 서비스 → 컨트롤러 → 통합 순
- **헥사고날 강제**: ArchUnit 으로 패키지 의존 방향 검증
- **MSA 경계**: settlement-service ↔ order-service 코드·DB 의존 0 (Kafka 이벤트 프로젝션 + 내부 대사 API `/internal/recon` 으로만)

## 인프라

- **컨테이너**: Docker Compose (로컬), Kubernetes (운영)
- **리버스 프록시**: gateway-service (Spring Cloud Gateway)
- **모니터링**: Prometheus + Micrometer + Grafana
- **메시지 브로커**: Redpanda (Kafka 호환)
- **코드 커버리지**: JaCoCo — CI 게이트 LINE 최소 50%, 핵심 도메인 패키지(payment/order/product/settlement/... domain) INSTRUCTION 80% 강제 (`build.gradle.kts`). adapter in/out 서브패키지 전반은 게이트에서 제외(통합 테스트로 별도 검증)

## 보안

| 항목 | 설정 |
|------|------|
| 인증 | JWT (HS256) — `shared-common.common.config.jwt` |
| 비밀번호 해싱 | BCrypt (cost=12) |
| CORS | 환경변수 화이트리스트 |
| Rate Limiting | Bucket4j (`shared-common.common.ratelimit`) |
| Actuator | 인증 필수, 비인가 접근 차단 |
| Audit | PII 마스킹 + 감사 로그 (`shared-common.common.audit`) |
| 환불 동시성 | Pessimistic Lock + Idempotency-Key |

## 빌드 및 실행 커맨드

```bash
# 전체 빌드
./gradlew build

# 모듈별 컴파일
./gradlew :shared-common:compileJava
./gradlew :order-service:compileJava
./gradlew :settlement-service:compileJava
./gradlew :loan-service:compileJava
./gradlew :financial-statements-service:compileJava
./gradlew :economics-service:compileJava
./gradlew :company-service:compileJava
./gradlew :operation-service:compileJava
./gradlew :market-service:compileJava
./gradlew :common-data-service:compileJava
./gradlew :ai-service:compileJava
./gradlew :gateway-service:compileJava

# 모듈별 테스트
./gradlew :order-service:test
./gradlew :settlement-service:test

# 모듈별 부트런
./gradlew :order-service:bootRun
./gradlew :settlement-service:bootRun
./gradlew :gateway-service:bootRun

# 모듈별 jar
./gradlew :order-service:bootJar

# Docker Compose
docker compose up -d                # DB-per-service PG 10종 · ES · Redpanda · 10 services + gateway
docker compose down

# 컨테이너 이미지 (MODULE 빌드 인자로 어떤 서비스인지 지정)
docker build --build-arg MODULE=order-service       -t lemuel-order .
docker build --build-arg MODULE=settlement-service  -t lemuel-settlement .
docker build --build-arg MODULE=loan-service        -t lemuel-loan .
docker build --build-arg MODULE=financial-statements-service -t lemuel-financial .
docker build --build-arg MODULE=economics-service   -t lemuel-economics .
docker build --build-arg MODULE=company-service     -t lemuel-company .
docker build --build-arg MODULE=operation-service   -t lemuel-operation .
docker build --build-arg MODULE=market-service      -t lemuel-market .
docker build --build-arg MODULE=ai-service          -t lemuel-ai .
docker build --build-arg MODULE=common-data-service -t lemuel-commondata .
docker build --build-arg MODULE=gateway-service     -t lemuel-gateway .
```

## 작업 이력 / 브랜치 정보

- **메인 라인**: `develop` → `main`
- **MSA 분리**: 완료됨 (3 서비스 + DB-per-service, settlement↔order 는 이벤트 드리븐 프로젝션으로 코드·DB 의존 0).
  분리 전 백업은 `backup/pre-msa-split`. order↔settlement DB 물리 분리는 ADR 0020 으로 완료.
- **제거된 도메인**: `reservation`(시공 예약/기사 배정) 서비스는 제거됨 — 모듈·자체 DB·gateway 라우팅·프론트·k8s 매니페스트 모두 정리.
- **이후 추가 도메인**: 멤버십 승인 등.
- **TPS 개선 작업**: PgBouncer, Reaㅋ
