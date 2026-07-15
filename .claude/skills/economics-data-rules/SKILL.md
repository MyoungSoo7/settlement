---
name: economics-data-rules
description: 경제지표 공개조회 서비스 규칙 — 지표 카탈로그는 enum 아닌 DB row, D/M 주기·월1일 정규화, 파생값 계산, ECOS 수집+시드, GET 공개/admin 게이트, API키 마스킹. economics-service 로직 작성·리뷰 시 로드.
---

# 경제지표 데이터 규칙 (economics-service)

한국은행 ECOS 거시지표(기준금리·국고채3년·USD/KRW·CPI) **공개 read-only** 서비스(port 8087, lemuel_economics).
타 서비스 코드·DB·이벤트 의존 0.

## 지표 카탈로그 = DB row (enum 아님, 핵심 설계)

- `Indicator`(record): `code, name, unit, cycle, ecosStatCode, ecosItemCode`. **지표는 `indicators` 테이블 row** —
  추가/수정은 스키마·코드 변경 없이 row 로. ECOS stat/item 코드가 틀리면 **코드 고치지 말고 row 만 정정**.
- 초기 4종: `BASE_RATE`(%, D, 722Y001) · `TREASURY_3Y`(%, D, 817Y002) · `USD_KRW`(KRW, D, 731Y001) · `CPI`(2020=100, M, 901Y009).
  (BASE_RATE 은 스펙상 M 이었으나 ECOS 실제가 일별이라 D 로 정정 — "카탈로그는 데이터" 원칙 실사례.)
- `IndicatorCycle`: `D`/`M` 2값. **M 지표의 observedDate 는 반드시 해당 월 1일로 정규화**(`YearMonth.atDay(1)`). DB CHECK 강제.
- `IndicatorValue`(record, 수치 BigDecimal / NUMERIC(18,4)): 관측치. upsert 키 **`(indicator_code, observed_date)` UNIQUE**.
- **파생값(전기대비 변동)은 저장 않고 `changeFrom` 계산**(ratePercent scale 4 HALF_UP, 분모 0 이면 null).
  **서로 다른 indicatorCode 끼리 변동계산 시도 → 예외**(교차계산 원천차단).
- `ValueSource`: `SEED`/`ECOS`. ECOS upsert 가 SEED 를 덮어씀.

## ECOS 수집 + 시드

- `EcosApiClient`: `/StatisticSearch/{key}/json/kr/1/10000/{stat}/{cycle}/{start}/{end}/{item}`. `INFO-200`=데이터없음(빈 리스트),
  그 외 RESULT.CODE 예외. 행 단위 결측(빈값/`-`/파싱불가)은 그 행만 skip.
- **★ ECOS API 키는 로그·예외에 노출 금지** — 키가 URL 경로 세그먼트라 원인예외 체이닝 대신 `maskApiKey()` 만 남긴다.
- `ECOS_API_KEY` 미설정 → 수집 비활성(시드로만). 쿼터 보호 150ms sleep. 개별 지표/행 실패는 집계만 하고 계속.
- 수집 후 `@CacheEvict`(indicatorSnapshots/indicatorSeries) — TTL(600s)만 믿지 않음.
- admin sync 202+백그라운드, 동시실행 409(+tracker 항상 해소). 시드 V2 는 **결정적 계산만(`random()` 금지)**.

## 보안·경계

- 자체 최소 SecurityConfig(shared-common **미의존**, 스캔 `github.lms.lemuel.economics` 한정).
- `GET /api/economics/**` permitAll, `/admin/economics/**` 은 `X-Internal-Api-Key`(운영 fail-closed), 나머지 denyAll.
- Kafka/Outbox 없음. 아웃바운드는 ECOS HTTP 단방향뿐. (참고: AdminApiKeyFilter "미배선" TODO 주석은 STALE — 이미 배선됨.)

## 안티패턴 (발견 시 지적)

- 지표를 Java enum/하드코딩으로 추가 / ECOS 코드 오류를 클라이언트 코드 수정으로 대응(row 만 고칠 것).
- 변동·파생 지표를 DB 컬럼 저장 / 월별 지표를 월중 임의일로 저장(1일 정규화 위반).
- ECOS 실패 예외를 원인째 상위로 던져 로그에 키 유출.
- 시드에 `random()` / shared-common·Kafka·타 서비스 결합 추가 / GET 인증 요구.
