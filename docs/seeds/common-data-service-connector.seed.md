# Seed — common-data-service 범용 커넥터 as-is 사양

> **상태: CONFIRMED** (2026-07-19) · 정본 데이터: [`common-data-service-connector.seed.yaml`](./common-data-service-connector.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화.

## Goal (한 줄)

**common-data-service(data.go.kr 범용 커넥터 — 레지스트리·표준봉투 파싱·(source,record_key) 멱등 upsert·SSRF 차단)의
현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함 | 제외 |
|------|------|
| datasource 레지스트리 (등록 upsert·SSRF) | audit 내부 (파티션 사실만 경계 기재) |
| 수집 파이프라인 (봉투 파싱·recordKey·원문 보존) | |
| 공개 조회 (캐시·상한) · 관리 표면 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **레지스트리 패턴** — 등록만으로 코드변경 없이 수집. code 정규식·http(s) 강제·pageSize clamp, code 기준 부분갱신 upsert (`DataSource.java:8-52`).
2. **표준봉투** — resultCode 00=OK / 03=NODATA(무예외) / 그 외 예외, XML 응답 방어, 페이지 1~100 + totalCount 유무별 종료 조건 (`DataPortalApiClient.java:119-158`).
3. **recordKey** — keyFields `|` 조인, 결측/300자 초과 시 payload SHA-256 폴백(by-design) (`:177-196`).
4. **payload 원문 보존** — `toString()` 그대로, 파싱·정규화 0, TEXT 컬럼이 의도 (COMMENT 명문).
5. **멱등 upsert** — `(source_id, record_key)` UNIQUE, 개별 실패는 집계 후 계속 (`V1:31`).
6. **SSRF 차단** — localhost 계열 호스트명 + loopback/사설/링크로컬(메타데이터 169.254.x)/ULA 차단, DNS 조회는 의도적 생략 (`DataSourceAdminService.java:70-116`).
7. **수집 트리거** — 스케줄 없음, 수동 202+백그라운드, CAS 동시 1건(409), Throwable 캐치로 영구 RUNNING 방지.
8. **캐시 정합** — Caffeine 600s + 수집·등록 후 전체 Evict (TTL 만 믿지 않음).

## 이벤트 계약

**없음 — 위성 서비스.** Kafka 의존이 빌드에 아예 없음. 소비측은 공개 GET HTTP pull.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | DataSource 불변식·부분갱신 upsert 일치 | `DataSourceTest`·`DataSourceAdminServiceTest` |
| AC-2 | 봉투 파싱·페이지네이션·키 폴백 일치 | `DataPortalApiClientTest` |
| AC-3 | SSRF 차단 목록 위반 0 | `DataSourceAdminServiceTest` (SSRF 케이스) |
| AC-4 | 헥사고날 위반 0 | `HexagonalArchitectureTest` |
| AC-5 | LINE ≥ 90% · domain INSTRUCTION ≥ 80% | `:common-data-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록)

- **KI-1 ★DRIFT**: 스킬의 "시드 V2 kasi-rest-days 무키 데모"는 소멸(시드 마이그레이션 제거됨) — 키 없으면 신규 배포 데이터 0건. 스킬 갱신 필요.
- **KI-2**: 키 미설정 sync 가 409 로 매핑 (의미상 503/422 가 자연스러움).
- **KI-3**: 수집 전량 메모리 적재 (스트리밍 아님).
- **KI-4**: 인메모리 트래커 단일 인스턴스 전제·DNS rebinding 미차단 — javadoc 명문 트레이드오프.
- **KI-5**: 키 마스킹 유틸 부재 — 단 노출 경로 자체가 0.
