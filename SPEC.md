# Lemuel 기능명세서 (Functional Specification)

이커머스 + 정산(Settlement) MSA 플랫폼의 전체 기능 명세. 코어 JVM 18개 마이크로서비스 + API Gateway 에
폴리글랏 7종(Kotlin 2 · Go 2 · Python 3)을 더한 **총 26개 서비스** 헥사고날 백엔드이며,
원래 단일 모놀리스였으나 Bounded Context 로 분리했다.
(위 26 밖에 미배선 standalone 1종이 더 있다 — `receipt-ocr-service`, §3.21.)
아키텍처·컨벤션은 [`CLAUDE.md`](./CLAUDE.md), 아키텍처 결정은 [`docs/adr/`](./docs/adr/) 참조.

- 문서 상태: 현행 코드 기준 요약 명세 (엔드포인트 표면 + 도메인 규칙 + 이벤트 흐름)
- 범위: **백엔드 표면**이다. 프론트 화면(라우트·메뉴)은 이 문서가 아니라 `menus` 시드 +
  `frontend/src/App.tsx` 가 정본이고, 화면↔API 대응은 `api-screen-gate.test.mjs` 가 기계로 강제한다.
- 최종 갱신: 2026-08-22

---

## 1. 개요

| 항목           | 내용                                                                                                                                                                                                |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 도메인         | 주문·결제(포인트·기프트카드 원장 포함)·정산·선정산/기업대출·투자·계정계·재무제표·경제지표·기업뉴스평판·운영관제·주식시세·AI챗봇·공공데이터·조직/멤버십·법인카드·보험(GA)·셀러예치금·게시판·교육 + 알림·정산대사·실시간시세·결제웹훅·백테스트·이상탐지·시계열예측 |
| 서비스 수      | **26개** — 코어 Java 18 + API Gateway + Kotlin 2(알림·대사) + Go 2(스트리밍·웹훅) + Python 3(백테스트·이상탐지·예측). 별도 standalone: receipt-ocr(영수증 판독, 미배선)                          |
| 아키텍처       | 헥사고날(Ports & Adapters), DB-per-service, 이벤트 드리븐(CQRS 프로젝션)                                                                                                                            |
| 서비스 간 연계 | **Kafka 이벤트로만** (코드·DB 직접 의존 0) + 내부 대사 API(`/internal/recon`)                                                                                                                       |

### 기술 스택

| 구분                          | 기술                                                                                                                                                       |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 언어 / 프레임워크             | **Java 25 / Spring Boot 4.0.7**(코어 18+gateway) · Kotlin 2.0 / Boot 3.3·JDK 21(이벤트 2종) · Go 1.22/1.23 `net/http`(엣지 2종) · Python 3.11 / FastAPI(ML 3종 + receipt-ocr) |
| 빌드                          | Gradle 멀티모듈 (Kotlin DSL), shared-common 은 composite build. **폴리글랏 7종은 standalone 빌드**(settings.gradle 미포함, `polyglot-ci.yml` 별도 CI)      |
| Gateway                       | Spring Cloud Gateway 2025 (WebFlux)                                                                                                                        |
| DB / 검색                     | PostgreSQL 17 (DB-per-service) / Elasticsearch 8.17                                                                                                        |
| 메시지                        | Kafka (Redpanda 호환)                                                                                                                                      |
| PG 연동                       | Toss Payments                                                                                                                                              |
| 배치 / 캐시                   | Spring Batch / Caffeine(L1) + 선택 Redis(L2)                                                                                                               |
| PDF / 마이그레이션            | iText 8 / Flyway                                                                                                                                           |
| 관측 / 회복탄력성 / RateLimit | Micrometer+Prometheus+OTLP / Resilience4j / Bucket4j                                                                                                       |

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
- **이벤트 계약-as-code (ADR 0024)**: cross-service 56개 토픽의 JSON Schema + 정본 샘플이
  `shared-common/src/testFixtures/resources/contracts/events/` 에 단일 출처로 존재. 프로듀서·컨슈머 양방향 계약 테스트로 드리프트 차단.
- **토픽 전송 속성 (ADR 0035)**: 파티션·보존기간·순서키·소유 모듈의 정본은
  `shared-common/src/main/resources/kafka/topic-catalog.json`(등재 63건 — 계약 스키마가 붙은 것보다
  넓다: `lemuel.ops.*` 등 계약 없는 내부 토픽도 전송 속성은 카탈로그가 관리한다).
  **파티션 수 변경 = 키 재해시 = 순서 보장 소급 붕괴**라 프로비저너는 없는 토픽만 만들고 기존
  파티션은 늘리지 않는다. 신규 토픽 미등록은 `kafka-topic-gate.test.mjs` 가 CI 에서 FAIL.
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

DB: opslab(로컬 `application.yml` 기본값 — **compose 는 `inter`** 다. 스키마명은 양쪽 모두 `opslab`).
회원·상품·장바구니·주문·결제(포인트·기프트카드 원장 포함)·배송 + 정합성 부속(recon, projection backfill).

| 도메인             | API(대표 경로)                                                                                                                                | 기능                                                                                |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| 회원/인증          | `/auth`, `/users`, `/memberships`                                                                                                             | 회원가입·로그인(JWT 발급)·비밀번호 재설정·멤버십 승인                               |
| 상품/카테고리/태그 | `/api/products`, `/products/{id}/variants`, `/categories`, `/admin/categories`, `/display-sections`, `/admin/display-sections`, `/admin/option-catalog`, `/api/tags`, `/admin/products/{id}/images` | 상품·SKU(variant)·이미지·카테고리 트리(계층·정렬·soft delete)·진열 섹션·옵션 카탈로그·태그. ※ `/api/categories` 매핑은 없다(gateway 라우트만 존재) |
| 장바구니/쿠폰      | `/users/{userId}/cart`, `/coupons`                                                                                                            | 장바구니, 쿠폰 발급/사용(등급별 권한)                                               |
| 주문               | `/orders`, `/orders/{id}/shipment`, `/api/bulk-orders`, `/admin/shipments`, `/admin/shipping-policies`, `/admin/payment-expiry`, `/admin/stock-reclaim` | 단건/다건(SKU 자동 재고차감) 주문, Idempotency-Key 중복제출 차단, **대량주문 초안**(CSV 멀티파트 업로드→셀 단위 검증 격자→`/{id}/confirm` 일괄 확정, `/columns` 스펙·`/revalidate`·폐기), 배송 라이프사이클·관리 콘솔·셀러 배송정책, 결제 만료·재고 회수 배치 트리거 |
| 결제               | `/payments`, `/payments/split`(+`/{id}/confirm-deposit`, `/{id}/refund`), `/api/payments/*/refunds`, `/api/payments/by-order/{orderId}/cash-receipt`, `/admin/refunds`, `/admin/pg` | Toss 결제 생성·인증·캡처·환불, **텐더 결제**(카드·계좌이체·가상계좌·포인트·기프트카드 혼합, 하한 1개), **입금 대기 창**(§아래), 현금영수증 발급·조회, PG 라우팅(서킷브레이커 상태 조회 — 설정 엔드포인트 없음), 환불이력·자동 재시도 소진 건 관리자 환불승인 |
| 포인트 원장        | `/api/points/me`, `/admin/points`                                                                                                             | 내 잔액(가용/선점/총액)·로트 조회, 관리자 수기 지급/차감·적립정책 등록/종료·만료 예정 조회·소멸 배치 수동 실행·사용한도 조회 |
| 기프트카드 원장    | `/api/gift-cards/redeem`, `/api/gift-cards/me/balance`, `/admin/gift-cards/issue`, `/admin/gift-cards/expiry/run`                             | 코드 등록(사용자 귀속)·내 잔액 조회, 관리자 발행·소멸 배치 수동 실행                |
| 리뷰/게임          | `/reviews`, `/games`, `/admin/reviews`                                                                                                        | 상품 리뷰, 게임(이벤트성), 리뷰 운영 콘솔(숨김·복원·상태 집계·CSV)                  |
| 시스템 관리        | `/api/menus`, `/admin/menus`, `/admin/common-codes`, `/admin/rbac`, `/admin/seller-tiers`, `/admin/members`, `/admin/coupons`, `/admin/audit-logs` | 메뉴 조회(`/api/menus/me`)·트리·순환참조 방지·배치정렬, 공통코드 그룹/항목, RBAC 역할·권한, 셀러 등급 콘솔(ADR 0031), 회원 콘솔(역할 변경·상태 집계·CSV), 쿠폰 콘솔(활성/비활성·사용이력·CSV), 감사로그 조회(행위 집계·CSV) |
| 내부/정합성        | `/internal/recon`, `/admin/settlement-projection`                                                                                             | order 자기 합계 노출(대사, X-Internal-Api-Key), 프로젝션 백필                       |

**포인트 원장**(`point/`, 설계 정본 [`docs/plan/point-ledger.md`](docs/plan/point-ledger.md)) — `TenderType.POINT`
가 잔액 없이 열려 있던 회계 구멍을 닫은 도메인. **로트(lot) 단위 append-only 원장**이다.

- 소비 순서는 `expires_at ASC NULLS LAST, id ASC`(**만료 임박분 우선**) — 출처(origin)로 우선순위를 주지 않는다.
- **환불 복원**: 원 로트가 `ACTIVE`·`EXHAUSTED` 면 그 로트를 되살려 **원래 유효기간을 유지**하고,
  `EXPIRED`·`REVOKED` 면 `REFUND_RESTORE` 출처로 신규 로트를 발급하되 **원 로트가 가졌던 기간 길이**를 승계한다.
- 복원 대상 계정은 요청이 아니라 **원 사용 엔트리에서 도출**한다(`RestorePointCommand` 에 userId 가 없다 — IDOR 차단).
  반대로 사용(use)의 주체는 JWT 에서 파생한다.
- **선점(hold)**: 가상계좌·무통장처럼 입금 대기가 걸린 결제는 차감이 아니라 선점이다.
  `hold` 는 available−X/locked+X 로 **총액을 바꾸지 않고 로트도 건드리지 않는다**(엔트리도 남기지 않는다 —
  감사 흔적은 `point_holds` 자신이 진다). 3자 대조 축이 `available` 이 아니라 **`total`** 인 이유다.
- 입금 확정과 만료 배치의 경합은 **계정 행 비관적 락 + 선점의 종단 전이 가드**로 막는다.
  선점이 없을 때 확정은 예외(고객 포인트를 받지 않고 주문이 확정된다), 해제는 경고 후 통과(막으면 만료 배치가 함께 멈춘다).
- 멱등 최종 방어선은 `uq_point_entries_natural` — 같은 tender 의 중복 차감/복원이 DB 에서 막힌다.

**기프트카드 원장**(`giftcard/`, 정본 [`docs/plan/gift-card-ledger.md`](docs/plan/gift-card-ledger.md)) —
포인트와 원장 패턴은 같고 **다른 것만** 둔다.

- 잔액은 계정이 아니라 **증서(카드) 하나**에 붙는다(요약 테이블 없음). 부분 사용 허용(잔액 이월), 소멸은 카드 단위.
- **코드가 곧 재산**: `code_hash`(SHA-256 UNIQUE)만 저장하고 표시는 `code_last4`. 코드는 발행 응답에서 **한 번만**
  나가고 이후 재조회 불가. `GC-` + Crockford Base32 16자 ≈ 80비트. 등록 경로에 `RateLimitFilter`
  (`giftcard-redeem`) — 실패한 등록 시도는 코드를 로그에 남기지 않는다.
- **등록은 1회뿐**이다(등록된 카드를 다른 사용자가 다시 등록하면 코드를 아는 사람이 남의 잔액을 가져간다).
- 포인트 로트 재사용을 채택하지 않은 이유는 회계다 — 두 부채를 한 계정에 뭉치면 분리 표시가 불가능하다.
- **선점 수단이 없다**(Phase 2 는 포인트만) → `기프트카드 + 가상계좌` 조합은 PG 호출 전에 거절한다.

**입금 대기 창**(가상계좌·무통장) — 텐더 하나라도 입금 대기면 **승인까지만** 하고 캡처하지 않는다(카드 텐더도
마찬가지 — 입금이 끝내 안 오면 카드만 먼저 매입해 둔 것을 환불로 되돌려야 한다). 결제는 `READY` 로 남아
미입금 만료 배치가 집어갈 수 있고, 주문 `PAID` 전이도 `payment.captured` 발행도 **입금 확인 시점에만** 일어난다.
확정 진입점 `POST /payments/split/{paymentId}/confirm-deposit` 은 웹훅 중복 통보가 정상이라 **멱등이 기능의 일부**다.
만료 판정의 진실원은 `paymentMethod` 문자열이 아니라 **텐더 목록**(`PaymentDomain.awaitsDeposit`).

### 3.2 settlement-service — 정산 (port 8082, 자체 DB settlement_db)

정산 생성/확정, 지급(payout), 복식부기 원장·기간마감, 세무(부가세·원천징수·세금계산서), 차지백, PG 대사,
지급후 회수채권, 정보계 월마감, ES 색인, PDF 리포트.
order/payment/user/product 는 Kafka 이벤트로 적재하는 자체 프로젝션(`settlement_*_view`)으로만 조회(코드·DB 의존 0).

| 도메인      | API                                                                                    | 기능                                                                                                                                                                                                |
| ----------- | -------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 정산        | `/settlements`, `/api/settlements`, `/api/settlements/query`                           | 정산 조회/검색(ES) — **REST 는 조회 전용**. 생성은 `payment.captured` 컨슈머, 확정은 Spring Batch(`SettlementConfirmJob`)로 비동기 처리                                                             |
| 지급        | `/admin/payouts` (ADMIN)                                                               | 셀러 지급 실행·재시도(펌뱅킹 mock), 반송(bounce) 기록·재지급                                                                                                                                        |
| 지급 계좌   | `/admin/seller-bank-accounts` (ADMIN/MANAGER) · `/api/seller/bank-account` (셀러 본인) | 지급 계좌 레지스트리 — 관리자 대행 등록·정정과 셀러 셀프서비스 upsert/조회. 셀러 경로는 식별자를 JWT 주체(userId)에서만 파생(IDOR 차단), 계좌번호는 저장 시 암호화(`PAYOUT_ENC_KEY`)·노출 시 마스킹 |
| 원장/리포트 | `/api/ledger`, `/api/reports` (ADMIN/MANAGER) · `/admin/ledger-periods` (ADMIN)         | 복식부기 원장 조회, 캐시플로우 리포트(PDF), 원장 월마감 — 확정 시산표가 **균형일 때만** 마감(불균형 422), 마감 기간 신규 전표 차단·재개봉 없음                                                       |
| 매출 통계   | `/api/reports/sales-stats` (ADMIN/MANAGER)                                             | 기간 매출 요약 + **직전 동일 길이 기간** 대비 증감(전기 0 이면 증감률 `null` — 0% 아님), 축별 구성비 5종(결제수단·셀러등급·정산상태·셀러·상품). 기간 상한 366일, 랭킹 상위 N 은 1~100 클램프. 추이는 기존 `/api/reports/cashflow` 재사용 |
| 세무        | `/admin/tax`, `/admin/tax/scans`, `/admin/seller-tax-profiles` (ADMIN/MANAGER) · `/api/tax-invoices`, `/api/tax-invoices/scans/{scanId}` (셀러) | 부가세 **포함과세** 산출(`floor(수수료×10/110)`), 개인 셀러 원천징수 3.3% 를 **실지급액에서 공제**, 세금계산서 발행·PDF, 셀러 업로드 스캔본 OCR 자동매칭(ADR 0029) + **사람 손이 필요한 스캔 리뷰 큐**(`/{scanId}/reject`·`/rematch` — 신뢰도 미달·미매칭 건은 자동 대사 결론을 기록하지 않는다) |
| 차지백      | `/admin/chargebacks` (ADMIN)                                                           | 지급 거절/분쟁 처리                                                                                                                                                                                 |
| PG 대사     | `/admin/pg-reconciliation`, `/admin/reconciliation` (ADMIN/MANAGER)                    | PG 정산파일 업로드→대사→차이 승인/거절(역정산 트리거)                                                                                                                                               |
| 회수        | `/admin/recoveries` (ADMIN/MANAGER)                                                    | 지급후 회수 채권 — holdback 으로 흡수 못한 잔액을 원금으로 개시, 후속 정산 확정 시 자동 상계, 정체 시 `MANUAL_REQUIRED` 이관                                                                         |
| 월마감      | `/admin/monthly-closing` (ADMIN)                                                       | 정보계 월마감 — 셀러 월 정산 마트 적재(완결된 과거 월만, 기간 단위 교체로 멱등). 원장 마감된 기간 재마감은 409                                                                                       |
| 정산 운영   | `/admin/settlements`(rerun·holdback-preview), `/admin/commission-rates` (ADMIN)         | 정산 배치 재실행(소급 상한 90일), 보류 해제 미리보기, 유효기간 수수료율 정책 등록·시뮬레이션(ADR 0032 — 미래 구간만)                                                                                |
| 운영        | `/admin/dlq`, `/admin/integrity`, `/admin/event-track`, `/admin/audit-trail`            | Kafka DLT/DLQ 관리, 정합성 자가진단 8종 콘솔(원장완결성·payout 대사·반송 대사·홀드백·정체상태·환불조정·소비이벤트수·프로젝션 차이), 소비 이벤트 3분류(정상·중복·격리) 추적·재처리, 감사 이력 조회(행위 집계·CSV) |
| 백필/이관   | `/admin/backfill/ledger-reverse`, `/admin/payouts/backfill`, `/admin/payouts/pii`, `/admin/outbox/ledger` (ADMIN) | 원장 역분개 소급 백필(상태·실행), payout 백필 상태, 지급계좌 PII 재암호화(키 로테이션), 원장 Outbox 실패 건 조회·재적재 |
| 내부(대사)  | `/internal/recon/settlements` (X-Internal-Api-Key)                                      | settlement 자기 합계 노출 — order 의 `/internal/recon` 과 대칭. 양측이 **자기 DB 만** 읽는다(cross-DB 연결 0)  |

**수수료(등급별 스냅샷 보존)**: NORMAL 3.5% / VIP 2.5% / STRATEGIC 2.0%. **정산주기**: T+7 / T+3 / T+1 영업일.
**홀드백**: NORMAL 30%/30일, VIP 10%/14일, STRATEGIC 0%.

### 3.3 loan-service — 선정산 + 기업대출 + 담보/개인신용 + 물건금융(리스·할부) (port 8084, 자체 DB lemuel_loan)

| 도메인             | API                              | 기능                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ------------------ | -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 선정산 대출        | `/loans`                         | 셀러 미확정 정산금 담보 선지급, 상환은 정산 이벤트 saga 연계                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| 기업 신용대출      | `/loans/corporate`               | 상장사(stockCode) CEO 신청 → `CorporateCreditPolicy` 가 재무제표+평판으로 creditScore(0~~100)/등급(A~~E)/한도 산정. **신청(request) 시점에 E등급·한도초과 422**. 실행(disburse)은 ADMIN 전용 + 비관적 락(이중지급 방지)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| 담보/개인신용 대출 | `/loans/secured`                 | **주택담보**(`/mortgage`)·**금융자산담보**(`/financial-asset`, 예금·채권·주식)·**개인신용**(`/personal`) 3종. `SecuredLoan` 단일 애그리거트가 담보 optional 로 셋을 수용한다. 한도는 담보형=유효담보가치×유형별 인정비율(부동산 LTV 기본 0.70 주입 / 보증 100%·예금 95%·채권 80%·주식 60%), 신용형=외부 CB 점수→등급(≥850 A/≥750 B/≥650 C/≥550 D, 미만 E 불가)별 정액. 담보평가는 위성 실연동(EQUITY=market 종가×수량, REAL_ESTATE=commondata 실거래가) + 실패·키 부재 시 제시값 폴백. 금리=기준금리+가산(담보형 고정 0.8%p / 신용형 A1.5·B2.5·C4.0·D6.0%p). 신청 시점 **422**(한도초과·등급미달). 승인 시 담보 유효화, 실행은 운영자 전용+비관적 락. 장기 분할상환이라 **연체·기한이익상실**이 상태머신에 포함(`DISBURSED→OVERDUE→DEFAULTED`, 직행 금지) |
| 물건금융(리스·할부) | `/loans/leases`                  | **금융리스·운용리스·할부** 3종. 리스원금=취득원가−선수금−보증금, 월납입은 잔존가치 현가를 뺀 연금현가식(월이율 0이면 균등분할). 불변식: 잔존가치 < 리스원금(회수할 원금이 남아야 한다). 상태 `APPLIED→APPROVED→ACTIVE→{OVERDUE→DEFAULTED, MATURED, EARLY_TERMINATED}`(승인 전 취소·반려). 개시 시 `LEASE_RECEIVABLE` 전표 + `lemuel.loan.lease_activated` 발행. 중도해지는 잔여원금+위약금(상한 10%) 견적 |
| 담보 운영          | `/loans/secured/{id}/collateral` | 재평가·마진콜 판정(담보유지비율 <1.40 마진콜 / <1.20 강제처분)·담보 처분·보증기관 대위변제. 운영자 전용, 처분·대위변제는 `Idempotency-Key` 선점. 재평가 값은 외부 입력(감정가·시세)이라 배치가 아니라 API 가 진입점                                                                                                                                                                                                    |
| 담보서류 OCR (ADR 0036) | `/loans/secured/{id}/collateral/documents`(멀티파트 업로드·`/latest` 조회), `/loans/secured/collateral-documents/{id}/review` | 담보서류(감정평가서·등기부) 업로드→Gemini OCR→담보 설정값 자동 대사(감정평가액 정확 일치 · **선순위 채권최고액 — 자기신고값의 유일한 검증 수단** · 평가기준일 ±1일 · 신뢰도 <0.80 리뷰). `(loan_id, file_hash)` 멱등. **승인 게이트**: 서류가 첨부된 대출은 최신 서류 MATCHED 여야 승인(`LOAN_COLLATERAL_DOC_NOT_MATCHED` 422, 무서류는 점진 도입 — `app.loan.collateral-ocr.required=true` 면 담보형 미첨부도 거절). 판독 실패 무폴백 503. 업로드는 본인/운영자, 리뷰 종결은 운영자 전용 |
| 평판               | `/loans/company-reputation`      | 신용평가용 기업 평판 프로젝션 조회                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| 상환 시뮬레이션    | `POST /loans/repayment/simulate` | 원금·기간·연이자율·상환방식(만기일시 BULLET / 원리금균등 EQUAL_PAYMENT / 원금균등 EQUAL_PRINCIPAL)으로 회차별 상환표를 미리 계산하는 **순수 미리보기**(부수효과·영속화 없음). 원 단위 반올림·마지막 회차 잔여 흡수로 원금 합 일치                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |

자체 복식부기 원장 2전표 + `lemuel.loan.corporate_loan_disbursed` 발행.

원장 계정은 **10종**이다: 기본 6종(LOAN_RECEIVABLE·CASH·FEE_RECEIVABLE·FEE_INCOME·BAD_DEBT_EXPENSE·BAD_DEBT_ALLOWANCE) + 담보·물건금융 확장 4종(GUARANTEE_FEE_EXPENSE·COLLATERAL_DISPOSAL_LOSS·COLLATERAL_DISPOSAL_GAIN·LEASE_RECEIVABLE).
담보/개인신용 대출의 일반 흐름은 기본 6계정으로 처리한다: 실행 `SEC_DISBURSE`(대출채권/현금), 회차 상환 `SEC_REPAYMENT`(현금/대출채권) + `SEC_INTEREST`(현금/수수료수익). 담보 처분·상각은 확장 계정을 쓴다. 장기 분할상환이라 상환·이자 전표는 대출 1건당 N회 발생하므로 중복분개 유니크(`uq_loan_ledger_reference_accounts`)에서 제외된다. `lemuel.loan.secured_loan_disbursed` / `.secured_loan_repaid` 발행 — account-service 가 차주(BORROWER) 원장 `SECURED_LOAN_RECEIVABLE` 분개로 소비한다(원금만, 완제 이벤트에 `prepaymentFee` 옵셔널 필드 포함).

**Phase 2 완료(2026-07-30)** — 담보유형 5종·담보권 순위(P2-1·2), 원장 계정·실행 전표(P2-3), 재평가·마진콜(P2-4), 담보 실행·상각(P2-5), 중도상환+수수료(P2-6), economics 기준금리 실연동(P2-7a), account-service GL 소비 매핑, **담보평가 실연동(P2-7b)**: 금융자산담보 상품(`FINANCIAL_ASSET`, `POST /loans/secured/financial-asset`, 예금·채권·주식 담보 + 유형별 인정비율 한도) 신설, EQUITY 는 market-service 종가×수량, REAL_ESTATE 는 commondata 실거래가(수집 소스 설정 시), 조달 실패·키 부재는 제시값 폴백(신청 가용성 우선 — 기준금리와 동일 원칙).

### 3.4 financial-statements-service — 재무제표 조회 (port 8086, lemuel_financial)

- `/api/financial/companies` — 코스피·코스닥 상장사 요약 재무제표 공개 조회(부채비율·영업이익률·ROA·자본총계).
- `/admin/financial/sync` — DART OpenAPI 수집 배치(`DART_API_KEY`, corp_cls Y/K→코스피/코스닥 매핑). 샘플 시드는 제거 — 실수집 전용.
- investment 투자점수·loan 기업대출 신용평가의 회계자료 원천. 타 서비스와 코드·DB·이벤트 의존 0.

### 3.5 economics-service — 경제지표 조회 (port 8087, lemuel_economics)

- `/api/economics/indicators` — 기준금리·국고채3년·USD/KRW·CPI 공개 조회.
- `/admin/economics/sync` — 한국은행 ECOS 수집(`ECOS_API_KEY`, URL 키는 로그 마스킹). 샘플 시드는 제거 — 실수집 전용(지표 카탈로그 행만 V1 유지).

### 3.6 company-service — 기업 뉴스·평판·문서함 (port 8090, lemuel_company, ADR 0023)

| 도메인      | API                                                                                                                                                                        | 기능                                                                                              |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 뉴스·기업   | `/api/company/companies`                                                                                                                                                   | 기업 뉴스 기사(제목·요약·링크만, 본문 미저장) + 기업 마스터 공개 조회                             |
| 문서함      | `/api/company` (문서 목록·다운로드 — **ADMIN/MANAGER JWT 게이트**)                                                                                                         | CEO 브리핑 docx 업로드/다운로드                                                                   |
| 사업장 인력 | `/api/company/workforce` (목록), `/api/company/workforce/detail` (단건+비교)                                                                                               | 국민연금 사업장가입자 공개데이터 기반 인원수·추정연봉 조회 + 업종·지역 집단 비교                  |
| 수집/관리   | `/admin/company/collect`, `/admin/company/documents`, `/admin/company/reputation`, `/admin/company/sellers`, `/admin/company/companies`, `/admin/company/workforce/import` | 네이버 뉴스 수집(`NAVER_*`)·감성분석(keyword/Claude/Gemini)·평판 스코어·셀러 링크·사업장 CSV 적재 |

기업 식별자(stockCode/corpCode)는 financial 과 공용 비즈니스 키. Phase 3 평판 변동 Outbox 발행.

**사업장 업종·지역 비교**(Seed: `docs/plan/seeds/company-service-workforce-comparison.seed.yaml`) — 사업자등록번호가 앞
6자리만 공개돼 타 서비스와 자동 조인이 불가하므로 company-service 단독으로 완결한다.

- 상세 조회는 내부 id 가 아니라 **복합키 query parameter**(`name`·`bizRegNoPrefix`·`snapshotMonth`) — 실제 데이터에
  따옴표·느낌표가 든 사업장명이 있어 path variable 은 안전하지 않다. 미매칭 404 / 형식 위반 400.
- 비교는 **업종축(6자리 → 앞3자리)과 지역축(시도+시군구 → 시도)이 독립**, 각 축 최대 2단계 폴백, 최소 표본 10건.
  중앙값은 `percentile_cont(0.5)`, 백분위는 `cume_dist`. 평균·업종×지역 교차는 제공하지 않는다.
- 사유 코드 3종: `SAMPLE_TOO_SMALL` · `INDUSTRY_CODE_MISSING`(원본 공란) · `REGION_UNPARSEABLE`.
- **조회 경로는 집계를 계산하지 않는다** — CSV 적재 후 월별 중앙값·표본수·사업장별 백분위를 단일 트랜잭션으로
  통째 교체(`BUILDING`→`COMPLETE`)하고, 조회는 COMPLETE 인 월만 읽는다. 대사: `source = accepted + rejected`.
- 기준소득월액 상한 도달은 실패 사유가 아닌 **신뢰도 플래그**(`salaryCapReached`, 고시표 범위 밖이면 상한액 null).
- 금액 필드는 JSON 에서 소수 문자열, 비율·건수는 수치.

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
- **provider 스위치**(`app.ai.provider`, 기본 gemini): Gemini(RestClient) / Anthropic(Spring AI SDK) /
  DeepSeek(OpenAI 호환 RestClient) 중 하나만 등록. DeepSeek 어댑터는 `base-url` 만 바꾸면 **로컬 Ollama**
  (OpenAI 호환 엔드포인트)도 같은 코드로 부른다 — 외부 크레딧 없이 개발·데모 가능.
  reasoning 계열의 사고과정은 사용자에게 노출하지 않는다(`reasoning_content` 미독출 + 인라인 `<think>` 제거).
- LLM 실비용 → **JWT USER 이상 필수 + bucket4j 비용가드(분5/일100)**. LLM 어댑터는 `adapter/out/llm` 격리(ArchUnit).
  저장·전송 전 카드/주민번호 PII 마스킹. **RAG 구현 완료**(pgvector 지식베이스 `/api/ai/knowledge` +
  임베딩 검색 폴백, ADR 0034 — `app.ai.rag.enabled`, 기본 off). 로드맵: Function Calling.

### 3.10 common-data-service — 공공데이터 범용 커넥터 (port 8098, lemuel_commondata)

- `/api/common-data/sources` — 등록된 데이터소스·수집 레코드 공개 조회.
- `/admin/commondata` — 임의 OpenAPI 를 코드 변경 없이 "데이터소스"로 등록(endpoint·defaultParams·keyFields) →
  표준 봉투 파싱 → `data_records (source, record_key)` UNIQUE upsert(멱등). `DATA_GO_KR_API_KEY` 공용.
- **SSRF 가드**: 등록 endpoint 가 내부/사설/루프백/링크로컬(메타데이터 169.254.169.254 포함)이면 거절.

### 3.11 investment-service — CEO 투자하기 (port 8100, lemuel_investment)

- `/api/investment` — 투자점수 조회, 초보 투자 체크, 종목 추천(`GET /recommendations`), 투자주문 신청/집행/취소/조회, 재원 조회.
- **투자점수** `InvestmentScorePolicy`: 수익성35 + 안정성35 + 성장성30 = 0~~100, AAA~~CCC, ≥60 투자적격.
- **투자주문 상태머신**: REQUESTED → APPROVED → EXECUTED / REJECTED·CANCELED.
- 재원 = settlement `confirmed` 이벤트 프로젝션(`seller_funding_view`)의 확정 정산금 − 집행 투자금(부족·부적격 422).
- **소유권 강제**: sellerId 는 JWT 주체에서 파생, 집행/취소는 주문 소유권 대조(403). 집행 시 `lemuel.investment.executed` 발행.

### 3.12 account-service — 계정계 GL (port 8102, lemuel_account)

- `/api/account` (**ADMIN/MANAGER**) — owner 잔액·분개, 대출/투자/정산 집계, 시산표(trial-balance).
  수신(banking) BC 3종: `/api/banking/time-deposits`(정기예금)·`/api/banking/savings`(적금)·`/api/banking/pensions`(퇴직연금).
  내부/운영: `/internal/account`, `/admin/backfill`(정산예정 청산 백필).
- **토픽 27종** 소비(settlement 7 · loan 6 · point 6 · giftcard 4 · seller_recovery 2 · payout 1 · investment 1),
  컨슈머 클래스 19개(point 6토픽·giftcard 4토픽은 각각 한 컨슈머가 분기 처리) → 전사 복식부기
  GL(`account_entries`, 전표당 차1·대1).
- 계정 **20종**(`GlAccount`): CASH·LOAN_RECEIVABLE·CORPORATE_LOAN_RECEIVABLE·SECURED_LOAN_RECEIVABLE·
  INVESTMENT_ASSET·SELLER_PAYABLE·HOLDBACK_PAYABLE·SELLER_RECOVERY_RECEIVABLE·SETTLEMENT_SCHEDULED·
  WITHHOLDING_PAYABLE·TIME_DEPOSIT_LIABILITY·INSTALLMENT_SAVINGS_LIABILITY·RETIREMENT_PENSION_LIABILITY·
  INTEREST_EXPENSE + **내부 결제수단 6종**(POINT_LIABILITY·POINT_PROMOTION_EXPENSE·POINT_BREAKAGE_INCOME·
  GIFT_CARD_LIABILITY·GIFT_CARD_PROMOTION_EXPENSE·GIFT_CARD_BREAKAGE_INCOME).
- **내부 결제수단 분개**(포인트·기프트카드가 같은 모양): 충전/등록 시 부채 계상(현금 충전은 DR CASH,
  판촉 지급은 DR 판촉비), 사용은 `DR 부채 / CR CASH` 로 **정산이 가정한 현금 유입을 상계**하고,
  환불 복원은 그 대칭, 소멸은 `DR 부채 / CR 낙전수익(BREAKAGE_INCOME)`, 적립 회수는 판촉비 환입.
  포인트 부채와 상품권 부채를 **한 계정에 뭉치지 않는 것**이 계정을 6종으로 나눈 이유다(분리 표시 요구).
- 멱등 2단(processed_events + `(source_topic,ref_type,ref_id)` UNIQUE). **발행 없음(소비 전용)**.
- 미결(ADR 0026): 셀러 payout 현금 유출 GL 인식 + 시산표 실검증(회계 결정 대기).

### 3.13 organization-service — 조직·멤버십 (port 8104, 자체 DB lemuel_organization)

셀러/기업 단위 조직과 그 구성원(멤버십)을 관리한다. `externalRef` 로 sellerId 또는 stockCode 를 느슨히 참조(비검증).

| 도메인 | API (base `/api/organizations`, **JWT 인증 필수**)                                                                                  | 기능                                                                                         |
| ------ | ----------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| 조직   | `POST /`, `GET /{orgId}`                                                                                                            | 조직 생성(생성자 자동 OWNER 멤버십) / 조회                                                   |
| 멤버십 | `POST /{orgId}/members`, `POST /{orgId}/members/accept`, `PATCH /{orgId}/members/{userId}/role`, `DELETE /{orgId}/members/{userId}` | 초대(OWNER/MANAGER) · 수락 · 역할 변경(OWNER 전용) · 제거(OWNER 전용, **마지막 OWNER 보호**) |

- **타입/역할**: `OrganizationType`=SELLER·CORPORATE, `OrgRole`=OWNER>MANAGER>STAFF. 인가는 요청 파라미터가 아니라
  JWT 주체(userId)의 조직 내 역할로 판정(IDOR 방지, `OrgAuthorizer`).
- **상태머신**: Organization ACTIVE⇄SUSPENDED. Membership INVITED→ACTIVE⇄SUSPENDED, 각 상태→REMOVED(터미널).
- **이벤트 발행**(Outbox, `aggregateType="Organization"`): `lemuel.organization.created`, `lemuel.organization.member_joined`,
  `lemuel.organization.member_role_changed`, `lemuel.organization.member_removed`.
  4종 전부 **card-service 가 소비**(조직·멤버 프로젝션, 컨슈머 그룹 `lemuel-card`).
  shared-common 의존(JWT·Outbox·멱등컨슈머).

### 3.14 card-service — 법인카드 (port 8106, 자체 DB lemuel_card)

셀러 조직에 **마스터 한도**를 부여하고, 그 한도 안에서 임직원별 **서브한도** 카드를 발급한다.
**Phase 1**(발급·한도·상태·프로젝션)과 **Phase 2**(실시간 승인·매입·명세서·지출관리 SaaS) 모두 완료(2026-08-04).

| 도메인                   | API (카드계정·카드는 base `/api/cards`·**JWT 필수** — Phase 2 는 `/van/v1/**`·`/internal/api/v1/**` 별도 표면)                                | 기능                                                                |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| 카드계정                 | `POST /accounts`, `GET /accounts/{id}`                                                                                                        | 개설(심사 후 마스터한도 산정) / 조회 — 재산정은 배치 전용(수동 API 없음) |
| 카드                     | `POST /accounts/{id}/cards`, `GET /cards/me`, `PATCH /cards/{cardId}/limit`, `PATCH /cards/{cardId}/status`                                   | 발급(서브한도 검증) / 내 카드 조회 / 서브한도 변경 / 정지·재개·해지 |
| 승인 (Phase 2)           | VAN: `POST /van/v1/authorizations` / 내부: `POST /internal/api/v1/cards/{cardId}/authorizations`                                              | 실시간 승인 — 가용한도 검증 + 홀드 생성(authorizationId 멱등)       |
| 매입·취소·환불 (Phase 2) | `POST /van/v1/captures`, `POST /van/v1/voids`, `POST /van/v1/refunds`                                                                         | 매입 확정 / 홀드 취소 / 환불                                        |
| 명세서·상환 (Phase 2)    | `POST /internal/api/v1/statements/{id}/payments`                                                                                              | 명세서 납부(`paymentId` 멱등)                                       |
| 지출관리 (Phase 2)       | `POST /internal/api/v1/expense-reports/{reportId}/{submit,approve,reject}`, `GET /internal/api/v1/organizations/{orgId}/departments/{deptId}/budget-utilization` | 지출 워크플로(제출/승인/반려) / 부서 예산 소진율 조회               |
| 운영 콘솔 (ADR 0036)     | `GET/POST /admin/expense-receipts/**` (gateway 라우팅)                                                                                        | NEEDS_REVIEW 영수증 리뷰 큐 — card 최초의 admin 표면                |
| 영수증 OCR (ADR 0036)    | `POST /internal/api/v1/expense-reports/{reportId}/receipts`(멀티파트), `GET .../receipts/latest`, `POST /internal/api/v1/expense-receipts/{id}/review` | 영수증 업로드→Gemini OCR→매입 자동 대사 / 최신 영수증 조회 / NEEDS_REVIEW 육안 리뷰 종결 |

- **한도 산정**: `masterLimit = floor((sellerPayable + holdbackPayable) × R × H)` — `R`=인정비율(기본 0.70,
  `app.card.limit.recognition-ratio`), `H`=평판 haircut(A·B 1.00 / C 0.85 / D 0.70 / E 0.00). 재원은 account-service
  GL 통제계정에 조회(ADR 0030 — card 는 재원을 복제하지 않는다). 최소한도(기본 300,000) 미달·E등급이면 발급 거절.
- **핵심 불변식**: `master_limit >= Σ sub_limit`. 서로 다른 애그리거트라 DB 제약으로 표현 불가 —
  `findByIdForUpdate`(PESSIMISTIC_WRITE) **후** `sumActiveSubLimits` 재계산이 유일한 방어(`CardIssuanceLimitConcurrencyIT` 가 게이트).
- **가용한도 불변식(Phase 2)**: `가용 = masterLimit − Σ활성홀드 − Σ매입`. 동시 승인 경합은 비관적 락 + `ConcurrentAuthorizationIT` 게이트.
- **상태머신**: CardAccount SCREENING→{ACTIVE,REJECTED(터미널)}, ACTIVE⇄SUSPENDED/DELINQUENT→CLOSED. Card ISSUED⇄SUSPENDED→CANCELED(터미널).
  `sumActiveSubLimits` 는 `status <> 'CANCELED'` — **정지 카드도 한도를 계속 점유**한다(복직 시 한도 충돌 방지).
- **거절사유(Phase 2)**: LIMIT_EXCEEDED / CARD_SUSPENDED(정지·연체) / MEMBER_INACTIVE / MERCHANT_POLICY_VIOLATION — 4종 확정, 추가 금지(ADR 0022).
- **재원 조회 실패**: 폴백 없음 → `CARD_FUNDING_UNAVAILABLE`(**503**). 추정으로 여신을 내주지 않는다.
- **배치**: ① 매일 03:30 KST 한도 재산정(ShedLock `card-limit-recalculation` PT30M). ② 일 1회 미매입 홀드 만료(`HoldExpiryScheduler`). ③ 매월 1일 01:00 KST 명세서 마감(`StatementBillingScheduler`). ④ 일 1회 연체 전이(`DelinquencyBatchScheduler`). 각 배치 1건 = 트랜잭션 1건(`REQUIRES_NEW`).
- **명세서 생명주기(Phase 2)**: OPEN→CLOSED→{PARTIALLY_PAID,PAID,DELINQUENT}→PAID. 전액 납부 시 DELINQUENT 계정 자동 ACTIVE 복구.
- **지출관리(Phase 2)**: 매입 Kafka 이벤트 소비 → 경비보고서 DRAFT 자동 생성(captureId 멱등). 제출→승인/반려 워크플로. 승인 경로 비결합(`AuthorizationLatencyTest` p99≤300ms).
- **영수증 3자 대사(ADR 0036)**: 영수증 업로드 → shared-common `VisionExtractionClient`(Gemini) OCR →
  매입 대조 자동 대사(총액 compareTo 정확 일치 · 거래일 KST ±1일 · 신뢰도 <0.80 은 NEEDS_REVIEW).
  `(report_id, file_hash)` 멱등(재업로드 시 OCR 재호출 없음). **승인 게이트**: 영수증이 첨부된 보고서는
  최신 영수증이 MATCHED 여야 승인(`CARD_RECEIPT_NOT_MATCHED` 422, `app.card.receipt-ocr.required=true` 면
  미첨부도 거절). 판독 실패는 무폴백 `CARD_RECEIPT_OCR_UNAVAILABLE`(503).
- **조직 연동**: `lemuel.organization.member_removed` 소비 → 이탈자 카드 자동 정지(멱등 컨슈머).
- **이벤트 발행**(Outbox, `aggregateType="Card"`, **파티션 키는 항상 cardAccountId**): `account_opened`·`issued`·
  `limit_changed`·`status_changed`·`account_status_changed`·`authorized`·`captured`·`statement_paid`.
- **알려진 한계(Phase 2)**: 카드 이용과 정산 지급이 같은 재원을 두 번 쓸 수 있다 — 실제 상계는 청구 사이클의
  몫이고 그때까지 인정비율 `R` 이 그 위험을 흡수한다. 정산 연동(account-service 이벤트 배선)은 이벤트 계약 우선(ADR 0022) 준수 후 Phase 3 착수.

### 3.15 insurance-service — GA 보험대리점 (port 8108, mgmt 8109, 자체 DB lemuel_insurance)

법인보험대리점(GA) 플랫폼 — 설계사(FC)의 상담 → 가입설계 → 청약 → 계약 → 유지·변경 → 수수료 정산을 하나로 잇는다.
설계 정본은 [`docs/plan/prd/insurance-service.md`](docs/plan/prd/insurance-service.md).

| 도메인      | API (base `/api/insurance`, **JWT 인증 필수**)                                                     | 기능                                                                          |
| ----------- | -------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| 가입설계    | `POST /proposals`, `GET /proposals/{id}`, `POST /proposals/{id}/convert`, `GET /proposals/{id}/sheet` | 견적 산출 · 조회 · 청약 전환 · 설계서 출력                                    |
| 청약        | `POST /applications`, `POST /applications/{id}/{review,approve,reject}`                            | 청약 접수 → 심사 → 승인/거절(승인 시 계약 생성)                               |
| 계약        | `POST /policies/{policyNumber}/{surrender,cancel}`, `GET /policies/{policyNumber}/payouts`         | 해지 · 철회 · 지급내역 조회                                                   |
| 상품설명서  | `GET /products/{productCode}/disclosure`, `POST /disclosures`                                      | 상품설명서 조회 · **대면 교부 증빙 등록**(완전판매 게이트 — §아래) |
| 청약서류 OCR (ADR 0036) | `POST /applications/{id}/documents`(멀티파트), `GET /applications/{id}/documents/latest`, `POST /application-documents/{id}/review` | 청약서 업로드→Gemini OCR→청약 자동 대사 / 최신 서류 조회 / NEEDS_REVIEW 육안 리뷰 종결 |

- **완전판매 게이트**: 청약 **승인(approve)** 시점에 상품설명서 교부 증빙을 검사한다 — 증빙이 없으면
  `DisclosureNotDeliveredException`. 상태 전이 **전에** 검사하므로 실패한 청약은 `UNDER_REVIEW` 로 남는다.
- **청약서류 대사 게이트(ADR 0036)**: 청약서 업로드 → shared-common `VisionExtractionClient`(Gemini) OCR →
  청약 대조 자동 대사(연 보험료·보장금액 compareTo 정확 일치 · 청약일 접수일 KST ±1일 · 신뢰도 <0.80 은
  NEEDS_REVIEW). `(application_id, file_hash)` 멱등. 승인 시 서류가 첨부돼 있으면 최신 서류가 MATCHED
  여야 통과(422, 무서류는 점진 도입 — `app.insurance.application-ocr.required=true` 면 미첨부도 거절).
  판독 실패는 무폴백 503. PII(주민번호·연락처)는 추출하지 않는다.
- **상태머신**: Application `SUBMITTED→UNDER_REVIEW→APPROVED/REJECTED` · Policy `ACTIVE→LAPSED/SURRENDERED/EXPIRED/CANCELLED`
  · Proposal `QUOTED→CONVERTED/EXPIRED` · Commission `SCHEDULED→PAID→CLAWBACK_PENDING→CLAWED_BACK/CANCELLED`.
- **방카슈랑스 확장(V6+)**: 판매채널 `SalesChannel`=FC·BANCA, 은행 채널 **25%룰**(특정 보험사 모집액 집중 한도) 모니터링,
  수수료 수취인 `recipientType` 에 BANK 추가.
- **수수료**: 초년도·차년도 요율로 회차 스케줄 생성, 조기 해지 시 환수(clawback). 라운딩은 `RoundingMode.DOWN`.
- **PII**: 피보험자·계약자 주민번호·연락처는 분리 테이블에 암호화 저장(`INSURANCE_ENC_KEY` 미설정 시 **fail-closed**).
- **소유권(IDOR)**: 해지·철회·지급내역의 FC 식별자는 요청이 아니라 JWT 주체에서만 파생(`FcIdentity`).
- **배치 7종**: 계약 만기·가입설계 만료·월말 수수료 마감·수수료 지급·환수 스윕·일반 지급·방카 집중도 감시.
- **이벤트 발행**(Outbox, 9토픽): `lemuel.insurance.policy_issued` · `.policy_status_changed` · `.commission_confirmed` ·
  `.commission_paid` · `.commission_clawback_triggered` · `.commission_monthly_closed` · `.banca_rule_violated` ·
  `.general_payout_requested` · `.general_payout_paid`. **계약 스키마 미등록 · 소비처 미배선**(발행 전용).
  인바운드 예외 1종: `lemuel.insurance.carrier_policy_status`(보험사 계약상태 통지)를 **소비**한다
  (`CarrierPolicyStatusConsumer`) — 외부 유입 토픽이라 §5 표·토픽 카탈로그(발행 모듈 기준)에는 없다.
  (`coverage-bound: lemuel.insurance.coverage_bound` 는 발행·소비·스키마가 모두 없는 설정 잔재여서
  2026-08-14 제거했다 — 기능이 생기면 프로듀서와 함께 카탈로그에 다시 등록한다. ADR 0035)

### 3.16 deposit-service — 셀러 예치금 원장 (port 8112, mgmt 8113, 자체 DB lemuel_deposit)

셀러 예치금 **잔고의 단일 진실원**. 지급·상계 재원을 `hold`(선점)로 묶고 `offset`(상계)으로 소진해,
같은 재원이 두 곳에서 쓰이는 이중사용을 구조적으로 막는다.

| 도메인    | API                                                                                                 | 기능                                                        |
| --------- | ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| 잔고 조회 | `GET /api/deposits/accounts/me` (셀러 본인) · `GET /api/deposits/accounts/{sellerId}` (ADMIN/MANAGER) | 가용(available)·선점(locked)·총액(total) 조회               |
| 운영 콘솔 | `POST /admin/deposits/accounts/{sellerId}/{credits,debits,holds,offsets}` (ADMIN)                    | 수기 입금·출금·선점·상계 — 감사 대상                        |
| 증빙 OCR (ADR 0036) | `POST /admin/deposits/accounts/{sellerId}/proofs`(멀티파트, referenceType·referenceId 쿼리), `GET .../proofs/latest`, `POST /admin/deposits/proofs/{id}/review` (ADMIN) | 수기 기표 증빙(이체확인증) 업로드→Gemini OCR / 최신 증빙 조회 / NEEDS_REVIEW 육안 리뷰 종결 |

- **IDOR 차단**: `/accounts/me` 는 **경로에 sellerId 가 없다는 것 자체가 계약** — 대상을 경로로 받는 순간 IDOR 경로가 된다.
  임의 셀러 조회는 별도 경로 + ADMIN/MANAGER 게이트.
- **계좌 부재는 정상 상태**(첫 입금 시점에 생성). 0원 계좌를 지어내 돌려주지 않는다 — 대사에서 "없음"과 "0원"은 다른 사실.
- **부족분**(`DepositOffsetShortfall`): 상계 재원이 모자라면 잔여를 부족분으로 적재한다(무음 실패 금지).
- **이벤트 발행**(Outbox, `aggregateType="Deposit"`): `lemuel.deposit.balance_changed` · `.hold_placed` ·
  `.hold_released` · `.offset_applied` · `.offset_shortfall`. **계약 스키마 미등록 · 소비처 미배선**(발행 전용 —
  §5 발행 전용 정책 적용).
- **Kafka 컨슈머 2종**(PR #229): `lemuel.settlement.confirmed` → 입금(credit, refType=`SETTLEMENT`, refId=settlementId) ·
  `lemuel.payout.completed` → 출금(debit, refType=`PAYOUT`, refId=payoutId). 멱등키를 payoutId 로 잡는 이유는
  계약상 `settlementId` 가 nullable(정산 없는 지급)이라 자연키가 못 되기 때문이다.
  지급이 나갔는데 차감할 잔고가 없으면(`InsufficientDepositException`) **삼키지 않고 전파**해 DLT 에 남긴다 —
  상계 부족분(shortfall)과 달리 설계된 결과가 아니라 원장 불일치 신호다.
  소비측 계약 테스트: `DepositConsumerParsingTest`(정본 샘플 기반).
- **hold/offset 은 여전히 콘솔 경로** — card 승인·매입은 페이로드에 sellerId 가 없어 미구독이다.
- **수기 기표 증빙 대사(ADR 0036, 지연 대사 변형)**: 수기 기표는 즉시 반영·선행 애그리거트 없음이라
  앵커를 호출자 지정 멱등 키 `(sellerId, referenceType, referenceId)` 로 잡는다 — 증빙을 먼저 첨부하고,
  값 대사(이체금액 compareTo 정확 일치 · 이체일 기표일 ±3일 — 수기 리드타임 흡수, `app.deposit.proof-ocr.date-tolerance-days`)
  는 **기표(credit/debit) 시점**에 실행된다(`DepositProofGate`). 증빙이 첨부된 참조는 MATCHED 여야 기표
  통과(`DEPOSIT_PROOF_NOT_MATCHED` 422), 무증빙은 그대로 통과(점진 도입 — `app.deposit.proof-ocr.required=true`
  면 미첨부도 거절하되, 면제 referenceType(기본 SETTLEMENT·PAYOUT)으로 Kafka 자동 기표는 계속 무영향).
  대사 실패 판정은 기표 트랜잭션과 함께 롤백되어 EXTRACTED 로 남는다(요청 값 정정 후 재시도 가능).
  판독 실패는 무폴백 503. 계좌번호는 추출하지 않는다(PII 최소화).
- **배치 2종**: 만료 hold 회수(`DepositHoldExpiryScheduler`, 매시 5분 KST, ShedLock) — 만료됐는데 재원을
  잡고 있는 hold(`ACTIVE`·`PARTIALLY_CAPTURED` 둘 다)를 닫고 `locked` 를 `available` 로 되돌린다.
  부족분 적체 지표(`ShortfallBacklogMetrics`, 5분) — `deposit.shortfall.open.{count,amount}`.
- **부족분 해소는 운영자 주도**(자동 재상계 없음 — 재상계는 잔고에서 돈을 다시 가져오는 행위라 정책):
  `GET /admin/deposits/shortfalls` · `POST .../{id}/resolve`(실제 available 차감, 모자라면 422) ·
  `POST .../{id}/write-off`(잔고 불변).

### 3.17 board-service — 메타 주도 게시판 (port 8114, mgmt 8115, 자체 DB lemuel_board)

게시판을 **코드가 아니라 데이터로** 만든다. `board_definitions` 1행 = 게시판 1개이고, 프론트의 단일
라우트 `/boards/:boardKey` 가 정의를 읽어 스킨을 바꿔 그린다 — 게시판을 늘리는 데 배포도 마이그레이션도
필요 없다. "CRUD 게시판"과 "이미지 게시판"은 별개 도메인이 아니라 같은 도메인의 두 스킨이다.
설계 근거 정본: [`docs/plan/board-service.md`](docs/plan/board-service.md).

| 도메인    | API                                                                     | 기능                                              |
| --------- | ----------------------------------------------------------------------- | ------------------------------------------------- |
| 이용      | `GET /api/boards` · `GET /api/boards/{boardKey}`                        | 활성 + 호출자가 읽을 수 있는 게시판 정의 조회     |
| 관리 콘솔 | `GET|POST /admin/boards` · `PUT|DELETE /admin/boards/{id}` · `POST /admin/boards/{id}/{activate,deactivate}` (ADMIN) | 게시판 생성 · 정책 수정 · 개폐 · 삭제 |
| 게시글    | `GET /api/boards/{key}/posts`(페이지·분류·검색) · `GET|POST /api/boards/{key}/posts` · `PUT|DELETE .../{postId}` · `POST .../{postId}/{pin,hide,restore}` | 목록(고정 먼저·최신순, 본문 미포함) · 상세(조회수 증가) · 작성 · 수정 · 삭제(상태 전이) · 운영 조작 |
| 댓글      | `GET|POST /api/boards/{key}/posts/{postId}/comments` · `DELETE /api/boards/{key}/comments/{commentId}` | 목록(삭제분은 자리표시) · 작성(답글 1단) · 삭제 |
| 첨부      | `GET|POST /api/boards/{key}/posts/{postId}/attachments`(멀티파트) · `GET /api/boards/{key}/attachments/{id}/download` · `DELETE .../attachments/{id}` | 목록 · 업로드(매직바이트 판정) · 다운로드 · 삭제 |

- **스킨 4종**: `LIST`(공지·자료실) · `GALLERY`(이미지 게시판) · `FAQ`(아코디언) · `QNA`(질문·답변).
  스킨은 정책을 강제한다 — `GALLERY` 는 첨부를, `QNA` 는 댓글을 끌 수 없다(도메인 조립 시점 차단).
- **인가는 역할 allowlist**(`read/write/comment/manage_roles`). RBAC `permissions` 코드로 판정하지 않는다 —
  그 테이블은 order DB 라 읽는 순간 DB-per-service 경계가 무너진다. **읽기가 비면 공개 게시판**(비로그인 포함),
  쓰기·댓글·운영은 비울 수 없다(익명 쓰기 미지원).
- **읽을 수 없는 게시판은 403 이 아니라 404** — 403 은 존재를 알려 줘 키 대입으로 비공개 게시판을 훑게 한다.
- **발행 0 · 소비 0**: Kafka 토픽이 없다. 권한은 역할, 작성자명은 작성 시점 스냅샷, 분류는 order 공통코드 그룹
  **코드 문자열 참조**(cross-DB FK 아님)라 어떤 외부 조회도 필요 없다.
- **메뉴 등록은 별도 조작**: `menus` 는 order-service 소유다. 관리 화면이 게시판 생성 후 기존
  `POST /admin/menus` 를 한 번 더 호출하고, 연결 상태는 두 API 응답을 화면에서 대조해 배지로 보여 준다.
  게시판 생성이 곧 전사 네비게이션 변경이 되면 테스트 게시판·오타 난 이름이 즉시 모두에게 노출된다.
- **키(`boardKey`)는 불변**(URL·메뉴 행이 가리킨다), **삭제는 닫힌 게시판만**(운영 중 삭제는 되돌릴 수 없다).
- 스캔 범위를 board 패키지로 한정해 shared-common Outbox·Audit 엔티티를 끌어오지 않는다 —
  쓰지 않는 `outbox_events` 를 만들어 두면 다음 사람이 이 서비스가 이벤트를 발행한다고 오해한다.
- **인가는 도메인이 판정한다**: `BoardPost.edit(actor, ...)` 처럼 애그리거트가 주체를 받아 소유권을 대조한다.
  컨트롤러에 두면 어댑터를 하나 더 만들 때 조용히 빠지고 그게 IDOR 이 된다. 주체는 JWT 에서만 만든다.
- **작성자 표시명은 마스킹 스냅샷**(`ad***`) — 원문 이메일을 board DB 에 저장하지 않는다(PII 확산 차단).
  소유권 대조는 `author_id` 로 하므로 인가 정확도는 그대로다.
- **삭제는 상태 전이**(글·댓글 모두). 삭제된 댓글은 원문 대신 `삭제된 댓글입니다.` 자리표시만 응답에 나간다
  — 원문은 신고·감사 대응을 위해 DB 에만 남는다. 숨김(HIDDEN, 운영자가 되돌릴 수 있음)과 삭제(작성자 의사)를 가른다.
- **가시성은 질의 조건으로 번역**한다(`PostSearchCriteria`) — 페이지를 읽고 자바에서 걸러 내면
  총건수와 페이지 크기가 어긋난다. 동적 조건은 Specification 으로 만든다(`:param IS NULL OR` JPQL 은 PG bytea 트랩).
- **HTML 본문은 저장 시점에 정화**한다(`SanitizeHtmlPort` ← jsoup `Safelist.relaxed()` 화이트리스트).
  작성·수정 두 경로가 모두 `BoardContentSanitizer` 를 지난다 — 한쪽만 막으면 수정으로 심는 우회가 남는다.
  MARKDOWN·댓글은 대상이 아니다(코드 블록의 정당한 태그까지 지워지고, 댓글은 HTML 렌더 경로가 없다).
- **첨부는 요청의 주장을 믿지 않는다**: 형식은 매직바이트로 판정하고(선언과 다르면 400), 저장 파일명은
  서버가 만든 UUID 이며, SVG·HTML·XML 은 정책이 허용해도 차단한다. 다운로드는 판정된 Content-Type +
  이미지만 inline + `nosniff` 3종 헤더로 나간다. 볼 수 없는 글의 첨부는 404 다(설계문서 §15).
- **Phase 3 범위**: 정의 CRUD + 게시글·댓글 + LIST/GALLERY 스킨 + sanitize + 첨부
  + HTML 본문 sanitize(Phase 3 에서 앞당김 — 사유는 설계문서 §13).
  첨부·GALLERY 스킨은 Phase 3. 그때까지 다른 스킨은 목록형으로 렌더한다.

### 3.18 education-service — 교육 과정 관리 (port 8116, mgmt 8117, 자체 DB lemuel_education·스키마 `education`)

플랫폼에 붙은 셀러·FC·직원에게 내려보낼 **교육 콘텐츠**를 만들고 공개한다. 교육은 "만들자마자 공개"가
아니라 초안→차시 구성→검토→공개의 단계를 거치므로, **과정의 생애를 상태로, 차시 순서를 제약으로 강제**한다.
설계 정본: [`docs/plan/prd/education-service.md`](docs/plan/prd/education-service.md).

| 도메인 | API (base `/admin/education/courses`, **JWT `hasRole('ADMIN')`**)                                | 기능                                                       |
| ------ | ------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| 과정   | `GET /`(상태·제목 검색) · `POST /` · `GET /{id}` · `PUT /{id}` · `POST /{id}/{publish,hide,close}` | 생성(항상 DRAFT) · 조회·검색 · 수정 · 상태 전이 3종        |
| 차시   | `GET`·`POST /{courseId}/lessons` · `PUT`·`DELETE /{courseId}/lessons/{lessonId}` · `POST /{courseId}/lessons/reorder` | 목록 · 생성 · 수정 · 삭제 · 재정렬                         |

- **상태머신**: `DRAFT → PUBLISHED ⇄ HIDDEN → CLOSED`. 새 과정은 `Course.draft` 단일 팩토리로만 만들어져
  **항상 DRAFT** 다. 공개는 `DRAFT`·`HIDDEN` 에서만, 숨김은 `PUBLISHED` 에서만, 종료는 `PUBLISHED`·`HIDDEN`
  에서만. **삭제는 없다** — 공개된 적 있는 과정은 지우지 않고 `CLOSED` 로 닫는다.
- **차시는 독립 식별자를 갖되 과정 애그리거트에 속한다** — 이 서비스의 핵심 설계 판단. 그래서
  `/{courseId}/lessons/{lessonId}` 경로가 주장한 소속을 서버가 `Lesson.requireBelongsTo(courseId)` 로
  대조하고, 불일치면 404 `LESSON_NOT_IN_COURSE`. 대조를 **어댑터가 아니라 도메인에** 둔 이유는 진입점이
  늘어도 규칙이 한 곳에 남게 하기 위해서다.
- **재정렬은 전수 교체**다. 요청에 그 과정의 차시가 정확히 한 번씩 담겨야 하고(`Lesson.validateReorder`
  — 개수·중복·집합 일치), 하나라도 어긋나면 400 `LESSON_ORDER_INVALID`. **부분 재정렬은 없다.**
  저장은 2단이다 — `(course_id, sequence)` UNIQUE 때문에 두 차시를 맞바꾸면 중간 상태가 겹치므로,
  먼저 전부 음수 구간(`-1..-n`)으로 밀고 그다음 목표 순서(`1..n`)를 쓴다.
- **차시 삭제만 멱등**이다(없으면 조용히 통과). 단 존재하는데 소속이 다르면 거부한다 — 지우고 나서
  "그 과정이 아니었다"를 알면 되돌릴 수 없다.
- **콘텐츠 파일을 저장하지 않는다** — `content_ref` 로 참조만 보관(영상·문서 호스팅은 이 서비스 밖).
  **수강·진도·이수는 범위 밖**(콘텐츠 관리 전용), 역할 세분화 없음(`ADMIN` 단일).
- 모든 쓰기 유스케이스가 `education_audit_logs` 를 남기고, 과정 수정은 낙관적 락(`version`).
- **이벤트**: 공개 전이일 때만 `lemuel.education.course_published`(Outbox, `aggregateType="Education"`,
  순서키 `courseId`)를 적재한다 — 수정·숨김·종료는 발행하지 않는다. **소비 0**(§5 발행 전용).
- shared-common 은 **제한 스캔**(`scanBasePackages=...education`) — 필요한 빈만 `@Import` 한다.
  소비 측 배선은 의도적으로 들이지 않는다(education 스키마에 `processed_events` 가 없다).
- 포트는 **8116/8117** 이다. 처음엔 8115 였는데 그것이 board-service 의 management 포트와 같아
  로컬에서 둘을 동시에 `bootRun` 하면 뒤에 뜨는 쪽이 바인드에 실패했다(compose 는 컨테이너 내부가
  모두 8080 이라 드러나지 않던 충돌이다). 2026-08-23 에 8116/8117 로 옮겨 해소했다.

### 3.19 gateway-service — API Gateway (port 8080)

- Spring Cloud Gateway(WebFlux). 서비스별 경로 predicate 라우팅. 위성 5종(financial·economics·market·commondata·company)은 공개 조회 API 만 라우팅(수집 트리거
  `/admin/**` 외부 미노출). organization 은 `/api/organizations/**`(JWT 필수), education 은
  `/admin/education/**`(ADMIN 전용 콘텐츠 관리라 admin 경로째 라우팅)을 라우팅.
- 자체 인증 필터 없음 — 인증·인가는 각 서비스 SecurityConfig 가 강제.
- 라우트 누락은 컴파일러도 화면 커버리지 게이트도 못 본다(서비스는 401 인데 게이트웨이는 404) —
  `gateway-route-gate.test.mjs` 가 gateway·nginx 배선을 CI 에서 강제한다.
- **폴리글랏 7종(§3.20~3.21)은 gateway 미라우팅** — 독립 포트로 직접 노출(내부/데모 용도).
  예외는 실시간 스트림 2종뿐: `market-stream-service` 의 `/api/market-stream/**`(SSE)와
  `notification-service` 의 `/api/notifications/stream`(알림 푸시 SSE, JWT 필수). 후자는 스트림 한 경로만
  올린다 — `/notifications/send`·`/demo` 는 인증 없는 내부 발송 경로다. 정본: [`docs/sse.md`](docs/sse.md).

### 3.20 Kotlin 이벤트 서비스 2종 — notification(8130) · reconciliation(8131)

Boot 3.3 · JDK 21 · 코루틴. **자체 DB 없음**(무영속 MVP) · shared-common 미의존 · gateway 미라우팅
(예외: notification 의 알림 푸시 SSE 한 경로 — 아래 표).

| 서비스                            | API / 트리거                                                                          | 기능                                                                                                                                                                                                                                                                                                                   |
| --------------------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **notification-service** (8130)   | `POST /notifications/send`, `GET /notifications/demo`, **`GET /notifications/stream`(SSE 푸시 허브 — JWT 필수, gateway `/api/notifications/stream`)** + Kafka 리스너 | 도메인 이벤트 5토픽(`settlement.confirmed`·`payment.confirmed/captured/refunded`·`investment.executed`) → 다채널(log/Slack/email) 알림. **코루틴 I/O 팬아웃 + 채널별 타임아웃(3s)/재시도(3회) 격리**, eventId 멱등(TTL 30분). Kafka 리스너는 기본 OFF(`APP_KAFKA_ENABLED=true` 로 활성) — 브로커 없이도 기동·데모 가능 |
| **reconciliation-service** (8131) | `POST /reconciliation/run`, `GET /reconciliation/demo` + `@Scheduled`(매일 19:00 KST) | 정산 대사 — settlement·payment 소스 **코루틴 병렬 fetch** 후 대조, sealed `Discrepancy`(MISSING/EXTRA/AMOUNT/STATUS) 분류, 허용오차 1원(`tolerance-krw`). 소스 base-url 은 env 주입(기본 샘플 시뮬레이션)                                                                                                              |

### 3.21 Polyglot 서비스 5종 — Go 2 + Python 3 (정본: [`docs/plan/polyglot-services.md`](docs/plan/polyglot-services.md))

언어별 강점 배치: **Go**=동시성·저지연 엣지, **Python**=데이터/ML/퀀트. 모두 동작 MVP(핵심 로직+헬스체크+테스트+멀티스테이지 Dockerfile, non-root), Gradle 빌드와 독립.

| 서비스                       | 언어           | 포트 | 역할                                                                                  |
| ---------------------------- | -------------- | ---- | ------------------------------------------------------------------------------------- |
| `market-stream-service`      | Go             | 8110 | 실시간 시세 스트리밍 — SSE `/stream/{code}` + WS `/ws/{code}`, goroutine Hub 팬아웃   |
| `payment-webhook-service`    | Go             | 8111 | Toss 결제 웹훅 수신 — HMAC 서명검증·멱등(TTL) → Kafka `lemuel.payment.confirmed` 발행 |
| `screening-backtest-service` | Python/FastAPI | 8120 | 투자 스크리닝 규칙 백테스트 — 수익률·MDD·Sharpe·승률 (pandas/numpy)                   |
| `settlement-anomaly-service` | Python/FastAPI | 8121 | 정산/payout 이상탐지 — MAD z-score + IsolationForest 앙상블 (scikit-learn)            |
| `forecast-service`           | Python/FastAPI | 8122 | 정산액/매출 시계열 예측 — Holt-Winters + seasonal-naive (statsmodels)                 |

- 공통 규약: `GET /health`(또는 `/healthz`) → `{"status":"UP"}`, env 설정, 구조적 로깅, 기본값은 시뮬레이션/번들 샘플이라 무-외부의존 단독 실행 가능. Python 은 **3.11 필수**(pinned deps).
- CI: `.github/workflows/polyglot-ci.yml` — `changes` 잡이 **변경 서비스만** Go/Python/Kotlin 동적 매트릭스로
  테스트·이미지 푸시(서비스 단위 CI, JVM `ci.yml` 과 동일 패턴). Java `ci`/harness-guard 와 독립.

**별도 standalone — `receipt-ocr-service`(Python/FastAPI, 기본 8123, ADR 0036)**: 법인카드 영수증
필드 추출의 **자체 구현 + 그것을 숫자로 판정하는 채점 하네스**. 위 7종과 달리 **compose·gateway·
polyglot-ci 어디에도 배선돼 있지 않다**(의도된 현 상태 — PRD N6/G-1). 정본
[`docs/plan/prd/receipt-ocr-service.md`](docs/plan/prd/receipt-ocr-service.md).

- 표면은 `GET /health` · `POST /extract` 둘뿐. 추론은 **RapidOCR(ONNX, CPU) 로컬**이라 네트워크 호출·건당 비용이 0.
- **정확도가 아니라 운영 판정과 그 비용으로 채점한다** — `domain/matcher.py` 는 Java `ExpenseReceiptMatcher`
  의 이식본이고, 오답 비용의 비대칭을 판정별 가중치(1 / 3 / 10 / 25)로 반영한 **가중 오류비용**이 점수다.
- 총액 판독 실패는 부분 결과 없이 **503**(무폴백) — 못 읽은 값을 지어내지 않는다.
- 골든셋은 **합성 35건**(시나리오 5 × 촬영조건 7)이라 실물 성능 주장에는 쓰지 않는다 —
  모델 간 상대 비교와 하네스 검증 전용.
- **현재 운영 경로는 여전히 Gemini** 다. Phase 3 에서 바뀐 것은 Java `ExtractReceiptFieldsPort` 계약이
  **필드별 신뢰도**(`fieldConfidence.{amount,date}`)를 내보내도록 넓혀진 것이고, 그 포트의 유일한 구현은
  아직 `GeminiReceiptOcrAdapter` 다. 대사 판정 일치율 실측이 gemini 88.6% vs local 37~43% 라
  교체를 정당화할 숫자가 아직 없다 — 이 서비스의 논거는 정확도 우위가 아니라 신뢰도 구조·지연·외부전송 제거다.

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
SecuredLoan  : REQUESTED → APPROVED → DISBURSED → REPAID (↘ REJECTED, DISBURSED → OVERDUE → DEFAULTED → {REPAID, WRITTEN_OFF} — 직행 금지)
선정산 Loan   : REQUESTED → APPROVED → DISBURSED → REPAID (↘ REJECTED, DISBURSED → OVERDUE → {REPAID, WRITTEN_OFF})
Organization : ACTIVE ⇄ SUSPENDED
Membership   : INVITED → ACTIVE ⇄ SUSPENDED, 각 상태 → REMOVED(터미널)  (마지막 OWNER 불변식)
CardAccount  : SCREENING → ACTIVE / REJECTED(터미널), ACTIVE ⇄ SUSPENDED/DELINQUENT → CLOSED
Card         : ISSUED ⇄ SUSPENDED → CANCELED(터미널)
Statement    : OPEN → CLOSED → {PARTIALLY_PAID, PAID, DELINQUENT} → PAID
InsApplication: SUBMITTED → UNDER_REVIEW → APPROVED / REJECTED
Policy(보험) : ACTIVE → LAPSED / SURRENDERED / EXPIRED / CANCELLED
Proposal     : QUOTED → CONVERTED / EXPIRED
Commission   : SCHEDULED → PAID → CLAWBACK_PENDING → CLAWED_BACK / CANCELLED
DepositHold  : ACTIVE → PARTIALLY_CAPTURED → CAPTURED / EXPIRED / VOIDED / RELEASED
PointAccount : ACTIVE ⇄ SUSPENDED, ACTIVE|SUSPENDED → CLOSED (잔액 0 일 때만)
               ※ SUSPENDED 는 사용 불가·적립은 가능(조사 중 적립까지 막으면 정상 주문이 손해)
PointLot     : ACTIVE → EXHAUSTED(remaining=0) / → EXPIRED(소멸 배치) / → REVOKED(적립 취소)
               ※ EXPIRED·REVOKED 는 되살리지 않는다 — 되돌릴 일은 신규 로트 발급(역분개 원칙)
PointHold    : ACTIVE → CAPTURED / RELEASED / EXPIRED (종단 전이 가드가 늦게 온 쪽을 거절)
GiftCard     : ISSUED → ACTIVE → REGISTERED → {REGISTERED(부분사용), USED_UP},
               ACTIVE|REGISTERED → EXPIRED, → SUSPENDED(분실·부정)
Course(교육) : DRAFT → PUBLISHED ⇄ HIDDEN → CLOSED  (삭제 없음 — 닫을 뿐)
```

정책: 수수료·정산주기·홀드백(등급별, §3.2), 기업대출 신용정책(등급×계수 한도), 투자점수 3축,
법인카드 한도 산정(재원×인정비율×평판 haircut, §3.14), 보험 수수료 스케줄·환수(§3.15),
포인트 소비 순서(FEFO)·환불 복원 규칙(§3.1).

---

## 5. 이벤트 카탈로그 (cross-service 56개 토픽)

계약 스키마·정본 샘플: `shared-common/src/testFixtures/resources/contracts/events/` — 56개 토픽(ADR 0024).
전송 속성(파티션·보존·순서키) 정본은 별도다 — `kafka/topic-catalog.json` 등재 63건(§2.2, ADR 0035).

> 수치 검증: `ls shared-common/src/testFixtures/resources/contracts/events/*.schema.json | wc -l` → 56
> (`git ls-files` 로 세면 **미추적 신규 스키마가 빠져** 커밋 직후에 처음 어긋난다 — 2026-08-22 실측)

| 토픽                                                                                                        | 프로듀서     | 주요 컨슈머                                                                                    |
| ----------------------------------------------------------------------------------------------------------- | ------------ | ---------------------------------------------------------------------------------------------- |
| `lemuel.payment.captured` / `.refunded`                                                                     | order        | settlement(프로젝션·정산 생성/역정산) · notification                                           |
| `lemuel.order.created`                                                                                      | order        | settlement(프로젝션)                                                                           |
| `lemuel.user.registered`                                                                                    | order        | settlement(프로젝션) · company(셀러 생성)                                                      |
| `lemuel.product.changed`                                                                                    | order        | settlement(프로젝션)                                                                           |
| `lemuel.seller.tier_changed`                                                                                | order        | settlement(프로젝션 — 조회·리포트용. **정산 계산 미사용**, 결제 시점 등급이 정본, ADR 0031). `reason=BACKFILL` 은 변경이 아니라 초기 적재용 재발행 |
| `lemuel.point.charged` / `.granted` / `.used` / `.restored` / `.expired` / `.revoked`                       | order        | account(포인트 부채 GL — 충전·판촉지급·사용상계·환불복원·낙전수익·판촉비환입. 순서키 `accountId`) |
| `lemuel.giftcard.registered` / `.used` / `.restored` / `.expired`                                            | order        | account(상품권 부채 GL — 포인트와 **별도 계정**. 순서키 `giftCardId`, 파티션 1)                |
| `lemuel.settlement.created`                                                                                 | settlement   | loan · account                                                                                 |
| `lemuel.settlement.confirmed`                                                                               | settlement   | loan · investment · account · notification · deposit(입금)                                     |
| `lemuel.payout.completed`                                                                                   | settlement   | account(GL 현금 폐루프 — DR SELLER_PAYABLE / CR CASH, ADR 0026 Option A) · deposit(출금)       |
| `lemuel.loan.repayment_applied`                                                                             | loan         | settlement · account                                                                           |
| `lemuel.loan.disbursement_requested`                                                                        | loan         | account                                                                                        |
| `lemuel.loan.corporate_loan_disbursed`                                                                      | loan         | account                                                                                        |
| `lemuel.investment.executed`                                                                                | investment   | account · notification                                                                         |
| `lemuel.loan.secured_loan_disbursed` / `.secured_loan_repaid` / `.secured_loan_principal_repaid`            | loan         | account                                                                                        |
| `lemuel.loan.lease_activated`                                                                               | loan         | (소비처 미배선 — 계약만 선행, account GL 소비는 후속)                                          |
| `lemuel.settlement.holdback_released` / `.holdback_consumed`                                                | settlement   | account(GL 홀드백 유보·소멸)                                                                   |
| `lemuel.settlement.adjusted` / `.canceled`                                                                  | settlement   | account(GL 조정·역정산 분개)                                                                   |
| `lemuel.settlement.withholding_accrued`                                                                     | settlement   | account(원천징수 부채 계상)                                                                    |
| `lemuel.seller_recovery.opened` / `.offset`                                                                 | settlement   | account(미수채권 개설·상계)                                                                    |
| `lemuel.company.reputation_changed`                                                                         | company      | loan(신용 리스크 프로젝션) · card(평판 프로젝션 → haircut)                                     |
| `lemuel.organization.created` / `.member_joined` / `.member_role_changed`                                   | organization | card(조직·멤버 프로젝션 — created 는 SELLER 만, 소유자 OWNER 멤버십 포함 적재)                 |
| `lemuel.organization.member_removed`                                                                        | organization | card(이탈자 카드 자동 정지)                                                                    |
| `lemuel.card.account_opened` / `.issued` / `.limit_changed` / `.status_changed` / `.account_status_changed` | card         | 소비처 미배선 — 발행 전용                                                                      |
| `lemuel.card.authorized`                                                                                    | card         | **소비처 미배선 — 발행 전용**(승인 홀드는 card 내부에서 생성된다. 계약 검증만 존재: Phase2ContractPlaceholderTest + CardEventContractTest) |
| `lemuel.card.captured`                                                                                      | card         | card 자기 소비 2그룹 — `lemuel-card-expense`(경비보고서 DRAFT 자동생성)·`lemuel-card-statement`(명세서 적재). 계약: CardEventContractTest |
| `lemuel.card.statement_paid`                                                                                | card         | **소비처 미배선 — 발행 전용**(명세서 전액 납부 통지, ADR 0022 신규 토픽·하위호환)               |

부가(계약 스키마 없음): `lemuel.ops.*`(실패 신호 `*.failed` + `stock.depleted`·`stock.reclaim_delayed`·`shipping.delayed`), `lemuel.pgreconciliation.discrepancy_approved`,
`lemuel.payment.confirmed`(payment-webhook-service(Go) 발행 → notification 소비 — 내부 계약).

발행 전용(소비처 미배선 — 의도된 상태, 소비자가 생기면 ADR 0024 절차로 계약 편입).
**이 목록은 `topic-consumer-gate.test.mjs` 가 기계로 강제한다** — 카탈로그 토픽 중 어떤 서비스도
구독하지 않는 것이 여기 없으면 CI 가 FAIL 한다(2026-08-22 신설. 그전까지 이 목록은 card 5종으로
적혀 있었고 그 뒤 늘어난 4종을 아무도 대조하지 않았다):

- `lemuel.payment.created` / `lemuel.payment.authorized` — 결제 라이프사이클 관측용(레거시)
- `lemuel.user.membership_changed` — cross-service 소비자가 생기면 편입
- `lemuel.card.*` **7종** — `account_opened`·`account_status_changed`·`limit_changed`·`issued`·
  `status_changed`(청구 사이클 3단계에서 소비 예정) + `authorized`·`statement_paid`
- `lemuel.insurance.*` 9종 — `policy_issued`·`policy_status_changed`·`commission_confirmed`·`commission_paid`·
  `commission_clawback_triggered`·`commission_monthly_closed`·`banca_rule_violated`·`general_payout_requested`·`general_payout_paid`
- `lemuel.deposit.*` 5종 — `balance_changed`·`hold_placed`·`hold_released`·`offset_applied`·`offset_shortfall`
- `lemuel.loan.lease_activated` — 리스 개시 통지
- `lemuel.education.course_published` — 소비처 미배선(과정 공개 통지). 2026-08-22 이전에는 **폴러까지
  미배선이라 Outbox 행만 쌓이고 브로커에 닿지 않았다** — 제한 스캔이라 shared-common 의
  `OutboxPublisherScheduler`(@Component)가 붙지 않았고, 컴파일·테스트·API 응답 어디에도 증상이
  나타나지 않는 종류의 공백이었다. 의존·설정·빈·`@EnableScheduling` 네 축을 세워 해소했고
  (`docs/plan/prd/education-service.md` G-1), 지금은 `outbox-poller-gate` 가 이를 전수 강제한다

insurance 9종은 **계약 스키마·정본 샘플·프로듀서 계약 테스트까지 편입됐다**(2026-08-22, ADR 0024) —
소비처만 아직 없다. 소비자가 생기면 컨슈머 계약 테스트를 붙이는 것으로 끝난다.
deposit 토픽은 아직 계약 스키마(testFixtures)에 편입되지 않았다.
역방향 예약: `lemuel.ops.order.failed` 는 operation 이 구독하지만 emit 지점 미배선(OpsSignalCategory 주석 참조).

---

## 6. 비기능 요구 (Non-functional)

- **보안**: JWT(HS256, BCrypt cost=12), CORS 화이트리스트, Bucket4j rate limit, Actuator 인증, PII 마스킹·감사로그,
  환불 동시성(Pessimistic Lock + Idempotency-Key), 내부/관리 API 키 필터(운영 fail-closed), commondata SSRF 가드.
- **관측**: Prometheus + Micrometer + Grafana + OTLP 트레이싱, 서비스별 헬스/프로브.
- **테스트**: 도메인→서비스→컨트롤러→통합 순. JaCoCo CI 게이트 **LINE 90%**, 핵심 도메인 INSTRUCTION 80%.
  settlement 통합테스트는 Testcontainers PostgreSQL.
- **배포**: Docker Compose(로컬, DB-per-service PG **18종**+ES+Redpanda+redis+pgbouncer+앱 컨테이너 **21개**(JVM 19 = 18 서비스+gateway, +market-stream+notification)+frontend+관측 6종), Kubernetes(운영, GitHub Actions→GHCR→ArgoCD+image-updater GitOps), Flyway 마이그레이션. 폴리글랏 7종은 전용 차트로 격리 배포(상세: [`ARCHITECTURE.md`](ARCHITECTURE.md) §5). `receipt-ocr-service` 는 compose 미배선(§3.21).
- **운영 필수 설정**: `JWT_SECRET`(강함), `app.security.internal-key-required=true`, 각 서비스 외부 API 키.

---

## 7. 관련 문서

- 아키텍처·컨벤션: [`CLAUDE.md`](./CLAUDE.md) · 사용자 문서: [`README.md`](./README.md)
- 아키텍처 개요(26서비스 인벤토리·패턴·스택): [`ARCHITECTURE.md`](ARCHITECTURE.md) · 폴리글랏 정본: [`docs/plan/polyglot-services.md`](docs/plan/polyglot-services.md)
- 서비스별 역산 PRD 27종: [`docs/plan/prd/`](docs/plan/prd/) · 결정화 Seed: [`docs/plan/seeds/`](docs/plan/seeds/)
- 아키텍처 결정: [`docs/adr/`](./docs/adr/) (ADR 0020 DB 분리, 0024 이벤트 계약, 0026 계정계 payout 인식(제안),
  0035 토픽 카탈로그, 0036 영수증 OCR 플랫폼, 0037 MSA 분해 근거 등)
- 도메인 규칙 스킬 **17종**(`.claude/skills/*-rules` / `*-domain-rules`): settlement · order-commerce · loan ·
  investment · account · card-service · organization · insurance · deposit · board · company-news · financial-data ·
  economics-data · market-quotes · commondata-connector · operation-signal · ai-chat.
  **education 만 전용 스킬이 없다** — 규칙 정본은 [`docs/plan/prd/education-service.md`](docs/plan/prd/education-service.md).
