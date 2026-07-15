---
name: market-quotes-rules
description: 시세 공개조회 서비스 규칙 — 종목마스터 피드 파생 upsert, 금액 BigDecimal·수량 BigInteger, 피드값 보존, PER/PBR 절대 미계산 경계, KRX 수집+시드, GET 공개/admin 게이트. market-service 로직 작성·리뷰 시 로드.
---

# 시세 데이터 규칙 (market-service)

KRX 상장사 일별 시세·시가총액 **공개 read-only** 서비스(port 8094, lemuel_market). 타 서비스 코드·DB·이벤트 의존 0.

## 도메인

- `Stock`(record): `stockCode`(6자리, financial/company 와 공용 비즈니스 키)·isin·name·`market`. 필수: stockCode·name·market.
  **종목마스터는 선씨딩 아니라 시세 피드에서 파생 upsert** — 그날 피드 등장 종목을 upsert 해 상장/폐지 자동 반영.
- `StockQuote`(record): baseDate·close/open/high/low(**BigDecimal**, NUMERIC(15,2))·priorDayDiff·fluctuationRate,
  volume·tradeAmount·listedShares·`marketCap`(**BigInteger**, 수량·원단위 총액 정밀도 보존). 필수: stockCode·baseDate·closePrice·source.
- **머니 세이프티**: 금액=BigDecimal, 수량·총액=BigInteger. double/float 금지.
- **전일대비·등락률은 피드값 그대로 보존** — economics 처럼 이전 관측치에서 재계산하지 않는다.
- `Market`: `KOSPI`/`KOSDAQ`/`KONEX`(3값 — CLAUDE.md 요약은 2값이나 실제 KONEX 포함). 알 수 없는 값은 row skip(도메인+DB CHECK).
- `ValueSource`: `SEED`/`KRX`. KRX upsert 가 SEED 를 덮어씀. upsert 키 **`(stock_code, base_date)` UNIQUE**.

## ★ PER/PBR 미계산 경계 (핵심)

- **시세·시총만 서빙하고 밸류에이션은 절대 계산하지 않는다.** `financial` import 0건, PER/PBR 로직 0건 — 설계상 부재가 정상.
- PER(주가/EPS)·PBR(주가/BPS)은 EPS·BPS 재무원천이 필요 → **financial-service 관할**. market 은 분자(주가)와 시총만 제공.
- **밸류에이션 조인은 소비측(CEO 브리핑 프론트/invest-copilot)이 두 서비스 공개 GET 을 각각 호출해 합친다.** 응답 DTO 에 valuation 필드 전무.

## KRX 수집 + 시드

- `KrxApiClient`: 금융위 `getStockPriceInfo`. envelope resultCode `00`=OK, `03`=NODATA(빈 리스트, 휴장/미래일),
  그 외 예외. `totalCount`/pageSize 페이지네이션(하루치 전종목 ≈2800 완주). row 결측(코드/종가/시장 없음)은 skip.
- `syncQuotes(baseDate)` = **날짜 1건 = 배치 1회**(전종목). `KRX_API_KEY` 미설정 → 수집 비활성(시드로만).
  개별 종목 실패는 집계만 하고 계속. upsertStock(FK) → upsertQuote(source=KRX). 수집 후 `@CacheEvict`(catalog/snapshots/series).
- admin sync 202+백그라운드, 동시실행 409(+tracker 해소). 시드 V2 8종목(코스피7+코스닥1), **결정적 계산(`random()` 금지)**.
- ⚠ serviceKey 는 절대 URI 로 빌드(상대 URI 를 `RestClient.uri(URI)` 에 넘기면 baseUrl 경로 대체돼 500 — 코드 주석).

## 보안·경계

- 자체 최소 SecurityConfig(shared-common **미의존**, 스캔 `github.lms.lemuel.market` 한정).
- `GET /api/market/**` permitAll, `/admin/market/**` 은 `X-Internal-Api-Key`(운영 fail-closed), 나머지 denyAll.
- Kafka/Outbox 없음. 아웃바운드는 금융위 HTTP 단방향뿐. 조회 order: 존재검증(404)→기간검증(400)→조회, 카탈로그 상한 500.

## 안티패턴 (발견 시 지적)

- **PER/PBR·밸류에이션 계산** / financial import·DB조인(MSA 경계 붕괴).
- 등락률·전일대비를 이전 관측치에서 파생계산(피드값 보존 위반) / 금액 double·수량 BigDecimal 오용.
- 종목마스터 별도 선씨딩 / market 3값 외 강제 저장.
- shared-common·Kafka 결합 추가 / GET 인증 요구 / 시드 `random()` / 한 종목 실패로 하루 배치 중단.
