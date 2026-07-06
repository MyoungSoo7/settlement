# economics-service 설계 (2026-07-06)

## 목적

한국은행 ECOS OpenAPI 에서 **거시 경제지표**(기준금리·시장금리·환율·물가)를 수집해 자체 DB 에 적재하고,
공개 read-only 조회 API 로 제공하는 신규 마이크로서비스. 기존 Lemuel MSA 의 5번째 서비스 모듈로 추가한다.
같은 날 설계된 `financial-statements-service`(기업 단위 공시 데이터)와 대칭되는 **시장 단위 데이터** 서비스로,
"외부 공공 API 수집 + 자체 DB + 공개 조회" 계열의 두 번째 사례다.

## 요구사항 (가정 포함)

사용자가 A안(독립 read-only 조회 서비스)을 승인. 세부 항목은 추천 기본값으로 확정:

| 항목 | 결정 | 근거 |
|------|------|------|
| 데이터 범위 | **기준금리, 국고채 3년 금리, USD/KRW 환율, CPI** (지표 카탈로그 방식 — 추가 쉬움) | loan(금리)·정산(환율) 도메인과 접점 있는 지표 우선 |
| 데이터 소스 | **한국은행 ECOS OpenAPI + 시드 폴백** | 위 지표 전부 커버, 무료 API 키(`ECOS_API_KEY`), 미설정 시 시드 데이터로 동작 |
| 소비 주체 | 1차: 공개 조회 API (프론트/누구나). 내부 이벤트 연계는 Phase 2 로 범위 제외 | YAGNI — 소비자 없는 이벤트를 먼저 만들지 않음 |
| 제공 방식 | REST 조회 (최신값 + 시계열). 조건 알림은 범위 제외 | 〃 |

## 아키텍처

기존 컨벤션(헥사고날, DB-per-service, 독립 부팅)을 그대로 따른다. 템플릿은 financial-statements-service.

```
economics-service/                    (port 8087, 자체 DB lemuel_economics → host 5438)
└── github.lms.lemuel.economics
    ├── domain/                       Indicator(지표 정의), IndicatorValue(관측치),
    │                                 IndicatorCycle(D/M), ValueSource(SEED/ECOS)
    ├── application/
    │   ├── port/in/                  GetIndicatorsUseCase, GetIndicatorSeriesUseCase,
    │   │                             SyncIndicatorsUseCase, SyncResult
    │   ├── port/out/                 LoadIndicatorPort, LoadIndicatorValuePort,
    │   │                             SaveIndicatorValuePort, EcosClientPort
    │   └── service/                  IndicatorQueryService, EcosSyncService
    ├── adapter/
    │   ├── in/web/                   IndicatorController, EconomicsSyncAdminController(+SyncStatusTracker),
    │   │                             GlobalExceptionHandler
    │   ├── out/persistence/          JPA 엔티티/리포지토리/어댑터
    │   └── out/external/             EcosApiClient (StatisticSearch JSON), EcosProperties
    └── config/                       SecurityConfig(자체), AdminApiKeyFilter, AsyncConfig
```

### 컨벤션 이탈 — shared-common 미의존 (financial-statements-service 와 동일 사유)

- 공개 read-only 시장 데이터만 다룬다. 회원/주문 컨텍스트와 무관, 이벤트 발행/수신 없음.
- shared-common 을 물면 JWT·Outbox·Kafka 토글이 죽은 무게로 따라옴 → **의존하지 않고** 자체 최소 SecurityConfig.
- 조회 API 는 permitAll(GET), 수집 트리거(`/admin/economics/**`)는 `X-Internal-Api-Key` 공유 시크릿 필터
  (미설정 시 통과+경고 — 기존 서비스들과 동일한 로컬 개발 편의 시맨틱).

### 데이터 모델 (Flyway V1)

```
indicators(code PK, name, unit, cycle(D/M), ecos_stat_code, ecos_item_code, updated_at)
indicator_values(id, indicator_code FK, observed_date, value NUMERIC(18,4),
                 source(SEED/ECOS), synced_at,
                 UNIQUE(indicator_code, observed_date))
```

- **지표를 하드코딩하지 않고 카탈로그(indicators) 로**: 지표 추가가 스키마 변경 없이 row 추가로 끝난다.
- 초기 카탈로그(V1 에서 시드): `BASE_RATE`(한국은행 기준금리, ECOS 722Y001, M),
  `TREASURY_3Y`(국고채 3년, 817Y002, D), `USD_KRW`(원/달러 환율, 731Y001, D), `CPI`(소비자물가지수, 901Y009, M).
- 월별(M) 지표의 observed_date 는 해당 월 1일로 정규화해 저장.
- 파생값(전기 대비 변동폭·변동률)은 저장하지 않고 도메인(`IndicatorValue`)에서 계산 — 분모 0/null 방어 포함.
- 금액/지표 값은 `BigDecimal` 매핑 (money-safety 규칙 준수).

### ECOS 수집 흐름 (배치, 관리자 트리거)

1. `POST /admin/economics/sync?code={indicatorCode}&from=YYYYMMDD&to=YYYYMMDD`
   — code 생략 시 카탈로그 전체. ECOS `StatisticSearch/{key}/json/kr/{page}/{count}/{statCode}/{cycle}/{from}/{to}/{itemCode}`
   호출 → `UNIQUE(indicator_code, observed_date)` upsert (SEED → ECOS 덮어씀).
2. 트리거는 202 반환 + 백그라운드 실행(`AsyncConfig`), `GET /admin/economics/sync/status` 로 진행 확인.
   동시 실행 409. 호출 간 간격(`app.economics.sync.request-interval-ms`, 기본 150ms)으로 ECOS 쿼터 보호.
3. ECOS 응답의 결측/휴장일은 skip (에러 아님) — 실패 지표는 SyncResult 에 집계해 상태로 노출.

### 공개 API

```
GET /api/economics/indicators                          지표 카탈로그 + 각 최신값(변동 포함)
GET /api/economics/indicators/{code}/latest            최신 관측치 (전기 대비 변동폭·변동률)
GET /api/economics/indicators/{code}/series?from=&to=  시계열 (기본 최근 1년, 날짜 오름차순)
```

- 게이트웨이: `Path=/api/economics/**` → `ECONOMICS_SERVICE_URI:http://localhost:8087`
  (admin 경로는 게이트웨이 미노출).
- Caffeine 캐시: 카탈로그+최신값(짧은 TTL), 시계열(code+기간 키).
- 존재하지 않는 지표 code → 404, 잘못된 기간(from>to) → 400 (GlobalExceptionHandler).

### 시드 (Flyway V2)

지표 4종 × 최근 24개월(월별) / 최근 60영업일(일별) **근사치**(source='SEED' 명시).
`ECOS_API_KEY` 설정 후 sync 실행 시 UNIQUE upsert 로 실데이터가 덮어쓴다.

### 프론트엔드

`/economics` 공개 라우트 — 지표 카드 그리드(최신값 + 전기 대비 변동 표시) + 지표 선택 시 시계열 차트.
`src/api/economics.ts` + `src/pages/EconomicsPage.tsx`.

### 테스트 / 품질 게이트

- 도메인 단위 테스트: 변동폭/변동률 계산(분모 0·null·단일 관측치 방어), 카탈로그 검증 규칙.
- EcosSyncService 모킹 테스트: upsert 시맨틱, 결측 skip, 실패 집계, 동시 실행 409.
- IndicatorQueryService 테스트, 컨트롤러 standalone MockMvc, ArchUnit 헥사고날 의존 방향.
- 루트 JaCoCo 게이트(LINE 50%, adapter/config 제외) 그대로 적용.

### 인프라

- `settings.gradle.kts` 에 `economics-service` 모듈 추가.
- docker-compose: `economics-postgres`(host 5438, DB lemuel_economics) + `economics-service`(host 8087)
  + gateway env(`ECONOMICS_SERVICE_URI`).
- Dockerfile 은 기존 `MODULE=economics-service` 빌드 인자 재사용.
- CLAUDE.md / README 의 서비스 목록 갱신 (5번째 마이크로서비스).

## Phase 2 (이번 범위 아님 — 설계 여지만 확보)

shared-common 의존 추가 + Outbox 로 `lemuel.economics.base-rate-changed` 발행 → loan-service 가 구독해
선정산 대출 금리 산정에 반영. 카탈로그+시계열 모델이므로 스키마 변경 없이 발행 로직만 얹으면 된다.

## 검토한 대안

1. **처음부터 내부 연계형(shared-common + Outbox + Kafka)** — 기각(YAGNI): 소비자(loan 금리 정책 변경)가
   아직 없는 이벤트를 먼저 만드는 셈이고, loan 쪽까지 스코프가 번진다. Phase 2 로 유보.
2. **financial-statements-service 에 economics 도메인 추가** — 기각: 기업 공시와 거시지표는 데이터 소스·갱신
   주기·소비자가 다른 별개 컨텍스트. 사용자 의도도 "서비스 하나 추가".
3. **수출입은행 환율 API 등 복수 소스 조합** — 기각(YAGNI): ECOS 단일 소스로 4개 지표 전부 커버 가능.
   `EcosClientPort` 뒤에 격리돼 있어 추후 소스 추가는 어댑터 추가로 끝난다.
