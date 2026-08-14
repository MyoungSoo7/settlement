# Lemuel 기능명세서 (Functional Specification)

이커머스 + 정산(Settlement) MSA 플랫폼의 전체 기능 명세. 코어 JVM 16개 마이크로서비스 + API Gateway 에
폴리글랏 7종(Kotlin 2 · Go 2 · Python 3)을 더한 **총 24개 서비스** 헥사고날 백엔드이며,
원래 단일 모놀리스였으나 Bounded Context 로 분리했다.
아키텍처·컨벤션은 [`CLAUDE.md`](./CLAUDE.md), 아키텍처 결정은 [`docs/adr/`](./docs/adr/) 참조.

- 문서 상태: 현행 코드 기준 요약 명세 (엔드포인트 표면 + 도메인 규칙 + 이벤트 흐름)
- 최종 갱신: 2026-08-09

---

## 1. 개요

| 항목           | 내용                                                                                                                                                                                                |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 도메인         | 주문·결제·정산·선정산/기업대출·투자·계정계·재무제표·경제지표·기업뉴스평판·운영관제·주식시세·AI챗봇·공공데이터·조직/멤버십·법인카드·보험(GA)·셀러예치금 + 알림·정산대사·실시간시세·결제웹훅·백테스트·이상탐지·시계열예측 |
| 서비스 수      | **24개** — 코어 Java 16 + API Gateway + Kotlin 2(알림·대사) + Go 2(스트리밍·웹훅) + Python 3(백테스트·이상탐지·예측)                                                                                |
| 아키텍처       | 헥사고날(Ports & Adapters), DB-per-service, 이벤트 드리븐(CQRS 프로젝션)                                                                                                                            |
| 서비스 간 연계 | **Kafka 이벤트로만** (코드·DB 직접 의존 0) + 내부 대사 API(`/internal/recon`)                                                                                                                       |

### 기술 스택

| 구분                          | 기술                                                                                                                                                       |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 언어 / 프레임워크             | **Java 25 / Spring Boot 4.0.4**(코어 16+gateway) · Kotlin 2.0 / Boot 3.3·JDK 21(이벤트 2종) · Go 1.22 `net/http`(엣지 2종) · Python 3.11 / FastAPI(ML 3종) |
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
- **이벤트 계약-as-code (ADR 0024)**: cross-service 37개 토픽의 JSON Schema + 정본 샘플이
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

| 도메인             | API(대표 경로)                                                                                                                                | 기능                                                                                |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| 회원/인증          | `/auth`, `/users`, `/memberships`                                                                                                             | 회원가입·로그인(JWT 발급)·비밀번호 재설정·멤버십 승인                               |
| 상품/카테고리/태그 | `/api/products`, `/products/{id}/variants`, `/api/categories`, `/categories`, `/admin/categories`, `/api/tags`, `/admin/products/{id}/images` | 상품·SKU(variant)·이미지·이커머스 카테고리 트리(계층·정렬·soft delete)·태그         |
| 장바구니/쿠폰      | `/users/{userId}/cart`, `/coupons`                                                                                                            | 장바구니, 쿠폰 발급/사용(등급별 권한)                                               |
| 주문               | `/orders`, `/orders/{id}/shipment`                                                                                                            | 단건/다건(SKU 자동 재고차감) 주문, Idempotency-Key 중복제출 차단, 배송 라이프사이클 |
| 결제               | `/payments`, `/payments/split`, `/api/payments/*/refunds`, `/admin/refunds`, `/admin/pg`                                                      | Toss 결제 생성·인증·캡처·환불(분할결제 포함), PG 라우팅, 환불이력, 관리자 환불승인  |
| 리뷰/게임          | `/reviews`, `/games`                                                                                                                          | 상품 리뷰, 게임(이벤트성)                                                           |
| 시스템 관리        | `/admin/menus`, `/admin/common-codes`, `/admin/rbac`                                                                                          | 메뉴 트리·순환참조 방지·배치정렬, 공통코드 그룹/항목, RBAC 역할·권한                |
| 내부/정합성        | `/internal/recon`, `/admin/settlement-projection`                                                                                             | order 자기 합계 노출(대사, X-Internal-Api-Key), 프로젝션 백필                       |

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
| 세무        | `/admin/tax`, `/admin/seller-tax-profiles` (ADMIN/MANAGER) · `/api/tax-invoices` (셀러) | 부가세 **포함과세** 산출(`floor(수수료×10/110)`), 개인 셀러 원천징수 3.3% 를 **실지급액에서 공제**, 세금계산서 발행·PDF, 셀러 업로드 스캔본 OCR 자동매칭(ADR 0029)                                   |
| 차지백      | `/admin/chargebacks` (ADMIN)                                                           | 지급 거절/분쟁 처리                                                                                                                                                                                 |
| PG 대사     | `/admin/pg-reconciliation`, `/admin/reconciliation` (ADMIN/MANAGER)                    | PG 정산파일 업로드→대사→차이 승인/거절(역정산 트리거)                                                                                                                                               |
| 회수        | `/admin/recoveries` (ADMIN/MANAGER)                                                    | 지급후 회수 채권 — holdback 으로 흡수 못한 잔액을 원금으로 개시, 후속 정산 확정 시 자동 상계, 정체 시 `MANUAL_REQUIRED` 이관                                                                         |
| 월마감      | `/admin/monthly-closing` (ADMIN)                                                       | 정보계 월마감 — 셀러 월 정산 마트 적재(완결된 과거 월만, 기간 단위 교체로 멱등). 원장 마감된 기간 재마감은 409                                                                                       |
| 정산 운영   | `/admin/settlements`(rerun·holdback-preview), `/admin/commission-rates` (ADMIN)         | 정산 배치 재실행(소급 상한 90일), 보류 해제 미리보기, 유효기간 수수료율 정책 등록·시뮬레이션(ADR 0032 — 미래 구간만)                                                                                |
| 운영        | `/admin/dlq`, `/admin/integrity`, `/admin/event-track`                                 | Kafka DLT/DLQ 관리, 정합성 자가진단 8종 콘솔, 소비 이벤트 3분류(정상·중복·격리) 추적·재처리                                                                                                         |

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
- **투자점수** `InvestmentScorePolicy`: 수익성35 + 안정성35 + 성장성30 = 0~~100, AAA~~CCC, ≥60 투자적격.
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

| 도메인                   | API (base `/api/cards`, **JWT 인증 필수**)                                                                                                    | 기능                                                                |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| 카드계정                 | `POST /accounts`, `GET /accounts/{id}`, `POST /accounts/{id}/recalculate`                                                                     | 개설(심사 후 마스터한도 산정) / 조회 / 수동 재산정(ADMIN)           |
| 카드                     | `POST /accounts/{id}/cards`, `GET /cards/me`, `PATCH /cards/{cardId}/limit`, `PATCH /cards/{cardId}/status`                                   | 발급(서브한도 검증) / 내 카드 조회 / 서브한도 변경 / 정지·재개·해지 |
| 승인 (Phase 2)           | VAN: `POST /van/api/v1/authorize` / 내부: `POST /internal/api/v1/authorize`                                                                   | 실시간 승인 — 가용한도 검증 + 홀드 생성(authorizationId 멱등)       |
| 매입·취소·환불 (Phase 2) | `POST /internal/api/v1/authorize/{id}/capture`, `DELETE /internal/api/v1/holds/{id}`, `POST /internal/api/v1/captures/{id}/refund`            | 매입 확정 / 홀드 취소 / 환불                                        |
| 명세서·상환 (Phase 2)    | `POST /internal/api/v1/statements/{id}/payments`                                                                                              | 명세서 납부(`paymentId` 멱등)                                       |
| 지출관리 (Phase 2)       | `POST /api/expenses/{id}/submit`, `POST /api/expenses/{id}/approve`, `POST /api/expenses/{id}/reject`, `GET /api/budgets/{orgId}/departments` | 지출 워크플로(제출/승인/반려) / 부서 예산 소진율 조회               |
| 영수증 OCR (ADR 0036)    | `POST /internal/api/v1/expense-reports/{reportId}/receipts`(멀티파트), `GET .../receipts/latest`, `POST /internal/api/v1/expense-receipts/{id}/review` | 영수증 업로드→Gemini OCR→매입 자동 대사 / 최신 영수증 조회 / NEEDS_REVIEW 육안 리뷰 종결 |

- **한도 산정**: `masterLimit = floor((sellerPayable + holdbackPayable) × R × H)` — `R`=인정비율(기본 0.70,
  `app.card.limit.recognition-ratio`), `H`=평판 haircut(A·B 1.00 / C 0.85 / D 0.70 / E 0.00). 재원은 account-service
  GL 통제계정에 조회(ADR 0030 — card 는 재원을 복제하지 않는다). 최소한도(기본 300,000) 미달·E등급이면 발급 거절.
- **핵심 불변식**: `master_limit >= Σ sub_limit`. 서로 다른 애그리거트라 DB 제약으로 표현 불가 —
  `findByIdForUpdate`(PESSIMISTIC_WRITE) **후** `sumActiveSubLimits` 재계산이 유일한 방어(`CardIssuanceLimitConcurrencyIT` 가 게이트).
- **가용한도 불변식(Phase 2)**: `가용 = masterLimit − Σ활성홀드 − Σ매입`. 동시 승인 경합은 비관적 락 + `ConcurrentAuthorizationIT` 게이트.
- **상태머신**: CardAccount ACTIVE⇄SUSPENDED/DELINQUENT→CLOSED. Card ISSUED⇄SUSPENDED→CANCELED(터미널).
  `sumActiveSubLimits` 는 `status <> 'CANCELED'` — **정지 카드도 한도를 계속 점유**한다(복직 시 한도 충돌 방지).
- **거절사유(Phase 2)**: LIMIT_EXCEEDED / CARD_SUSPENDED(정지·연체) / MEMBER_INACTIVE / MERCHANT_POLICY_VIOLATION — 4종 확정, 추가 금지(ADR 0022).
- **재원 조회 실패**: 폴백 없음 → `CARD_FUNDING_UNAVAILABLE`(**503**). 추정으로 여신을 내주지 않는다.
- **배치**: ① 매일 03:30 KST 한도 재산정(ShedLock `card-limit-recalculation` PT30M). ② 일 1회 미매입 홀드 만료(`HoldExpiryScheduler`). ③ 월말 명세서 마감(`CloseStatementScheduler`). ④ 일 1회 연체 전이(`DelinquencyBatchScheduler`). 각 배치 1건 = 트랜잭션 1건(`REQUIRES_NEW`).
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

### 3.17 gateway-service — API Gateway (port 8080)

- Spring Cloud Gateway(WebFlux). 서비스별 경로 predicate 라우팅. 위성 8종은 공개 조회 API 만 라우팅(수집 트리거
  `/admin/**` 외부 미노출). organization 은 `/api/organizations/**`(JWT 필수)를 라우팅.
- 자체 인증 필터 없음 — 인증·인가는 각 서비스 SecurityConfig 가 강제.
- **폴리글랏 7종(§3.18~3.19)은 gateway 미라우팅** — 독립 포트로 직접 노출(내부/데모 용도).
  예외는 실시간 스트림 2종뿐: `market-stream-service` 의 `/api/market-stream/**`(SSE)와
  `notification-service` 의 `/api/notifications/stream`(알림 푸시 SSE, JWT 필수). 후자는 스트림 한 경로만
  올린다 — `/notifications/send`·`/demo` 는 인증 없는 내부 발송 경로다. 정본: [`docs/sse.md`](docs/sse.md).

### 3.18 Kotlin 이벤트 서비스 2종 — notification(8130) · reconciliation(8131)

Boot 3.3 · JDK 21 · 코루틴. **자체 DB 없음**(무영속 MVP) · shared-common 미의존 · gateway 미라우팅
(예외: notification 의 알림 푸시 SSE 한 경로 — 아래 표).

| 서비스                            | API / 트리거                                                                          | 기능                                                                                                                                                                                                                                                                                                                   |
| --------------------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **notification-service** (8130)   | `POST /notifications/send`, `GET /notifications/demo`, **`GET /notifications/stream`(SSE 푸시 허브 — JWT 필수, gateway `/api/notifications/stream`)** + Kafka 리스너 | 도메인 이벤트 5토픽(`settlement.confirmed`·`payment.confirmed/captured/refunded`·`investment.executed`) → 다채널(log/Slack/email) 알림. **코루틴 I/O 팬아웃 + 채널별 타임아웃(3s)/재시도(3회) 격리**, eventId 멱등(TTL 30분). Kafka 리스너는 기본 OFF(`APP_KAFKA_ENABLED=true` 로 활성) — 브로커 없이도 기동·데모 가능 |
| **reconciliation-service** (8131) | `POST /reconciliation/run`, `GET /reconciliation/demo` + `@Scheduled`(매일 19:00 KST) | 정산 대사 — settlement·payment 소스 **코루틴 병렬 fetch** 후 대조, sealed `Discrepancy`(MISSING/EXTRA/AMOUNT/STATUS) 분류, 허용오차 1원(`tolerance-krw`). 소스 base-url 은 env 주입(기본 샘플 시뮬레이션)                                                                                                              |

### 3.19 Polyglot 서비스 5종 — Go 2 + Python 3 (정본: [`docs/plan/polyglot-services.md`](docs/plan/polyglot-services.md))

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
SecuredLoan  : REQUESTED → APPROVED → DISBURSED → REPAID (↘ REJECTED, DISBURSED → OVERDUE → DEFAULTED — 직행 금지)
Organization : ACTIVE ⇄ SUSPENDED
Membership   : INVITED → ACTIVE ⇄ SUSPENDED, 각 상태 → REMOVED(터미널)  (마지막 OWNER 불변식)
CardAccount  : ACTIVE ⇄ SUSPENDED/DELINQUENT → CLOSED
Card         : ISSUED ⇄ SUSPENDED → CANCELED(터미널)
Statement    : OPEN → CLOSED → {PARTIALLY_PAID, PAID, DELINQUENT} → PAID
InsApplication: SUBMITTED → UNDER_REVIEW → APPROVED / REJECTED
Policy(보험) : ACTIVE → LAPSED / SURRENDERED / EXPIRED / CANCELLED
Proposal     : QUOTED → CONVERTED / EXPIRED
Commission   : SCHEDULED → PAID → CLAWBACK_PENDING → CLAWED_BACK / CANCELLED
DepositHold  : ACTIVE → PARTIALLY_CAPTURED → CAPTURED / EXPIRED / VOIDED / RELEASED
```

정책: 수수료·정산주기·홀드백(등급별, §3.2), 기업대출 신용정책(등급×계수 한도), 투자점수 3축,
법인카드 한도 산정(재원×인정비율×평판 haircut, §3.14), 보험 수수료 스케줄·환수(§3.15).

---

## 5. 이벤트 카탈로그 (cross-service 37개 계약 토픽)

계약 스키마·정본 샘플: `shared-common/src/testFixtures/resources/contracts/events/` (37개, ADR 0024).

> 수치 검증: `git ls-files 'shared-common/src/testFixtures/resources/contracts/events/*.schema.json' | wc -l` → 37

| 토픽                                                                                                        | 프로듀서     | 주요 컨슈머                                                                                    |
| ----------------------------------------------------------------------------------------------------------- | ------------ | ---------------------------------------------------------------------------------------------- |
| `lemuel.payment.captured` / `.refunded`                                                                     | order        | settlement(프로젝션·정산 생성/역정산) · notification                                           |
| `lemuel.order.created`                                                                                      | order        | settlement(프로젝션)                                                                           |
| `lemuel.user.registered`                                                                                    | order        | settlement(프로젝션) · company(셀러 생성)                                                      |
| `lemuel.product.changed`                                                                                    | order        | settlement(프로젝션)                                                                           |
| `lemuel.seller.tier_changed`                                                                                | order        | settlement(프로젝션 — 조회·리포트용. **정산 계산 미사용**, 결제 시점 등급이 정본, ADR 0031). `reason=BACKFILL` 은 변경이 아니라 초기 적재용 재발행 |
| `lemuel.settlement.created`                                                                                 | settlement   | loan · account                                                                                 |
| `lemuel.settlement.confirmed`                                                                               | settlement   | loan · investment · account · notification · deposit(입금)                                     |
| `lemuel.payout.completed`                                                                                   | settlement   | account(GL 현금 폐루프 — DR SELLER_PAYABLE / CR CASH, ADR 0026 Option A) · deposit(출금)       |
| `lemuel.loan.repayment_applied`                                                                             | loan         | settlement · account                                                                           |
| `lemuel.loan.disbursement_requested`                                                                        | loan         | account                                                                                        |
| `lemuel.loan.corporate_loan_disbursed`                                                                      | loan         | account                                                                                        |
| `lemuel.investment.executed`                                                                                | investment   | account · notification                                                                         |
| `lemuel.loan.secured_loan_disbursed` / `.secured_loan_repaid` / `.secured_loan_principal_repaid`            | loan         | account                                                                                        |
| `lemuel.loan.lease_activated`                                                                               | loan         | (소비처 미배선 — 계약만 선행)                                                                  |
| `lemuel.loan.lease_activated`                                                                               | loan         | (미배선 — 발행 전용, account GL 소비는 후속)                                                   |
| `lemuel.settlement.holdback_released` / `.holdback_consumed`                                                | settlement   | account(GL 홀드백 유보·소멸)                                                                   |
| `lemuel.settlement.adjusted` / `.canceled`                                                                  | settlement   | account(GL 조정·역정산 분개)                                                                   |
| `lemuel.settlement.withholding_accrued`                                                                     | settlement   | account(원천징수 부채 계상)                                                                    |
| `lemuel.seller_recovery.opened` / `.offset`                                                                 | settlement   | account(미수채권 개설·상계)                                                                    |
| `lemuel.company.reputation_changed`                                                                         | company      | loan(신용 리스크 프로젝션) · card(평판 프로젝션 → haircut)                                     |
| `lemuel.organization.created` / `.member_joined` / `.member_role_changed`                                   | organization | card(조직·멤버 프로젝션 — created 는 SELLER 만, 소유자 OWNER 멤버십 포함 적재)                 |
| `lemuel.organization.member_removed`                                                                        | organization | card(이탈자 카드 자동 정지)                                                                    |
| `lemuel.card.account_opened` / `.issued` / `.limit_changed` / `.status_changed` / `.account_status_changed` | card         | 소비처 미배선 — 발행 전용                                                                      |
| `lemuel.card.authorized`                                                                                    | card         | Phase 2 완료 — 승인 홀드 생성(Phase2ContractPlaceholderTest + CardEventContractTest 계약 검증) |
| `lemuel.card.captured`                                                                                      | card         | Phase 2 완료 — 매입 확정(Phase2ContractPlaceholderTest + CardEventContractTest 계약 검증)      |
| `lemuel.card.statement_paid`                                                                                | card         | Phase 2 완료 — 명세서 전액 납부(ADR 0022 신규 토픽, 하위호환)                                  |

부가(계약 스키마 없음): `lemuel.ops.*`(실패 신호 `*.failed` + `lemuel.ops.stock.reclaim_delayed`), `lemuel.pgreconciliation.discrepancy_approved`,
`lemuel.payment.confirmed`(payment-webhook-service(Go) 발행 → notification 소비 — 내부 계약).

발행 전용(소비처 미배선 — 의도된 상태, 소비자가 생기면 ADR 0024 절차로 계약 편입):
`lemuel.payment.created` / `lemuel.payment.authorized`(결제 라이프사이클 관측용),
`lemuel.user.membership_changed`, `lemuel.card.*`(5종 — 청구 사이클 3단계에서 소비 예정),
`lemuel.insurance.*`(9종 — `policy_issued`·`policy_status_changed`·`commission_confirmed`·`commission_paid`·
`commission_clawback_triggered`·`commission_monthly_closed`·`banca_rule_violated`·`general_payout_requested`·`general_payout_paid`),
`lemuel.deposit.*`(5종 — `balance_changed`·`hold_placed`·`hold_released`·`offset_applied`·`offset_shortfall`).
insurance·deposit 토픽은 아직 계약 스키마(testFixtures)에 편입되지 않았다.
역방향 예약: `lemuel.ops.order.failed` 는 operation 이 구독하지만 emit 지점 미배선(OpsSignalCategory 주석 참조).

---

## 6. 비기능 요구 (Non-functional)

- **보안**: JWT(HS256, BCrypt cost=12), CORS 화이트리스트, Bucket4j rate limit, Actuator 인증, PII 마스킹·감사로그,
  환불 동시성(Pessimistic Lock + Idempotency-Key), 내부/관리 API 키 필터(운영 fail-closed), commondata SSRF 가드.
- **관측**: Prometheus + Micrometer + Grafana + OTLP 트레이싱, 서비스별 헬스/프로브.
- **테스트**: 도메인→서비스→컨트롤러→통합 순. JaCoCo CI 게이트 **LINE 90%**, 핵심 도메인 INSTRUCTION 80%.
  settlement 통합테스트는 Testcontainers PostgreSQL.
- **배포**: Docker Compose(로컬, DB-per-service PG 16종+ES+Redpanda+앱 컨테이너 18개(JVM 17+market-stream)), Kubernetes(운영, GitHub Actions→GHCR→ArgoCD+image-updater GitOps), Flyway 마이그레이션. 폴리글랏 7종은 전용 차트로 격리 배포(상세: [`ARCHITECTURE.md`](ARCHITECTURE.md) §5).
- **운영 필수 설정**: `JWT_SECRET`(강함), `app.security.internal-key-required=true`, 각 서비스 외부 API 키.

---

## 7. 관련 문서

- 아키텍처·컨벤션: [`CLAUDE.md`](./CLAUDE.md) · 사용자 문서: [`README.md`](./README.md)
- 아키텍처 개요(24서비스 인벤토리·패턴·스택): [`ARCHITECTURE.md`](ARCHITECTURE.md) · 폴리글랏 정본: [`docs/plan/polyglot-services.md`](docs/plan/polyglot-services.md)
- 아키텍처 결정: [`docs/adr/`](./docs/adr/) (ADR 0020 DB 분리, 0024 이벤트 계약, 0026 계정계 payout 인식(제안) 등)
- 도메인 규칙 스킬: `settlement-domain-rules`, `loan-domain-rules`, `investment-domain-rules`, `account-domain-rules`,
  `card-service-rules` (organization·insurance·deposit 는 전용 스킬 미배선 — `HARNESS.md` 커버리지 공백 참조)
