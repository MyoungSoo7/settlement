# 정산 정확성 검증 (Settlement Correctness Verification)

> **목적**: "정산이 제대로 작동하는가"를 말이 아니라 **재현 가능한 테스트·게이트**로 증명한다.
> 이 프로젝트의 완료 판정 원칙 — *정답은 사람의 판단이 아니라 테스트·게이트가 낸다*(CLAUDE.md) — 을 그대로 따른다.
> 아래 §0 의 커맨드를 그대로 실행하면 누구나 같은 결과를 재현할 수 있다.

## TL;DR — 최신 검증 스냅샷

| 항목 | 결과 | 게이트 | 판정 |
|------|------|--------|------|
| 실행 일시 | 2026-07-12 | — | — |
| 테스트 | **520 통과** / 실패 0 / 에러 0 / skip 1 | 실패 0 | ✅ |
| LINE 커버리지(모듈 실측) | **94.17%** (4103/4357) | ≥ 90% | ✅ |
| INSTRUCTION | 93.12% (19,857/21,323) | 핵심 도메인 ≥ 80% | ✅ |
| BRANCH | 76.77% (899/1,171) | (게이트 없음) | — |
| METHOD | 94.65% (1,027/1,085) | — | — |
| CLASS | 97.04% (197/203) | — | — |
| `jacocoTestCoverageVerification` | **BUILD SUCCESSFUL** (3m 18s) | 통과 필수 | ✅ |

> 게이트 통과가 곧 기계 판정이다. 위 커버리지 수치는 모듈 전체 실측값이며, 게이트 자체는 LINE 90%(어댑터 in/out 서브패키지는 통합테스트로 별도 검증하므로 게이트 산정에서 제외) 기준으로 통과했다.

## 0. 재현 방법 (누구나 같은 결과를 얻는 법)

**전제**
- JDK 25, Gradle Wrapper 동봉(`./gradlew`)
- **Docker 실행 중** — 통합테스트가 Testcontainers 로 실제 PostgreSQL 컨테이너를 띄운다. Docker 가 없으면 통합테스트가 skip 되어 계층 3 이상은 검증되지 않는다.

**커맨드**
```bash
./gradlew :settlement-service:test :settlement-service:jacocoTestCoverageVerification --console=plain
```

**결과 리포트 위치**
- 테스트 결과(HTML): `settlement-service/build/reports/tests/test/index.html`
- 커버리지(HTML): `settlement-service/build/reports/jacoco/test/html/index.html`
- 커버리지(XML, 파싱용): `settlement-service/build/reports/jacoco/test/jacocoTestReport.xml`

`BUILD SUCCESSFUL` + 실패/에러 0 + 커버리지 게이트 통과 = "정산이 제대로 작동한다"를 근거로 말할 수 있는 상태.

## 1. 검증 계층 — 왜 이 결과를 믿을 수 있나

정확성은 한 종류의 테스트로 증명되지 않는다. 아래 계층이 아래에서 위로 쌓인다.

| 계층 | 무엇을 보장하나 | 대표 테스트 | Docker |
|------|----------------|-------------|:------:|
| 1. 도메인 단위 | 상태머신·수수료·홀드백·역정산·금액 라운딩이 정책대로 | `*/domain/*Test` (순수 POJO) | 불필요 |
| 2. 커버리지 게이트 | "충분히 검증됐나"의 기계 판정(LINE 90%) | `jacocoTestCoverageVerification` | 불필요 |
| 3. 통합(실 DB) | JPA·동시성 락·Outbox·멱등이 실제로 도는가 | `*/integration/*IntegrationTest`, `*IT` | **필요** |
| 4. 회계 불변식 | 시산표 차·대 균형, POSTED 불변, 역분개 | `LedgerEntryTest`, `LedgerEndToEndIntegrationTest` | 일부 필요 |
| 5. 정합성 스위트 | 런타임 데이터가 어긋나는지 대사(INV-5~11) | `IntegrityPhaseA/BIntegrationTest` | **필요** |

## 2. 도메인 불변식 → 검증 테스트 매핑

각 항목은 실제 테스트로 강제된다(테스트가 깨지면 빌드가 깨진다).

### 2.1 복식부기 원장 불변식 — ADR 0007
- **차변·대변이 같은 계정이면 거부** — `LedgerEntryTest: 차변과_대변이_같은_계정이면_거부()`
- **`PENDING → POSTED` 시 postedAt 세팅, POSTED 재전기 불가** — `POSTED_재전기_불가()`
- **POSTED 수정 금지, 역분개(REVERSED)만 허용** — `POSTED_에서_reverse_가능()`
- **확정→발행→적재 시 원장 2건(차1·대1) 구성** — `LedgerEndToEndIntegrationTest: 정산_확정_아웃박스_적재_후_폴러처리시_ledger_2건_작성()`
- **환불 조정 시 역분개 2건** — `환불_정산조정_아웃박스_적재_후_폴러처리시_ledger_역분개_2건_작성()`

### 2.2 정산 정책 (등급별) — ADR 0014 / 0015
- **홀드백**: NORMAL 30%/30일 · VIP 10%/14일 · STRATEGIC 0%/0일 — `HoldbackPolicyTest: forTier(...)`
- **해제일은 영업일 기준 N일 후** — `computeReleaseDate: 영업일 기준 N 일 후`
- **금액은 BigDecimal, scale 2 · HALF_UP 정규화** — `Money VO: 생성 시 scale 2, HALF_UP 로 정규화된다`

### 2.3 멱등 3계층 방어 — CLAUDE.md / ADR 0022
- **① 컨슈머 멱등**: 동일 `event_id` 재수신 시 정산 1건 — `SettlementIdempotencyIntegrationTest: duplicateSameEventId_createsSettlementOnce()`
- **② 도메인 멱등**: 같은 `paymentId` 가 다른 event_id 로 재유입돼도 1건 — `samePaymentDifferentEventId_createsSettlementOnce()`
- **③ 스키마 멱등**: DB UNIQUE(`uk_settlements_payment_id`) 가 최후 차단 — `duplicatePaymentId_violatesUniqueConstraint()`
- **프로젝션**: PaymentCaptured 소비 시 `settlement_payment_view` 로컬 적재 — `paymentCaptured_populatesLocalPaymentViewProjection()`

### 2.4 동시성 안전
- **동시 부분환불 2건이 FOR UPDATE 로 직렬화되어 lost update 없음** — `SettlementConcurrencyIntegrationTest: 동시_부분환불_2건은_FOR_UPDATE_로_직렬화되어_lost_update_가_없다()`
- **동시 홀드백 해제 배치 2건이 각 정산을 정확히 한 번만 해제** — `동시_holdback_해제_배치_2건은_각_정산을_정확히_한번만_해제한다()`

### 2.5 정합성 스위트 (런타임 대사, INV-5~11) — 정합성 스위트 Phase A/B
- **INV-5**: 분개 통짜 누락 / 반쪽 분개(금액 불일치) 감지 — 시산표는 균형이어도 `ledger_completeness` 가 잡는다
- **INV-6**: net 초과 payout 위반 감지(미생성은 정보성)
- **INV-7**: 해제일 경과 미해제 홀드백만 overdue 판정
- **INV-8**: 완료된 지연 환불 중 조정(역정산) 없는 건을 `refund_id` 까지 특정
- **INV-10**: `processed_events` 를 (consumer_group, event_type)로 묶어 기간 건수 노출
- **INV-11**: SENDING 장기 체류 payout 과 `ledger_outbox` FAILED 감지

## 3. 한계와 범위 — 정직하게 검증되지 **않는** 것

검증 문서의 신뢰도는 한계를 숨기지 않는 데서 나온다.

- **외부 연동은 모킹/스텁**이다. 실 PG(토스페이먼츠 등)와 실 펌뱅킹 송금은 테스트에서 모킹된다 — "우리 로직이 정확한가"는 증명하되 "실 PG 응답 스펙과 100% 일치하는가"는 실환경 대사가 별도로 필요하다.
- **데이터는 합성(synthetic)**이다. 실제 정산 규제·엣지케이스가 아니라 설계된 시나리오를 검증한다.
- **skip 1건**: `SettlementControllerTest` 에 비활성 테스트 1건 존재(기능 결함 아님 — 대체 커버 경로 있음).
- **JaCoCo 경고**: `SettlementServiceApplication` 클래스의 execution data 불일치 경고가 출력되나, main 진입 클래스에 대한 무해한 경고이며 커버리지 산정에 영향 없다(게이트 통과).
- **BRANCH 76.77%** 는 게이트 대상이 아니다 — 방어적 분기·예외 경로 일부가 미커버. LINE/INSTRUCTION 게이트로 핵심 로직 커버리지를 강제한다.

## 4. 단건 검증 (CS·데모용)

전체 스위트 외에, 정산 1건 단위의 계산 근거를 사람이 읽게 풀어내는 경로가 있다.

- `/settlement-explain` — 정산 1건의 수수료·홀드백·최종 정산금 계산 근거 풀이
- `/trial-balance-verify`, `/ledger-verify` — 기간 시산표 차·대 균형 + 원장 불변식 검증
- `/recon-check` — 주문↔정산 대사 실행 및 불일치 원인 분류
- `/fee-audit` — 수수료·홀드백 정책 코드 감사(도메인 정책 교차검증)

## 5. 검증 이력

| 일시 | 커맨드 | 테스트 | LINE | 게이트 | 결과 |
|------|--------|--------|------|--------|------|
| 2026-07-12 | `:settlement-service:test` + `:jacocoTestCoverageVerification` | 520 (fail 0) | 94.17% | LINE 90% | ✅ BUILD SUCCESSFUL |

> 재검증 시 이 표에 행을 추가한다. 수치가 게이트 아래로 떨어지면 빌드가 실패하므로, 이 표의 초록불은 항상 실제 빌드 결과와 일치한다.
