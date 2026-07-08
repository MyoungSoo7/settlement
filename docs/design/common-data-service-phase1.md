# common-data-service Phase 1 — 공공데이터포털 범용 커넥터

## 목적

공공데이터포털(data.go.kr)의 **임의의 OpenAPI 를 코드 변경 없이** 수집·저장·공개 조회할 수 있는
범용 커넥터 서비스. economics(ECOS)·financial(DART)·market(금융위 시세)처럼 API 하나마다
서비스를 새로 만드는 대신, 표준 data.go.kr 응답 봉투를 따르는 API 라면 **데이터소스로 등록만 하면**
수집기가 돌아간다.

- 신규 위성 MSA: port **8098**, 자체 DB **lemuel_commondata** (host 5443)
- 타 서비스와 코드·DB·이벤트 의존 0, shared-common 미의존(자체 최소 SecurityConfig)
- 인증키: `DATA_GO_KR_API_KEY` — data.go.kr 은 계정당 1개 인증키로 활용신청한 모든 API 를 호출

## 왜 범용인가 (vs 특정 데이터셋 서비스)

| 접근 | 장점 | 단점 |
|------|------|------|
| ★ 범용 커넥터 (선택) | 새 공공 데이터가 필요할 때 서비스 신설 없이 소스 등록만. "common" 이름에 부합 | 도메인 특화 스키마/검증 불가 — payload 는 JSON 원문 보존 |
| 특정 데이터셋 1개 | 강타입 도메인 모델 | market/economics 와 중복 패턴, 데이터셋마다 서비스 증식 |
| 무저장 프록시 | 가장 가벼움 | 시드 폴백 불가(키 없으면 데모 불가), 쿼터 소진 위험, 이력 조회 불가 |

## 도메인 모델

```
DataSource  — 등록된 data.go.kr API 1개
  code(소문자 슬러그, UNIQUE) · name · endpoint(전체 URL)
  defaultParams(호출 시 항상 붙는 쿼리, 예: _type=json, solYear=2026)
  keyFields(아이템 자연키 필드명 목록 — 비면 payload SHA-256 해시로 대체)
  pageSize(기본 100, 상한 1000) · enabled · description

DataRecord  — 수집된 아이템 1건
  sourceCode · recordKey(자연키 조인 or 해시) · payload(아이템 JSON 원문) · collectedAt
  UNIQUE (source, recordKey) → 재수집은 payload/collectedAt 갱신(멱등)
```

## 수집기 (DataPortalApiClient)

data.go.kr 표준 봉투를 파싱한다:

- `response.header.resultCode` — `00` 정상, `03`(NODATA) 빈 결과, 그 외 예외
- `response.body.items.item[]` (배열/단일 객체 모두) 또는 `body.items[]`
- `totalCount` + `numOfRows`/`pageNo` 페이지네이션 — totalCount 없는 API 는
  "받은 행 < pageSize" 로 종료 판정, MAX_PAGES=100 안전 상한
- XML 응답(인증키 오류·`_type` 미지정)은 힌트를 담아 예외
- JSON 응답 형식 지정 파라미터(`_type`/`resultType`/`dataType`)는 API 마다 달라
  **소스의 defaultParams 로 등록**한다(클라이언트가 임의 추가하지 않음)

## API

공개 (gateway `/api/common-data/**` 라우팅, 무인증 GET):

- `GET /api/common-data/sources` — 등록 소스 목록
- `GET /api/common-data/sources/{code}` — 소스 상세
- `GET /api/common-data/sources/{code}/records?limit=` — 최신 수집 레코드 (payload 는 JSON 객체로 반환)

운영 (`/admin/commondata/**`, X-Internal-Api-Key 게이트, gateway 미라우팅):

- `POST /admin/commondata/sources` — 소스 등록/수정 (code 기준 upsert, 부분 갱신)
- `POST /admin/commondata/sources/{code}/sync?{추가파라미터}` — 수집 트리거
  (202 + 백그라운드, SyncStatusTracker 동시 1건, 쿼리 파라미터는 defaultParams 위에 override —
  날짜 의존 API 대응)
- `GET /admin/commondata/sync/status` — 진행/결과

## 시드 폴백 (Flyway V2)

키 없이도 데모 가능하도록 예시 소스 1개 + 레코드 씨딩:

- `kasi-rest-days` — 한국천문연구원 특일정보 `getRestDeInfo`(공휴일),
  keyFields=`locdate,seq`, defaultParams=`{"solYear":"2026","_type":"json"}`
- 2026년 법정공휴일 레코드(대체공휴일 미포함 근사) — 실수집이 UNIQUE upsert 로 대체

## 검증

- 헥사고날 ArchUnit(도메인 프레임워크 무의존, application↛adapter)
- 도메인/서비스 단위 테스트 — 모듈 LINE 50% + `commondata.domain` INSTRUCTION 80% 게이트
- `./gradlew :common-data-service:check`

## 이후 로드맵 (Phase 2+, 미착수)

- 스케줄 수집(소스별 cron), 수집 이력 테이블, 레코드 필드 검색(JSONB 인덱스)
