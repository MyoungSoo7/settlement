# PLAN — Lemuel 구현 계획 (기능 기반)

> [`SPEC.md`](./SPEC.md) 의 기능을 **구현 단위(Phase)로 분해한 계획서**. 완료 판정 기준은 [`DONE_CRITERIA.md`](./DONE_CRITERIA.md).
> 실시간 진척·미결 현황은 [`STATUS.md`](./STATUS.md) 가 정본 — 이 문서는 "무엇을 어떤 순서·의존으로 짓는가"의 지도.

- 기준 시점: 2026-07-24 (SPEC 2026-07-16, STATUS 2026-07-22 기준)
- **주의**: SPEC 은 *현행 코드 기준 요약 명세*다. 이 계획은 그린필드 신규 착수가 아니라, 이미 구현된 것을
  기능 계보로 재정렬하고 **잔여 작업만 열린 상태로** 표기한다. 상태 범례는 아래.

## 상태 범례
| 표기 | 의미 |
|------|------|
| ✅ DONE | 구현 + 게이트(테스트·ArchUnit·계약·커버리지) 통과 |
| 🟡 PARTIAL | 핵심 경로는 구현, 일부 잔여(아래 "잔여"에 명시) |
| ⬜ TODO | 미착수 — 결정/자원 대기 |

---

## Phase 0 — 공통 기반 (Foundations) ✅ DONE
> 모든 서비스가 딛는 횡단 토대. 여기가 흔들리면 위 계층 전부 재작업이라 최우선.

| 항목 | 산출물 | 상태 |
|------|--------|------|
| 헥사고날 스캐폴드 | `domain/ · application/port · adapter/{in,out}`, ArchUnit 의존 방향 강제 | ✅ |
| shared-common | 버전드 라이브러리(1.0.0, composite build): audit·config·exception·outbox·ratelimit·pdf | ✅ |
| 인증·인가 | JWT(HS256) 발급(order `AuthController`)·검증(shared-common), 역할 ADMIN/MANAGER/USER, IDOR 소유권 대조 | ✅ |
| Outbox + 멱등 | `outbox_events`(event_id UNIQUE) → 멀티워커 폴러 → 3단 멱등(outbox·processed_events·도메인 UNIQUE) | ✅ |
| 이벤트 계약-as-code | cross-service 14토픽 JSON Schema + 정본 샘플(testFixtures), 프로듀서·컨슈머 양방향 계약 테스트 (ADR 0024) | ✅ |
| 금액·원장 안전 | BigDecimal 강제, 전표 차1·대1 구성적 균형, `PENDING→POSTED→REVERSED` | ✅ |
| 하네스 게이트 | guard.mjs(PreToolUse·pre-commit·CI 3중), harness-audit, JaCoCo 90% 게이트 | ✅ |

**검증**: `./gradlew build` + ArchUnit + 계약 테스트 + `node scripts/harness/harness-audit.mjs`.

---

## Phase 1 — 커머스 거래 (order-service, 8088 / opslab) ✅ DONE
> 모든 돈 흐름의 발원지. 결제 이벤트가 정산·대출·투자·계정계의 입력이 되므로 이벤트 계약이 특히 중요.

| 기능 블록 | 대표 경로 | 상태 |
|----------|----------|------|
| 회원/인증/멤버십 | `/auth · /users · /memberships` | ✅ |
| 상품·SKU·카테고리·태그·이미지 | `/api/products · /categories · /api/tags` | ✅ |
| 장바구니·쿠폰(등급별) | `/users/{id}/cart · /coupons` | ✅ |
| 주문(재고 조건부 차감·Idempotency-Key)·배송 | `/orders · /orders/{id}/shipment` | ✅ |
| 결제(Toss·분할·환불 동시성) | `/payments · /payments/split · /api/payments/*/refunds` | ✅ |
| 리뷰·게임 | `/reviews · /games` | ✅ |
| 시스템관리(메뉴·공통코드·RBAC) | `/admin/menus · /admin/common-codes · /admin/rbac` | ✅ |
| 내부 대사·프로젝션 백필 | `/internal/recon · /admin/settlement-projection` | ✅ |

**발행 이벤트**: `payment.captured/refunded · order.created · user.registered · product.changed`.
**상태머신**: Payment / Order (`canTransitionTo()` 강제).

---

## Phase 2 — 정산 코어 (settlement-service, 8082 / settlement_db) ✅ DONE 🟡
> 프로젝트의 심장. order 코드·DB 의존 0 을 유지한 채 이벤트 드리븐 CQRS(ADR 0020)로 조회한다.

| 기능 블록 | 경로 | 상태 |
|----------|------|------|
| 프로젝션 뷰 적재 | `settlement_{order,payment,user,product}_view` ← Kafka | ✅ |
| 정산 생성/확정 | `payment.captured` 컨슈머(생성) + `SettlementConfirmJob`(확정, Batch) | ✅ |
| 정산 조회/검색 | `/settlements · /api/settlements/query`(ES) — REST 조회 전용 | ✅ |
| 지급(payout) | `/admin/payouts` — 확정·홀드백 해제 → Payout **멱등 자동 생성** | 🟡 실송금·계좌 레지스트리 잔여 |
| 복식부기 원장·리포트 | `/api/ledger · /api/reports`(PDF) | ✅ |
| 차지백 | `/admin/chargebacks` → 역분개 1:1 | ✅ |
| PG 대사 | `/admin/pg-reconciliation` → 차이 승인 → clawback 역정산 루프 | ✅ |
| 운영(DLQ·정합성·이벤트추적) | `/admin/dlq · /admin/integrity · /admin/event-track` | ✅ |
| 월마감·기간잠금·확정 시산표 | (최근 A1 Phase 5) | ✅ |

**정책**: 수수료 NORMAL 3.5%/VIP 2.5%/STRATEGIC 2.0%(정산시점 스냅샷 영구보존), 주기 T+7/T+3/T+1, 홀드백 30%·30일 / 10%·14일 / 0%.
**잔여**: payout 실송금 트리거 + 셀러 계좌 레지스트리(그린필드) — Phase 8 참조.

---

## Phase 3 — 금융 확장 (loan·investment·account) ✅ DONE 🟡
> 정산금(seller payable)을 담보/재원으로 쓰는 상위 금융 도메인. 전부 정산 이벤트에 의존.

### 3a. loan-service (8084 / lemuel_loan) ✅
- 선정산 대출(`/loans`) — 미확정 정산금 담보 선지급, 상환 saga 연계
- 기업 신용대출(`/loans/corporate`) — 재무제표+평판 → creditScore/등급/한도, 실행 ADMIN+비관적 락
- 상환 시뮬레이션(`/loans/repayment/simulate`) — BULLET/EQUAL_PAYMENT/EQUAL_PRINCIPAL 순수 미리보기
- 원장 2전표 + `loan.corporate_loan_disbursed` 발행

### 3b. investment-service (8100 / lemuel_investment) ✅
- 투자점수(수익성35+안정성35+성장성30, AAA~CCC, ≥60 적격)
- 투자주문 상태머신 `REQUESTED→APPROVED→EXECUTED/REJECTED/CANCELED`
- 재원 = `seller_funding_view`(확정 정산금) − 집행 투자금(부족/부적격 422), 소유권 JWT 파생
- `investment.executed` 발행

### 3c. account-service (8102 / lemuel_account) 🟡
- 6토픽 소비 → 전사 복식부기 GL(`account_entries`, 전표당 차1·대1), 소비 전용(발행 없음)
- 6계정: CASH·LOAN_RECEIVABLE·CORPORATE_LOAN_RECEIVABLE·INVESTMENT_ASSET·SELLER_PAYABLE·SETTLEMENT_SCHEDULED
- 시산표(`/api/account` trial-balance)
- **잔여(ADR 0026 회계 결정 대기)**: 셀러 payout 현금 유출 GL 인식 + 시산표 실검증 — Phase 8 참조.
  (payout.completed → DR SELLER_PAYABLE / CR CASH 폐루프는 Option A 로 배선됨, 실검증 잔여)

---

## Phase 4 — 공개조회 위성 + 부가 서비스 ✅ DONE
> shared-common 미의존/제한 스캔 + 자체 최소 SecurityConfig(GET 공개, `/admin/**` 는 X-Internal-Api-Key 게이트).

| 서비스 | 포트 | 기능 | 수집 | 상태 |
|--------|------|------|------|------|
| financial-statements | 8086 | 재무제표 공개조회(비율은 도메인 계산·미저장) | DART(`DART_API_KEY`) | ✅ |
| economics | 8087 | 기준금리·국고채·환율·CPI | ECOS(`ECOS_API_KEY`) | ✅ |
| company | 8090 | 뉴스(본문 미저장)·평판·문서함(ADMIN/MANAGER JWT) | NAVER + 감성분석 | ✅ |
| market | 8094 | KRX 시세·시총(**PER/PBR 미계산**) | 금융위(`KRX_API_KEY`) | ✅ |
| common-data | 8098 | data.go.kr 범용 커넥터(SSRF 가드·멱등 upsert) | `DATA_GO_KR_API_KEY` | 🟡 실수집 미검증(키 미보유) |
| operation | 8092 | 인시던트 라이프사이클 + 신호 BC + Phase3 이상탐지 | Alertmanager webhook | ✅ (Phase4 AI브리핑 로드맵) |
| ai | 8096 | 챗봇(SSE)·provider 스위치·PII 마스킹·비용가드 | Gemini/Anthropic | ✅ |

---

## Phase 5 — 조직·멤버십 (organization-service, 8104 / lemuel_organization) ✅ DONE 🟡
- 조직(SELLER/CORPORATE) 생성(생성자 자동 OWNER) / 조회
- 멤버십 초대·수락·역할변경(OWNER 전용)·제거(**마지막 OWNER 보호 불변식**)
- 인가는 JWT 주체의 조직 내 역할로 판정(`OrgAuthorizer`, IDOR 방지)
- 상태머신: Organization ACTIVE⇄SUSPENDED, Membership INVITED→ACTIVE⇄SUSPENDED→REMOVED
- **이벤트 발행 전용** `organization.created · member_joined` — 🟡 소비처 미배선(계약 스키마는 존재, 의도된 상태)

---

## Phase 6 — 폴리글랏 7종 ✅ DONE (MVP)
> settings.gradle 미포함 standalone, `polyglot-ci.yml` 별도 CI, gateway 미라우팅(market-stream SSE 만 예외).

| 서비스 | 언어 | 포트 | 역할 | 상태 |
|--------|------|------|------|------|
| notification | Kotlin | 8130 | 5토픽 → 다채널 알림(코루틴 팬아웃·타임아웃/재시도·멱등) | ✅ |
| reconciliation | Kotlin | 8131 | 정산 대사(코루틴 병렬 fetch·sealed Discrepancy·@Scheduled 19:00) | ✅ |
| market-stream | Go | 8110 | 실시간 시세 SSE/WS(goroutine Hub) | ✅ |
| payment-webhook | Go | 8111 | Toss 웹훅(HMAC·멱등) → `payment.confirmed` 발행 | ✅ |
| screening-backtest | Python | 8120 | 백테스트(수익률·MDD·Sharpe·승률) | ✅ |
| settlement-anomaly | Python | 8121 | 이상탐지(MAD z-score + IsolationForest) | ✅ |
| forecast | Python | 8122 | 시계열 예측(Holt-Winters + seasonal-naive) | ✅ |

---

## Phase 7 — Gateway (8080) ✅ DONE
- Spring Cloud Gateway(WebFlux) 경로 predicate 라우팅, 자체 인증 필터 없음(각 서비스 SecurityConfig 강제)
- 위성 8종은 공개 조회 API 만 라우팅(`/admin/**` 외부 미노출), organization `/api/organizations/**`(JWT 필수)
- 신규 서비스 배선 5곳(스캔·JPA·gateway·nginx·Dockerfile) → `msa-service-wiring` 스킬

---

## Phase 8 — 잔여 작업 (Open) — STATUS "다음 할 일" 정본
> 아래만 열려 있다. 각 항목의 완료 기준은 [`DONE_CRITERIA.md`](./DONE_CRITERIA.md) §잔여 참조.

| # | 작업 | 선행 | 상태 |
|---|------|------|------|
| 8-1 | ADR 0026 회계 결정 확정 → account payout 현금흐름 인식 구현 + **시산표 실검증** | 회계 결정 | ⬜ 결정 대기 |
| 8-2 | payout 파이프라인 **실송금 트리거 + 셀러 계좌 레지스트리**(그린필드) | 없음(생성 배선은 완료) | ⬜ |
| 8-3 | ADR 0022(이벤트 스키마 레지스트리) 정식 도입 검토 — 0024 계약-as-code 가 경량 선행 | 없음 | ⬜ 검토 |
| 8-4 | common-data 실수집 검증 | `DATA_GO_KR_API_KEY` 확보 | ⬜ 자원 대기 |
| 8-5 | operation Phase 4 AI 브리핑 | Phase 3 완료됨 | ⬜ 로드맵 |
| 8-6 | organization 이벤트 소비처 배선(소비자 생기면 ADR 0024 절차로 계약 편입) | 소비 유스케이스 | ⬜ |
| 8-7 | 신규 서비스 통합테스트 보강(커버리지 게이트 LINE 90% 후속) | 없음 | 🟡 진행 |

---

## 의존 순서 (요약)
```
Phase 0 (기반)
  └─ Phase 1 (order: 이벤트 발원)
       └─ Phase 2 (settlement: 프로젝션·정산·원장)
            ├─ Phase 3 (loan·investment·account: 정산금 소비)
            └─ Phase 6 (notification·reconciliation·anomaly·forecast: 정산 이벤트/데이터 소비)
  ├─ Phase 4 (위성: 재무·경제·기업·시세·공공데이터 — order/settlement 와 의존 0, 병행 가능)
  ├─ Phase 5 (organization: 독립, 발행 전용)
  └─ Phase 7 (gateway: 전 서비스 라우팅, 마지막 배선)
```
> 위성(Phase 4)·조직(Phase 5)은 거래/정산 계보와 의존이 0 이라 언제든 병행 착수 가능. 나머지는 위→아래 단방향 의존.
