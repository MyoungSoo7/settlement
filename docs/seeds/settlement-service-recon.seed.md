# Seed — settlement-service recon(일일 대사) as-is 사양

> **상태: CONFIRMED** (2026-07-19) · 정본 데이터: [`settlement-service-recon.seed.yaml`](./settlement-service-recon.seed.yaml)
> 부모: [회계 코어 루프](./settlement-service-accounting-core.seed.md) · 자매: [chargeback](./settlement-service-chargeback.seed.md) / [pgreconciliation](./settlement-service-pgreconciliation.seed.md)
> **이 Seed 로 settlement-service 체계화 1층(커버리지) 완료.**

## Goal (한 줄)

**settlement-service 일일 대사(order `/internal/recon` self-totals 비교 · 3축 불일치 검출 · 스케줄러)와
그 MSA 경계 계약의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해,
회귀 기준선 · 면접 문서 · 후속 확장의 베이스로 사용한다.**

## 아키텍처 원칙 (ADR 0020 Phase 5 — self-totals)

order 는 **자기 DB 만 읽어 자기 합계를 노출**하고, settlement 는 **자기 settlement_db 숫자와 비교**한다.
양측 모두 자기 DB 만 읽으므로 **cross-DB 연결 0** — order 스키마 변경이 settlement 를 깨지 않는다.
대사는 배치/관리 작업이라 order 일시 장애 시 해당 run 만 실패하고, 정산 핫패스는 order 무의존 유지.

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **클라이언트 회복 정책** (`OrderReconClient.java`) — connect 2s/read 5s 타임아웃 명시,
   일시 순단(타임아웃·연결불가·5xx)만 200ms 백오프 후 **1회 재시도**(총 2회), **4xx 는 즉시 실패**,
   소진 시 `OrderReconUnavailableException` 으로 번역해 해당 run 만 명시적 실패.
   인증은 `X-Internal-Api-Key` 공유 시크릿(미설정 시 개발 통과)
2. **경계 계약 8 엔드포인트** (`/internal/recon/*`): daily-totals · period-totals · daily-counts(INV-9) ·
   refunds-completed(INV-8) · refunds-completed-sum · captured-payments · payment-keys-checksum(INV-12) ·
   payment-keys. 계약 형태는 **양측 Java record ↔ JSON 매칭 — 스키마 정본 없음**. null 은 빈 값으로 정규화
3. **3축 일일 대사** (`ReconciliationReport`) — capture(총액) · refund(환불) · count(건수) 축,
   **허용 오차 0** (1원 차이도 mismatch — pgrecon 의 1원 자동보정 임계와 의도적 비대칭: 대사는 검출, PG대사는 보정).
   mismatch 시 자동 조정 없음 — ERROR 로그 + 메트릭 (사람 개입 전제)
4. **스케줄러** — cron 05:00 KST(전일 대상, Clock 주입으로 타임존 비의존), **ShedLock**
   `settlement-daily-reconciliation` lockAtMostFor=PT15M 로 다중 인스턴스 중복 실행 차단
5. **재사용 주의** — `OrderReconClient` 는 integrity 스위트(INV-8/9/12)도 사용 — 정책 변경은 두 소비처 영향

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 재시도·실패 번역 정책 일치 | `OrderReconClientTest` |
| AC-2 | 3축 비교·mismatch 메트릭 분기 일치 | 대사 서비스·도메인 테스트 |
| AC-3 | order 8 엔드포인트 ↔ 클라이언트 record 일치 | 양 모듈 test (MockRestServiceServer) |
| AC-4 | LINE ≥ 90% | `:settlement-service:jacocoTestCoverageVerification` |
| AC-5 | KST 고정·ShedLock 구성 유지 | 스케줄러 단위 테스트 |

## Known Issues (발견만 기록)

- **KI-1**: `/internal/recon` REST 계약에 **스키마 정본 없음** — ADR 0024 계약-as-code 는 Kafka 만 커버,
  REST 경계 필드 드리프트는 빌드 시점 차단 없음 (클라이언트 주석에 '공유 모듈 없음' 명시).
- **KI-2 (by-design)**: mismatch 자동 조정·재대사 없음 — 감사 기능으로서 사람 개입 전제.
- **KI-3**: 허용 오차 0 — 정당한 라운딩 차이도 mismatch 집계 가능 (pgrecon 1원 임계와 비대칭).
