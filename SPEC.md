# Lemuel 기능명세서 (Functional Specification)

이커머스 + 정산(Settlement) MSA 플랫폼의 전체 기능 명세. 코어 JVM 13개 마이크로서비스 + API Gateway 에
폴리글랏 7종(Kotlin 2 · Go 2 · Python 3)을 더한 **총 21개 서비스** 헥사고날 백엔드이며,
원래 단일 모놀리스였으나 Bounded Context 로 분리했다.
아키텍처·컨벤션은 [`CLAUDE.md`](./CLAUDE.md), 아키텍처 결정은 [`docs/adr/`](./docs/adr/) 참조.

- 문서 상태: 현행 코드 기준 요약 명세 (엔드포인트 표면 + 도메인 규칙 + 이벤트 흐름)
- 최종 갱신: 2026-07-16

---

## 1. 개요

| 항목 | 내용 |
|------|------|
| 도메인 | 주문·결제·정산·선정산/기업대출·투자·계정계·재무제표·경제지표·기업뉴스평판·운영관제·주식시세·AI챗봇·공공데이터·조직/멤버십 + 알림·정산대사·실시간시세·결제웹훅·백테스트·이상탐지·시계열예측 |
| 서비스 수 | **21개** — 코어 Java 13 + API Gateway + Kotlin 2(알림·대사) + Go 2(스트리밍·웹훅) + Python 3(백테스트·이상탐지·예측) |
| 아키텍처 | 헥사고날(Ports & Adapters), DB-per-service, 이벤트 드리븐(CQRS 프로젝션) |
| 서비스 간 연계 | **Kafka 이벤트로만** (코드·DB 직접 의존 0) + 내부 대사 API(`/internal/recon`) |

### 기술 스택
| 구분 | 기술 |
|------|------|
| 언어 / 프레임워크 | **Java 25 / Spring Boot 4.0.4**(코어 13+gateway) · Kotlin 2.0 / Boot 3.3·JDK 21(이벤트 2종) · Go 1.22 `net/http`(엣지 2종) · Python 3.11 / FastAPI(ML 3종) |
| 빌드 | Gradle 멀티모듈 (Kotlin DSL), shared-common 은 composite build. **폴리글랏 7종은 standalone 빌드**(settings.gradle 미포함, `polyglot-ci.yml` 별도 CI) |
| Gateway | Spring Cloud Gateway 2025 (WebFlux) |
| DB / 검색 | PostgreSQL 17 (DB-per-service) / Elasticsearch 8.17 |
| 메시지 | Kafka (Redpanda 호환) |
| PG 연동 | Toss Payments |
| 배치 / 캐시 | Spring Batch / Caffeine(L1) + 선택 Redis(L2) |
| PDF / 마이그레이션 | iText 8 / Flyway |
| 관측 / 회복탄력성 / RateLimit | Micrometer+Prometheus+OTLP / Resilience4j / Bucket4j |

---

## 2. 횡단 관심사 (Cross-cutting)

### 2.1 인증·인가
- **인증**: JWT (HS256), `shared-common.common.config.jwt`. 발급은 order-service `AuthController`(`/auth/login`).
  토큰 클레임: subject(email), `role`, `uid`(userId). 서명 시크릿은 `JWT_SECRET`(운영 필수, 미설정 시 기동 실패).
- **역할**: `ADMIN`, `MANAGER`, `USER`. `anyRequest().authenticated()` 기본 + 경로별 `hasRole`/`hasAnyRole`.
- **소유권(IDOR 방지)**: 셀러 리소스(투자 주문·재원 등)는 요청 파라미터가 아니라 **JWT 주체(userId)에서 파생**하고
  집행/취소는 소유권을 대조한다(403).
- **공개 read-only 위성 서비스**(financial·economics·market·commondata): shared-common 미의존 +
  (company 는 Outbox·문서 JWT 로 shared-common 을 제한 의존)
  자체 최소 SecurityConfig — GET 공개, 수집 트리거(`/admin/**`)는 `AdminApiKeyFilter`(X-Internal-Api-Key) 게이트.
  키 미설정 시 기본 통과(개발), `app.security.internal-key-required=true`(운영)면 **fail-closed** 거부.
- **RBAC 관리**(order-service `AdminRbacController`): 역할·권한 매트릭스 CRUD·복제 — 로그인 역할 위의 권한 레이어.

### 2.2 이벤트·멱등 (Outbox + Kafka)
- **Outbox 패턴**: DB 트랜잭션 안에서 `outbox_events` 에 기록 → 멀티워커 폴러(FOR UPDATE SKIP LOCKED)가 Kafka 발행.
- **3단 멱등 방어**: `outbox_events.event_id UNIQUE` → 컨슈머 `processed_events(group,event_id)` PK → 도메인 UNIQUE 제약.
- **이벤트 계약-as-code (ADR 0024)**: cross-service 12개 토픽의 JSON Schema + 정본 샘플이
  `shared-common/src/testFixtures/resources/contracts/events/` 에 단일 출처로 존재. 프로듀서·컨슈머 양방향 계약 테스트로 드리프트 차단.
- **이벤트 드리븐 프로젝션 (ADR 0020)**: settlement 가 order 이벤트를 소비해 자체 DB 에 `settlement_*_view` 적재
  (cross-DB 연결 0). 대사는 order 내부 API `/internal/recon` 호출로 양측이 자기 DB 만 읽는다.

### 2.3 금액·원장 안전
- 금액은 **BigDecimal** 강제, 라운딩 정책 보존. 전표는 차변1·대변1·금액1의 **구성적 균형**.
- 원장 상태: `PENDING → POSTED → REVERSED`(역분개 원칙, POSTED 불변).

### 2.4 관측·회복탄력성
- Actuator: `health,info,metrics,prometheus` 노출. `health.show-details=when-authorized`(미인증엔 상태만).
- Micrometer + Prometheus + OTLP 트레이싱. Resilience4j(회로차단), Bucket4j(rate limit).

---

## 3. 서비스별 기능 명세

### 3.1 order-service — 이커머스 거래 컨텍스트 (port 8088)
DB: opslab. 회원·상품·장바구니·주문·결제·배송 + 정합성 부속(recon, projection backfill).

| 도메인 | API(대표 경로) | 기능 |
|--------|------|------|
| 회원/인증 | `/auth`, `/users`, `/memberships` | 회원가입·로그인(JWT 발급)·비밀번호 재설정·멤버십 승인 |
| 상품/카테고리/태그 | `/api/products`, `/products/{id}/variants`, `/api/categories`, `/categories`, `/admin/categories`, `/api/tags`, `/admin/products/{id}/images` | 상품·SKU(variant)·이미지·이커머스 카테고리 트리(계층·정렬·soft delete)·태그 |
| 장바구니/쿠폰 | `/users/{userId}/cart`, `/coupons` | 장바구니, 쿠폰 발급/사용(등급별 권한) |
| 주문 | `/orders`, `/orders/{id}/shipment` | 단건/다건(SKU 자동 재고차감) 주문, Idempotency-Key 중복제출 차단, 배송 라이프사이클 |
| 결제 | `/payments`, `/payments/split`, `/api/payments/*/refunds`, `/admin/refunds`, `/admin/pg` | Toss 결제 생성·인증·캡처·환불(분할결제 포함), PG 라우팅, 환불이력, 관리자 환불승인 |
| 리뷰/게임 | `/reviews`, `/games` | 상품 리뷰, 게임(이벤트성) |
| 시스템 관리 | `/admin/menus`, `/admin/common-codes`, `/admin/rbac` | 메뉴 트리·순환참조 방지·배치정렬, 공통코드 그룹/항목, RBAC 역할·권한 |
| 내부/정합성 | `/internal/recon`, `/admin/settlement-projection` | order 자기 합계 노출(대사, X-Internal-Api-Key), 프로젝션 백필 |

### 3.2 settlement-service — 정산 (port 8082, 자체 DB settlement_db)
정산 생성/확정, 지급(payout), 복식부기 원장, 차지백, PG 대사, ES 색인, PDF 리포트.
order/payment/user/product 는 Kafka 이벤트로 적재하는 자체 프로젝션(`settlement_*_view`)으로만 조회(코드·DB 의존 0).

| 도메인 | API | 기능 |
|--------|------|------|
| 정산 | `/settlements`, `/api/settlements`, `/api/settlements/query` | 정산 조회/검색(ES) — **REST 는 조회 전용**. 생성은 `payment.captured` 컨슈머, 확정은 Spring Batch(`SettlementConfirmJob`)로 비동기 처리 |
| 지급 | `/admin/payouts` (ADMIN) | 셀러 지급 실행·재시도(펌뱅킹 mock) |
| 원장/리포트 | `/api/ledger`, `/api/reports` (ADMIN/MANAGER) | 복식부기 원장 조회, 캐시플로우 리포트(PDF) |
| 차지백 | `/admin/chargebacks` (ADMIN) | 지급 거절/분쟁 처리 |
| PG 대사 | `/admin/pg-reconciliation`, `/admin/reconciliation` (ADMIN/MANAGER) | PG 정산파일 업로드→대사→차이 승인/거절(역정산 트리거) |
| 운영 | `/admin/dlq`, `/admin/integrity` | Kafka DLT/DLQ 관리, 정합성 검증 콘솔 |

**수수료(등급별 스냅샷 보존)**: NORMAL 3.5% / VIP 2.5% / STRATEGIC 2.0%. **정산주기**: T+7 / T+3 / T+1 영업일.
**홀드백**: NORMAL 30%/30일, VIP 10%/14일, STRATEGIC 0%.

### 3.3 loan-service — 선정산 + 기업대출 (port 8084, 자체 DB lemuel_loan)
| 도메인 | API | 기능 |
|--------|------|------|
| 선정산 대출 | `/loans` | 셀러 미확정 정산금 담보 선지급, 상환은 정산 이벤트 saga 연계 |
| 기업 신용대출 | `/loans/corporate` | 상장사(stockCode) CEO 신청 → `CorporateCreditPolicy` 가 재무제표+평판으로 creditScore(0~100)/등급(A~E)/한도 산정. **신청(request) 시점에 E등급·한도초과 422**. 실행(disburse)은 ADMIN 전용 + 비관적 락(이중지급 방지) |
| 평판 | `/loans/company-reputation` | 신용평가용 기업 평판 프로젝션 조회 |
| 상환 시뮬레이션 | `POST /loans/repayment/simulate` | 원금·기간·연이자율·상환방식(만기일시 BULLET / 원리금균등 EQUAL_PAYMENT / 원금균등 EQUAL_PRINCIPAL)으로 회차별 상환표를 미리 계산하는 **순수 미리보기**(부수효과·영속화 없음). 원 단위 반올림·마지막 회차 잔여 흡수로 원금 합 일치 |

자체 복식부기 원장 2전표 + `lemuel.loan.corporate_loan_disbursed` 발행.

### 3.4 financial-statements-service — 재무제표 조회 (port 8086, lemuel_financial)
- `/api/financial/companies` — 코스피·코스닥 상장사 요약 재무제표 공개 조회(부채비율·영업이익률·ROA·자본총계).
- `/admin/financial/sync` — DART OpenAPI 수집 배치(`DART_API_KEY`) + Flyway 시드 폴백(코스피20+코스닥10).
- investment 투자점수·loan 기업대출 신용평가의 회계자료 원천. 타 서비스와 코드·DB·이벤트 의존 0.

### 3.5 economics-service — 경제지표 조회 (port 8087, lemuel_economics)
- `/api/economics/indicators` — 기준금리·국고채3년·USD/KRW·CPI 공개 조회.
- `/admin/economics/sync` — 한국은행 ECOS 수집(`ECOS_API_KEY`, URL 키는 로그 마스킹) + 시드 폴백.

### 3.6 company-service — 기업 뉴스·평판·문서함 (port 8090, lemuel_company, ADR 0023)
| 도메인 | API | 기능 |
|--------|------|------|
| 뉴스·기업 | `/api/company/companies` | 기업 뉴스 기사(제목·요약·링크만, 본문 미저장) + 기업 마스터 공개 조회 |
| 문서함 | `/api/company` (문서 목록·다운로드 — **ADMIN/MANAGER JWT 게이트**) | CEO 브리핑 docx 업로드/다운로드 |
| 수집/관리 | `/admin/company/collect`, `/admin/company/documents`, `/admin/company/reputation`, `/admin/company/sellers`, `/admin/company/companies` | 네이버 뉴스 수집(`NAVER_*`)·감성분석(keyword/Claude/Gemini)·평판 스코어·셀러 링크 |

기업 식별자(stockCode/corpCode)는 financial 과 공용 비즈니스 키. Phase 3 평판 변동 Outbox 발행.

### 3.7 operation-service — 운영 관제 (port 8092, lemuel_operation)
- `/api/ops/webhook` — Alertmanager 알람 수신(Bearer=INTERNAL_API_KEY, 상수시간 비교). key 미설정 시 프로파일 게이트.
- `/api/ops/incidents` (JWT ADMIN) — 인시던트 라이프사이클(OPEN→ACKNOWLEDGED→RESOLVED/FALSE_POSITIVE).
- `(source, correlation_key)` partial unique 로 활성 중복 0, repeat firing refire 병합.
- **신호 BC(Phase 2)**: 도메인 성공 이벤트(분모) + Prometheus 게이지 + 실패 이벤트(`lemuel.ops.*.failed`, 분자)로
  `failure_rate=signal/total` 산출. 로드맵: 베이스라인 이상탐지 → AI 브리핑.

### 3.8 market-service — 주식 시세·시총 (port 8094, lemuel_market)
- `/api/market/stocks` — KRX 상장사 일별 시세·시가총액 공개 조회.
- `/admin/market/sync` — 공공데이터포털 금융위 주식시세정보 수집(`KRX_API_KEY`). **PER/PBR 미계산**(시세·시총만 서빙,
  밸류에이션 조인은 소비측 몫).

### 3.9 ai-service — 대화형 AI 챗봇 (port 8096, lemuel_ai)
- `/api/ai` (ChatController), `/api/ai/conversations` — 컨텍스트 유지 채팅(SSE 스트리밍) + 대화 이력 CRUD.
- **provider 스위치**(`app.ai.provider`, 기본 gemini): Gemini(RestClient) / Anthropic(Spring AI SDK) 중 하나만 등록.
- LLM 실비용 → **JWT USER 이상 필수 + bucket4j 비용가드(분5/일100)**. LLM 어댑터는 `adapter/out/llm` 격리(ArchUnit).
  저장·전송 전 카드/주민번호 PII 마스킹. 로드맵: Function Calling → RAG(pgvector).

### 3.10 common-data-service — 공공데이터 범용 커넥터 (port 8098, lemuel_commondata)
- `/api/common-data/sources` — 등록된 데이터소스·수집 레코드 공개 조회.
- `/admin/commondata` — 임의 OpenAPI 를 코드 변경 없이 "데이터소스"로 등록(endpoint·defaultParams·keyFields) →
  표준 봉투 파싱 → `data_records (source, record_key)` UNIQUE upsert(멱등). `DATA_GO_KR_API_KEY` 공용.
- **SSRF 가드**: 등록 endpoint 가 내부/사설/루프백/링크로컬(메타데이터 169.254.169.254 포함)이면 거절.

### 3.11 investment-service — CEO 투자하기 (port 8100, lemuel_investment)
- `/api/investment` — 투자점수 조회, 초보 투자 체크, 종목 추천(`GET /recommendations`), 투자주문 신청/집행/취소/조회, 재원 조회.
- **투자점수** `InvestmentScorePolicy`: 수익성35 + 안정성35 + 성장성30 = 0~100, AAA~CCC, ≥60 투자적격.
- **투자주문 상태머신**: REQUESTED → APPROVED → EXECUTED / REJECTED·CANCELED.
- 재원 = settlement `confirmed` 이벤트 프로젝션(`seller_funding_view`)의 확정 정산금 − 집행 투자금(부족·부적격 422).
- **소유권 강제**: sellerId 는 JWT 주체에서 파생, 집행/취소는 주문 소유권 대조(403). 집행 시 `lemuel.investment.executed` 발행.

### 3.12 account-service — 계정계 GL (port 8102, lemuel_account)
- `/api/account` (**ADMIN/MANAGER**) — owner 잔액·분개, 대출/투자/정산 집계, 시산표(trial-balance).
- loan·investment·settlement 의 6개 토픽 소비 → 전사 복식부기 GL(`account_entries`, 전표당 차1·대1).
- 계정: CASH·LOAN_RECEIVABLE·CORPORATE_LOAN_RECEIVABLE·INVESTMENT_ASSET·SELLER_PAYABLE·SETTLEMENT_SCHEDULED.
- 멱등 2단(processed_events + `(source_topic,ref_type,ref_id)` UNIQUE). **발행 없음(소비 전용)**.
- 미결(ADR 0026): 셀러 payout 현금 유출 GL 인식 + 시산표 실검증(회계 결정 대기).

### 3.13 organization-service — 조직·멤버십 (port 8104, 자체 DB lemuel_organization)
셀러/기업 단위 조직과 그 구성원(멤버십)을 관리한다. `externalRef` 로 sellerId 또는 stockCode 를 느슨히 참조(비검증).

| 도메인 | API (base `/api/organizations`, **JWT 인증 필수**) | 기능 |
|--------|------|------|
| 조직 | `POST /`, `GET /{orgId}` | 조직 생성(생성자 자동 OWNER 멤버십) / 조회 |
| 멤버십 | `POST /{orgId}/members`, `POST /{orgId}/members/accept`, `PATCH /{orgId}/members/{userId}/role`, `DELETE /{orgId}/members/{userId}` | 초대(OWNER/MANAGER) · 수락 · 역할 변경(OWNER 전용) · 제거(OWNER 전용, **마지막 OWNER 보호**) |

- **타입/역할**: `OrganizationType`=SELLER·CORPORATE, `OrgRole`=OWNER>MANAGER>STAFF. 인가는 요청 파라미터가 아니라
  JWT 주체(userId)의 조직 내 역할로 판정(IDOR 방지, `OrgAuthorizer`).
- **상태머신**: Organization ACTIVE⇄SUSPENDED. Membership INVITED→ACTIVE⇄SUSPENDED, 각 상태→REMOVED(터미널).
- **이벤트 발행**(Outbox, `aggregateType="Organization"`): `lemuel.organization.created`, `lemuel.organization.member_joined`.
  현재 **소비처 미배선**(발행 전용 — 계약 스키마는 존재). shared-common 의존(JWT·Outbox·멱등컨슈머).

### 3.14 gateway-service — API Gateway (port 8080)
- Spring Cloud Gateway(WebFlux). 서비스별 경로 predicate 라우팅. 위성 8종은 공개 조회 API 만 라우팅(수집 트리거
  `/admin/**` 외부 미노출). organization 은 `/api/organizations/**`(JWT 필수)를 라우팅.
- 자체 인증 필터 없음 — 인증·인가는 각 서비스 SecurityConfig 가 강제.
- **폴리글랏 7종(§3.15~3.16)은 gateway 미라우팅** — 독립 포트로 직접 노출(내부/데모 용도).

### 3.15 Kotlin 이벤트 서비스 2종 — notification(8130) · reconciliation(8131)
Boot 3.3 · JDK 21 · 코루틴. **자체 DB 없음**(무영속 MVP) · shared-common 미의존 · gateway 미라우팅.

| 서비스 | API / 트리거 | 기능 |
|--------|------|------|
| **notification-service** (8130) | `POST /notifications/send`, `GET /notifications/demo` + Kafka 리스너 | 도메인 이벤트 5토픽(`settlement.confirmed`·`payment.confirmed/captured/refunded`·`investment.executed`) → 다채널(log/Slack/email) 알림. **코루틴 I/O 팬아웃 + 채널별 타임아웃(3s)/재시도(3회) 격리**, eventId 멱등(TTL 30분). Kafka 리스너는 기본 OFF(`APP_KAFKA_ENABLED=true` 로 활성) — 브로커 없이도 기동·데모 가능 |
| **reconciliation-service** (8131) | `POST /reconciliation/run`, `GET /reconciliation/demo` + `@Scheduled`(매일 19:00 KST) | 정산 대사 — settlement·payment 소스 **코루틴 병렬 fetch** 후 대조, sealed `Discrepancy`(MISSING/EXTRA/AMOUNT/STATUS) 분류, 허용오차 1원(`tolerance-krw`). 소스 base-url 은 env 주입(기본 샘플 시뮬레이션) |

### 3.16 Polyglot 서비스 5종 — Go 2 + Python 3 (정본: [`polyglot-services.md`](./polyglot-services.md))
언어별 강점 배치: **Go**=동시성·저지연 엣지, **Python**=데이터/ML/퀀트. 모두 동작 MVP(핵심 로직+헬스체크+테스트+멀티스테이지 Dockerfile, non-root), Gradle 빌드와 독립.

| 서비스 | 언어 | 포트 | 역할 |
|---|---|---|---|
| `market-stream-service` | Go | 8110 | 실시간 시세 스트리밍 — SSE `/stream/{code}` + WS `/ws/{code}`, goroutine Hub 팬아웃 |
| `payment-webhook-service` | Go | 8111 | Toss 결제 웹훅 수신 — HMAC 서명검증·멱등(TTL) → Kafka `lemuel.payment.confirmed` 발행 |
| `screening-backtest-service` | Python/FastAPI | 8120 | 투자 스크리닝 규칙 백테스트 — 수익률·MDD·Sharpe·승률 (pandas/numpy) |
| `settlement-anomaly-service` | Python/FastAPI | 8121 | 정산/payout 이상탐지 — MAD z-score + IsolationForest 앙상블 (scikit-learn) |
| `forecast-service` | Python/FastAPI | 8122 | 정산액/매출 시계열 예측 — Holt-Winters + seasonal-naive (statsmodels) |

- 공통 규약: `GET /health`(또는 `/healthz`) → `{"status":"UP"}`, env 설정, 구조적 로깅, 기본값은 시뮬레이션/번들 샘플이라 무-외부의존 단독 실행 가능. Python 은 **3.11 필수**(pinned deps).
- CI: `.github/workflows/polyglot-ci.yml` — 변경 경로만 Go(build+test)/Python(pytest) 매트릭스. Java `ci`/harness-guard 와 독립.

---

## 4. 도메인 상태머신·정책

```
Payment      : READY → AUTHORIZED → CAPTURED → REFUNDED  (↘ FAILED / CANCELED)
Order        : CREATED → PAID → REFUNDED/CANCELED (+ SHIPPING_PENDING·IN_TRANSIT·DELIVERED·
               CANCELLATION/REFUND 단계, OrderStatus.canTransitionTo() 강제)
Settlement   : REQUESTED → PROCESSING → DONE / FAILED / CANCELED
Payout       : REQUESTED → SENDING → COMPLETED / FAILED  (CANCELED 는 REQUESTED·FAILED 에서만 — 송금 중(SENDING) 취소 불허)
Chargeback   : OPEN → ACCEPTED / REJECTED
Ledger       : PENDING → POSTED → REVERSED
PgRecon 실행  : RUNNING → COMPLETED / FAILED
CorporateLoan: REQUESTED → APPROVED → DISBURSED → REPAID (↘ REJECTED)
Investment주문: REQUESTED → APPROVED → EXECUTED / REJECTED / CANCELED
Organization : ACTIVE ⇄ SUSPENDED
Membership   : INVITED → ACTIVE ⇄ SUSPENDED, 각 상태 → REMOVED(터미널)  (마지막 OWNER 불변식)
```

정책: 수수료·정산주기·홀드백(등급별, §3.2), 기업대출 신용정책(등급×계수 한도), 투자점수 3축.

---

## 5. 이벤트 카탈로그 (cross-service 12개 계약 토픽)

계약 스키마·정본 샘플: `shared-common/src/testFixtures/resources/contracts/events/` (12개, ADR 0024).

| 토픽 | 프로듀서 | 주요 컨슈머 |
|------|----------|-------------|
| `lemuel.payment.captured` / `.refunded` | order | settlement(프로젝션·정산 생성/역정산) · notification |
| `lemuel.order.created` | order | settlement(프로젝션) |
| `lemuel.user.registered` | order | settlement(프로젝션) · company(셀러 생성) |
| `lemuel.product.changed` | order | settlement(프로젝션) |
| `lemuel.settlement.created` | settlement | loan · account |
| `lemuel.settlement.confirmed` | settlement | loan · investment · account · notification |
| `lemuel.loan.repayment_applied` | loan | settlement · account |
| `lemuel.loan.corporate_loan_disbursed` | loan | account |
| `lemuel.investment.executed` | investment | account · notification |
| `lemuel.organization.created` / `.member_joined` | organization | (소비처 미배선 — 발행 전용) |

부가(계약 스키마 없음): `lemuel.loan.disbursement_requested`, `lemuel.company.reputation_changed`,
`lemuel.ops.*.failed`, `lemuel.pgreconciliation.discrepancy_approved`,
`lemuel.payment.confirmed`(payment-webhook-service(Go) 발행 → notification 소비 — 내부 계약).

---

## 6. 비기능 요구 (Non-functional)

- **보안**: JWT(HS256, BCrypt cost=12), CORS 화이트리스트, Bucket4j rate limit, Actuator 인증, PII 마스킹·감사로그,
  환불 동시성(Pessimistic Lock + Idempotency-Key), 내부/관리 API 키 필터(운영 fail-closed), commondata SSRF 가드.
- **관측**: Prometheus + Micrometer + Grafana + OTLP 트레이싱, 서비스별 헬스/프로브.
- **테스트**: 도메인→서비스→컨트롤러→통합 순. JaCoCo CI 게이트 **LINE 90%**, 핵심 도메인 INSTRUCTION 80%.
  settlement 통합테스트는 Testcontainers PostgreSQL.
- **배포**: Docker Compose(로컬, DB-per-service PG 13종+ES+Redpanda+서비스+gateway), Kubernetes(운영, GitHub Actions→GHCR→ArgoCD+image-updater GitOps), Flyway 마이그레이션. 폴리글랏 7종은 전용 차트로 격리 배포(상세: [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) §5).
- **운영 필수 설정**: `JWT_SECRET`(강함), `app.security.internal-key-required=true`, 각 서비스 외부 API 키.

---

## 7. 관련 문서
- 아키텍처·컨벤션: [`CLAUDE.md`](./CLAUDE.md) · 사용자 문서: [`README.md`](./README.md)
- 아키텍처 개요(21서비스 인벤토리·패턴·스택): [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) · 폴리글랏 정본: [`polyglot-services.md`](./polyglot-services.md)
- 아키텍처 결정: [`docs/adr/`](./docs/adr/) (ADR 0020 DB 분리, 0024 이벤트 계약, 0026 계정계 payout 인식(제안) 등)
- 도메인 규칙 스킬: `settlement-domain-rules`, `loan-domain-rules`, `investment-domain-rules`, `account-domain-rules`
