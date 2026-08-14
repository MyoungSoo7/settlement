# financial-statements-service 설계 (2026-07-06)

## 목적

코스피(유가증권시장) 상장 약 800개 기업의 **요약 재무제표**(매출액·영업이익·당기순이익·자산/부채/자본총계)를
조회해 보여주는 신규 마이크로서비스. 기존 Lemuel MSA 의 5번째 서비스 모듈로 추가한다.

## 요구사항 (가정 포함)

사용자 부재 중 추천 기본값으로 확정한 항목:

| 항목 | 결정 | 근거 |
|------|------|------|
| 데이터 소스 | **DART OpenAPI + 시드 폴백** | 금감원 OpenDART 가 코스피 재무제표의 표준 공짜 소스. API 키(`DART_API_KEY`) 없으면 시드 데이터로 동작 |
| 범위 | 백엔드 + 게이트웨이 라우팅 + 프론트 조회 페이지 | repo 에 React 프론트가 이미 있음 |
| 데이터 깊이 | 요약 재무제표 (연간 사업보고서 기준 6개 핵심 계정) | 목록/검색 + 상세 조회에 충분, DART `fnlttSinglAcnt`(주요계정) 1콜로 수집 가능 |

## 아키텍처

기존 컨벤션(헥사고날, DB-per-service, 독립 부팅)을 그대로 따른다. 템플릿은 loan-service.

```
financial-statements-service/          (port 8086, 자체 DB lemuel_financial → host 5437)
└── github.lms.lemuel.financial
    ├── domain/                        Company, FinancialStatement(파생지표), FsDivision, StatementSource
    ├── application/
    │   ├── port/in/                   GetCompaniesUseCase, GetFinancialStatementsUseCase,
    │   │                              SyncCompaniesUseCase, SyncStatementsUseCase
    │   ├── port/out/                  Load/SaveCompanyPort, Load/SaveFinancialStatementPort, DartClientPort
    │   └── service/                   CompanyQueryService, FinancialStatementQueryService, DartSyncService
    ├── adapter/
    │   ├── in/web/                    CompanyController, FinancialStatementController,
    │   │                              FinancialSyncAdminController(+SyncStatusTracker), GlobalExceptionHandler
    │   ├── out/persistence/           JPA 엔티티/리포지토리/어댑터
    │   └── out/external/              DartApiClient (corpCode.xml zip, company.json, fnlttSinglAcnt.json)
    └── config/                        SecurityConfig(자체), AdminApiKeyFilter, AsyncConfig
```

### 의도적 컨벤션 이탈 1건 — shared-common 미의존

- 이 서비스는 **공개 read-only 데이터**만 다룬다. 회원/주문 컨텍스트와 무관하고 이벤트 발행/수신도 없다.
- shared-common 을 물면 JWT 시크릿(order 와 동일 키), Outbox 테이블, Kafka 토글이 전부 따라온다 —
  전부 이 서비스에선 죽은 무게. → **의존하지 않고** 자체 최소 SecurityConfig 를 둔다.
- 조회 API 는 permitAll(GET), 수집 트리거(`/admin/financial/**`)는 `X-Internal-Api-Key` 공유 시크릿
  필터로 게이팅(미설정 시 통과+경고 — order 의 InternalApiKeyFilter 와 동일한 로컬 개발 편의 시맨틱).

### 데이터 모델 (Flyway V1)

```
companies(stock_code PK(6), corp_code UNIQUE NULL(8, DART 키), name, market='KOSPI', updated_at)
financial_statements(id, stock_code FK, fiscal_year, fs_div(CFS/OFS), currency,
                     revenue, operating_profit, net_income,
                     total_assets, total_liabilities, total_equity,   -- NUMERIC(21), 원 단위
                     source(SEED/DART), synced_at,
                     UNIQUE(stock_code, fiscal_year, fs_div))
```

- **stock_code 를 PK** 로 삼는 이유: 시드 단계에선 DART corp_code(8자리)를 신뢰성 있게 알 수 없다.
  종목코드는 공지된 안정 키. DART 동기화가 corp_code 를 나중에 채워도 PK 충돌이 없다.
- 파생지표(영업이익률·순이익률·부채비율·자기자본비율·ROA)는 저장하지 않고 도메인에서 계산.

### DART 수집 흐름 (배치, 관리자 트리거)

1. `POST /admin/financial/sync/companies` — corpCode.xml(zip) → 상장사 필터(stock_code 존재)
   → 기업개황 `company.json` 으로 `corp_cls == 'Y'`(유가) 만 upsert → 코스피 ~800개 확보.
2. `POST /admin/financial/sync/statements?year=YYYY` — corp_code 보유 기업별
   `fnlttSinglAcnt.json`(reprt_code=11011 사업보고서) → 연결(CFS) 우선 요약계정 upsert.
- 호출 간 간격(`app.financial.sync.request-interval-ms`, 기본 150ms)으로 DART 쿼터(일 2만콜) 보호.
- 트리거는 202 반환 + 백그라운드 실행, `GET /admin/financial/sync/status` 로 진행 확인. 동시 실행 409.

### 공개 API

```
GET /api/financial/companies?keyword=&page=0&size=20      기업 목록/검색 (이름·종목코드)
GET /api/financial/companies/{stockCode}                  기업 단건
GET /api/financial/companies/{stockCode}/statements?fromYear=&toYear=   연도별 요약 재무제표+파생지표
```

게이트웨이: `Path=/api/financial/**` → `FINANCIAL_SERVICE_URI` (admin 경로는 게이트웨이 미노출).

### 시드 (Flyway V2)

코스피 대표 20개사 × 2022–2024 연결 요약 재무제표 **근사치**(source='SEED' 로 명시).
실데이터가 필요하면 DART 키 설정 후 sync 로 대체(UNIQUE upsert 로 SEED → DART 덮어씀).

### 프론트엔드

`/financials` 공개 라우트 — 기업 검색/목록(페이징) + 선택 시 연도별 재무제표 표·파생지표.
`src/api/financial.ts` + `src/pages/FinancialStatementsPage.tsx`.

### 테스트 / 품질 게이트

- 도메인 단위 테스트(파생지표 null/0 분모 방어, 검증 규칙), DartSyncService 모킹 테스트(코스피 필터,
  실패 집계), 쿼리 서비스 테스트, 컨트롤러 standalone MockMvc, ArchUnit 헥사고날 의존 방향.
- 루트 JaCoCo 게이트(LINE 50%, adapter/config 제외) 그대로 적용.

### 인프라

- docker-compose: `financial-postgres`(host 5437) + `financial-statements-service`(host 8086) + gateway env.
- Dockerfile 은 기존 `MODULE` 빌드 인자 재사용.

## 검토한 대안

1. **order-service 에 도메인 추가** — 기각: 커머스 BC 와 무관한 공개 시장데이터, DB-per-service 원칙 위배.
2. **KRX 정보데이터시스템 스크래핑** — 기각: 비공식/불안정, 인증 없는 공식 API 아님.
3. **전체 계정과목 저장(fnlttSinglAcntAll)** — 기각(YAGNI): 조회 화면 요구는 요약 6계정으로 충분, 수집량 10배.
