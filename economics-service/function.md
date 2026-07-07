# economics-service 기능명세서

> ECOS 경제지표 공개 조회 마이크로서비스 (port 8087, 자체 DB `lemuel_economics`)
> 한국은행 ECOS OpenAPI 로 기준금리·국고채3년·환율·CPI 를 수집하고, 무인증 공개 API 로 최신값·변동·시계열을 제공한다.
> shared-common 미의존 · 타 서비스와 코드·DB·이벤트 의존 0 (독립 read-only 서비스).

---

## 1. 서비스 개요

| 항목 | 내용 |
|------|------|
| 책임 | 거시 경제지표(공개 데이터) 수집·조회 |
| 포트 | 8087 |
| DB | `lemuel_economics` (PostgreSQL, DB-per-service) |
| 외부 연동 | 한국은행 ECOS StatisticSearch OpenAPI |
| 인증 | 조회(GET)는 무인증 공개 / 수집 트리거(`/admin/**`)는 `X-Internal-Api-Key` 게이트 |
| 캐시 | Caffeine (`maximumSize=2000`, `expireAfterWrite=600s`) |
| 아키텍처 | 헥사고날 (Ports & Adapters) |

### 관리 지표 카탈로그 (초기 4종, `V2` 시드 / `V1` INSERT)

| code | 지표명 | 단위 | 주기 | ECOS statCode / itemCode |
|------|--------|------|------|--------------------------|
| `BASE_RATE`   | 한국은행 기준금리 | % | D(일별) | 722Y001 / 0101000 |
| `TREASURY_3Y` | 국고채 3년 금리   | % | D(일별) | 817Y002 / 010200000 |
| `USD_KRW`     | 원/달러 환율      | KRW | D(일별) | 731Y001 / 0000001 |
| `CPI`         | 소비자물가지수    | 2020=100 | M(월별) | 901Y009 / 0 |

> 지표 추가 = `indicators` 테이블 row 추가로 끝난다(스키마·코드 변경 불필요). ECOS 코드가 틀리면 해당 row 만 수정.

---

## 2. 기능 목록

### 2.1 공개 조회 기능 (무인증 GET)

| # | 기능명 | Endpoint | 설명 |
|---|--------|----------|------|
| F-01 | 지표 카탈로그 조회 | `GET /api/economics/indicators` | 전체 지표 목록 + 각 지표의 최신값·전기 대비 변동 스냅샷 |
| F-02 | 단건 최신값 조회 | `GET /api/economics/indicators/{code}/latest` | 특정 지표 1건의 최신값·변동. 없는 code → 404 |
| F-03 | 지표 시계열 조회 | `GET /api/economics/indicators/{code}/series?from&to` | 기간별 관측치 시계열. from/to 생략 시 최근 1년 |

### 2.2 운영자 수집 기능 (`X-Internal-Api-Key` 게이트)

| # | 기능명 | Endpoint | 설명 |
|---|--------|----------|------|
| F-04 | ECOS 수집 트리거 | `POST /admin/economics/sync?code&from&to` | ECOS 관측치를 받아 upsert. 202 + 백그라운드 실행, 동시 실행 시 409 |
| F-05 | 수집 상태 조회 | `GET /admin/economics/sync/status` | 진행/완료/실패 상태 보드 조회 |

---

## 3. 기능 상세

### F-01 · 지표 카탈로그 조회
- **경로**: `GET /api/economics/indicators`
- **처리**: `IndicatorQueryService.getIndicators()` — 전체 지표를 순회하며 각각 최신 관측치 최대 2건으로 `latest + 전기 대비 변동`을 조립.
- **캐시**: `indicatorSnapshots` (수집 배치가 evict).
- **응답** (`IndicatorSnapshotResponse[]`):
  ```json
  [{
    "code": "BASE_RATE", "name": "한국은행 기준금리", "unit": "%", "cycle": "D",
    "latest": { "observedDate": "2026-07-01", "value": 3.5000 },
    "change": { "amount": -0.2500, "ratePercent": -6.6667 }
  }]
  ```
- **비고**: 관측치가 없으면 `latest`/`change` = null. 지표 4종 규모라 지표별 `findLatest`(N+1) 허용.

### F-02 · 단건 최신값 조회
- **경로**: `GET /api/economics/indicators/{code}/latest`
- **처리**: `getIndicator(code)` — code 로 지표 조회 후 스냅샷 조립.
- **예외**: 존재하지 않는 code → `IndicatorNotFoundException` → **404**.
- **캐시**: `indicatorSnapshots`, key=`#code`.

### F-03 · 지표 시계열 조회
- **경로**: `GET /api/economics/indicators/{code}/series?from={ISO_DATE}&to={ISO_DATE}`
- **파라미터**: `from`/`to` optional (ISO `yyyy-MM-dd`). 생략 시 `to=오늘`, `from=오늘-1년`.
- **검증 순서**: ① 존재검증(404) → ② 기간검증 `from > to` → **400** → ③ 조회.
- **캐시**: `indicatorSeries`, key=`#code:#from:#to`.
- **응답** (`SeriesResponse`):
  ```json
  {
    "code": "USD_KRW", "name": "원/달러 환율", "unit": "KRW",
    "points": [{ "observedDate": "2026-07-01", "value": 1385.5000, "source": "ECOS" }]
  }
  ```
- **트레이드오프**: from/to 생략 시 캐시 키가 `code:null:null` 로 고정 → "현재일"이 최대 TTL(600s)만큼 drift(캐시 히트 우선 의도).

### F-04 · ECOS 수집 트리거
- **경로**: `POST /admin/economics/sync?code={선택}&from={필수}&to={필수}`
- **처리**: `EcosSyncService.syncIndicators()`
  1. ECOS API 키 설정 확인 (미설정 → `IllegalStateException`).
  2. 대상 지표 해석 (`code` 없으면 전체, 있으면 단건).
  3. 지표별로 ECOS `fetchObservations` 호출 → `(indicator_code, observed_date)` UNIQUE **upsert** (SEED → ECOS 대체).
  4. 호출 간 `request-interval-ms`(기본 150ms) 대기 — ECOS 쿼터 보호.
  5. 개별 지표 실패는 집계만 하고 계속 진행 (한 지표 때문에 배치 전체 죽지 않음).
- **동시성**: `SyncStatusTracker` 가 CAS 로 단일 실행 보장. 실행 중 재요청 → **409**.
- **비동기**: 수집은 오래 걸리는 배치라 `202 Accepted` 즉시 반환 + 백그라운드 실행. `statusUrl` 안내.
- **캐시**: 완료 후 `indicatorSnapshots`/`indicatorSeries` 전체 evict (`allEntries=true`) — TTL 만 믿지 않음.
- **결과** (`SyncResult`): `scanned`(스캔), `upserted`(반영), `skipped`(0건 응답), `failed`(실패).

### F-05 · 수집 상태 조회
- **경로**: `GET /admin/economics/sync/status`
- **응답** (`SyncStatusTracker.Status`): `state`(IDLE/RUNNING/DONE/FAILED), `job`, `startedAt`, `finishedAt`, `result`, `error`.
- **비고**: 실패/에러(OOM 포함) 시에도 트래커를 반드시 해소해 영구 RUNNING(→ 이후 전부 409) 방지.

---

## 4. 도메인 모델

| 모델 | 설명 |
|------|------|
| `Indicator` | 지표 카탈로그 항목 (code, name, unit, cycle, ECOS 코드). 생성자에서 필수값 검증. |
| `IndicatorValue` | 관측치 1건. 파생값(변동)은 저장 안 하고 `changeFrom()` 으로 계산. |
| `IndicatorValue.Change` | 변동폭(amount) + 변동률%(ratePercent, scale 4 HALF_UP). 분모 0 이면 ratePercent만 null. |
| `IndicatorCycle` | D(일별 `yyyyMMdd`) / M(월별 `yyyyMM`) — ECOS cycle 파라미터와 1:1. |
| `ValueSource` | SEED(근사 샘플) / ECOS(실데이터). |
| `IndicatorNotFoundException` | 존재하지 않는 code 조회 시 → 404 매핑. |

### 도메인 규칙
- **변동 계산 안전**: 서로 다른 지표의 관측치끼리 변동 계산 시도 → 예외로 원천 차단.
- **월별 정규화**: M 주기 지표의 `observed_date` 는 해당 월 1일로 정규화 저장.
- **결측 처리**: ECOS `DATA_VALUE` 가 빈 값/`"-"` 이거나 TIME 파싱 불가 → 그 행만 skip(전체 수집 안 죽임).

---

## 5. 아키텍처 (헥사고날 포트/어댑터)

```
adapter/in/web/
├── IndicatorController              # 공개 조회 API (F-01~03)
├── EconomicsSyncAdminController     # 수집 트리거 API (F-04~05)
├── SyncStatusTracker               # 수집 단일 실행 상태 보드 (인메모리, CAS)
└── GlobalExceptionHandler          # 404/400 등 예외 → HTTP 상태 매핑

application/
├── port/in/   GetIndicatorsUseCase · GetIndicatorSeriesUseCase · SyncIndicatorsUseCase
├── port/out/  LoadIndicatorPort · LoadIndicatorValuePort · SaveIndicatorValuePort · EcosClientPort
└── service/   IndicatorQueryService(조회) · EcosSyncService(수집)

adapter/out/
├── persistence/  IndicatorRepository · IndicatorValueRepository (+ JPA 엔티티·어댑터)
└── external/     EcosApiClient (ECOS HTTP 클라이언트) · EcosProperties

config/  SecurityConfig · AdminApiKeyFilter · CacheConfig · AsyncConfig · HttpClientConfig
```

### ECOS 연동 상세 (`EcosApiClient`)
- **URL**: `{baseUrl}/StatisticSearch/{apiKey}/json/kr/1/10000/{statCode}/{cycle}/{start}/{end}/{itemCode}`
- **오류 응답**: HTTP 200 + `RESULT.CODE` — `INFO-200`(데이터 없음)은 빈 리스트, 그 외는 예외.
- **페이지네이션**: 상한 10000건 > 일별 1년치(≈250건)라 단일 콜로 충분(YAGNI).
- **미설정 폴백**: `ECOS_API_KEY` 미설정 시 수집 비활성 → Flyway 시드 데이터로만 동작.

---

## 6. 데이터 모델 (`lemuel_economics`)

### `indicators` — 지표 카탈로그
| 컬럼 | 타입 | 비고 |
|------|------|------|
| `code` | VARCHAR(30) PK | 지표 코드 |
| `name` / `unit` | VARCHAR | 지표명 / 단위 |
| `cycle` | VARCHAR(1) | D/M (CHECK 제약) |
| `ecos_stat_code` / `ecos_item_code` | VARCHAR | ECOS 통계/항목 코드 |
| `updated_at` | TIMESTAMPTZ | |

### `indicator_values` — 관측치 시계열
| 컬럼 | 타입 | 비고 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `indicator_code` | VARCHAR(30) FK→indicators | |
| `observed_date` | DATE | M 주기는 월 1일 정규화 |
| `value` | NUMERIC(18,4) | |
| `source` | VARCHAR(10) | SEED/ECOS (CHECK 제약) |
| `synced_at` | TIMESTAMPTZ | |
| **UNIQUE** | `(indicator_code, observed_date)` | upsert 멱등 키 |
| **INDEX** | `(indicator_code, observed_date DESC)` | 최신값/시계열 조회 |

---

## 7. 보안·운영

| 항목 | 설정 |
|------|------|
| 조회 API | `GET /api/economics/**` 무인증 permitAll (공개 거시 데이터) |
| 수집 API | `/admin/economics/**` → `AdminApiKeyFilter` 가 `X-Internal-Api-Key` 검증. gateway 미라우팅(외부 미노출) |
| 시크릿 미설정 | `X-Internal-Api-Key` 미설정 시 통과+경고 (로컬 개발용, 운영 필수) |
| CORS | 환경변수 화이트리스트 (기본 localhost 3000/5173/8089) |
| Actuator | health/info/metrics/prometheus 노출 (NetworkPolicy 로 내부 격리 권장) |
| 세션 | STATELESS (무상태) |
```
