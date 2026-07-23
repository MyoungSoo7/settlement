---
name: commondata-connector-rules
description: 공공데이터 범용 커넥터 규칙 — 데이터소스 등록으로 코드변경 없이 수집, 표준봉투 파싱·payload 원문 보존, (source,record_key) 멱등 upsert, SSRF 차단, GET 공개/admin 게이트. common-data-service 로직 작성·리뷰 시 로드.
---

# 공공데이터 커넥터 규칙 (common-data-service)

data.go.kr **범용 커넥터**(port 8098, lemuel_commondata). economics/market 이 API 마다 서비스를 신설한 것과 달리,
표준 봉투를 따르는 API 면 **코드 변경 없이 "데이터소스" 등록만으로** 수집·저장·공개조회. shared-common 미의존.

## 데이터소스 모델 (`DataSource`)

- 필드: code·name·endpoint·`defaultParams`·`keyFields`·pageSize·enabled. 불변식:
  - `code` 정규식 `^[a-z0-9][a-z0-9-]{1,49}$`(소문자·숫자·하이픈 2~50), upsert 키.
  - `endpoint` 는 `http(s)` 만. `defaultParams`/`keyFields` 불변복사. pageSize 는 1~`MAX_PAGE_SIZE=1000` clamp(기본 100).
- **`defaultParams`**: 호출 시 항상 붙는 쿼리 — `_type`/`resultType`(JSON 형식 지정)이 API 마다 달라 **소스별 선언이 계약**(클라이언트가 임의로 안 붙임).
- **`keyFields`**: 아이템 자연키 필드명 — 값을 `|` 로 조인해 recordKey. 비면 payload 해시로 대체.
- 등록은 `code` 기준 upsert — 기존 소스는 null 필드 보존 부분갱신. 유효성은 도메인 생성자가 강제(신규 누락 → 400).

## 표준 봉투 파싱 + 멱등 upsert + 원문 보존

- `DataPortalApiClient`: `{endpoint}?serviceKey=..&numOfRows=..&pageNo=..&{defaultParams}`. serviceKey 는 `queryParam` 으로 **한 번만 인코딩**.
  resultCode `00`=OK, `03`=NODATA(빈 리스트, 예외 아님), 그 외 예외. `body.items.item[]`(배열/단일객체/`body.items` 배열 모두 지원).
  **XML 방어**: 응답이 `<` 로 시작(인증키 오류/형식 파라미터 누락 시 XML) → 명시적 예외("_type/resultType 등록 확인").
- 페이지네이션: `pageNo=1`부터 `MAX_PAGES=100` 상한. `totalCount>0` 이면 `pageNo*pageSize≥totalCount` 종료,
  미제공 API 는 `items.size()<pageSize` 로 종료(같은 페이지 반복 API 대비).
- **recordKey**: keyFields 조인 → **결측/과대(>300)/부재면 payload SHA-256 폴백**(폴백은 멱등성 보장 수단, 버그 아님).
- **★ payload 는 아이템 JSON 원문 그대로 보존** — 도메인 특화 스키마로 파싱/변형 저장 안 함(범용성 담보).
  upsert 키 **`(source, record_key)` UNIQUE** — 재수집은 payload/collectedAt 갱신으로 흡수. 키/본문 결측 item 은 skip 집계.
- 개별 레코드 실패는 집계만 하고 계속. 수집 후 `@CacheEvict`(dataSources/dataRecords). 공개 조회는 payload 를 표준 컬렉션(Map/List)으로 복원.

## ★ SSRF 차단 (`assertNotInternalAddress`, commit cc073e763)

- 등록 시 endpoint 검증 — 범용 커넥터라 외부 host 는 허용하되 **내부망 피벗만 차단**. **DNS 조회는 안 함**(오프라인 수집 편의 — 리터럴 IP·알려진 내부명만).
- 호스트명 차단: `localhost`·`.localhost`·`.local`·`.internal`. 리터럴 IP 차단: loopback / anyLocal(0.0.0.0) /
  siteLocal(10·172.16-31·192.168) / **linkLocal(169.254.0.0/16 — AWS/GCP 메타데이터 169.254.169.254)** / IPv6 ULA(`fc00::/7`).
- 한계(의도): 공개 도메인명이 사설 IP 로 rebinding 되는 경우는 등록단계에서 못 막음(DNS 미조회 트레이드오프).

## 보안·경계

- 자체 최소 SecurityConfig(shared-common **미의존**). `GET /api/common-data/**` permitAll, `/admin/commondata/**` 은
  `X-Internal-Api-Key`(운영 fail-closed), denyAll. `DATA_GO_KR_API_KEY` 계정당 1개 공용(미설정 → 수집 비활성).
- admin: `POST /sources`(등록), `POST /sources/{code}/sync`(override 파라미터를 defaultParams 위에), `GET /sync/status`.
  수집 202+백그라운드, **동시실행 409**(+tracker 항상 해소). 샘플 시드 마이그레이션은 **제거됨**(기존 DB 는
  Flyway `*:missing` 관대 처리) — 무키 환경은 수집 비활성으로 데이터 0건, 데모하려면 `DATA_GO_KR_API_KEY` 필수.

## 안티패턴 (발견 시 지적)

- **SSRF 검증 우회**: endpoint 받는 새 등록/수정 경로는 반드시 `assertNotInternalAddress` 통과. 내부/사설/링크로컬(메타데이터)/ULA 등록 금지.
- **payload 원문 훼손**: 수집 시 파싱·정규화·필드 선별해 저장(원문 보존이 계약).
- **멱등 키 위반**: recordKey 없이/blank 저장, UNIQUE 무시 append-only.
- `_type`/`resultType` 을 클라이언트가 임의로 붙임(소스 defaultParams 로 등록할 것).
- 개별 레코드 실패로 수집 전체 중단 / 동시 sync 무시(tracker 해소 누락 시 영구 RUNNING) / 공개 GET 외 쓰기를 게이트 없이 노출.
