# Settlement Integrity Suite — 정산 정합성 검증 확장 설계서

> settlement-copilot 의 다음 확장. 현재 대사(reconciliation)가 커버하는 범위를 불변식 단위로
> 전수 조사하고, **사각지대를 메우는 정합성 검증 스위트**를 3-Layer 플러그인 구조 위에 설계한다.
> 상태: **Phase A·B 구현 완료.**
> Phase A — `/admin/integrity/*` 4종 + MCP 도구 5종 + `/integrity-check` + `integrity-invariants`
> skill + `IntegrityPhaseAIntegrationTest`(분개 의도 누락을 시산표 대신 INV-5 가 잡는 완료 기준 통과).
> Phase B — 건수 대사(INV-9, `ReconciliationReport` 건수 축) + 재대사 윈도우(`recon_run window=N`) +
> 지연 환불 조정 대사(INV-8, `/admin/integrity/refund-adjustments`) + 이벤트 회계(INV-10,
> `/admin/integrity/processed-count` + MCP `event_accounting`) — 완료 기준(±상쇄·지연 환불 탐지)
> 은 `ReconcileDailyTotalsServiceTest`·`IntegrityPhaseBIntegrationTest` 로 통과.
> Phase C — 행 단위 프로젝션 대사(INV-12, `/admin/integrity/projection-diff` + order `/internal/recon/payment-keys[-checksum]`)
> **구현 완료**: 하이브리드(키셋 체크섬 1차 스크리닝 → 불일치 시 키 diff)로 데이터량을 방어하며 누락/고아/금액불일치
> id 를 특정한다. 완료 기준(행 1건 삭제 시 누락 id 특정)은 `IntegrityPhaseCIntegrationTest`·
> `ProjectionReconciliationServiceTest` 로 통과. INV-13(행 불변식 스캔)은 미착수(후속).

---

## 1. 현재 정합성 커버리지 맵

돈의 흐름을 파이프라인으로 놓고, 각 구간에 어떤 검증이 존재하는지 조사한 결과:

```
 order (opslab)                          settlement (settlement_db)
┌──────────────┐  outbox+Kafka  ┌──────────┐  confirm 배치   ┌─────────┐  scheduler  ┌────────┐
│ Payment      │───────────────▶│Settlement│───────────────▶│ Ledger  │             │ Payout │
│ capture/refund│               │ 생성/조정 │  LedgerOutbox  │ 분개    │             │ 지급   │
└──────────────┘                └──────────┘  Task(비동기)   └─────────┘             └────────┘
        │                            │                            │                      │
   [A] 일일 대사 ✅ ────────────────┘                            │                      │
   [B] PG 대사 ✅ (PG CSV↔order)     [C] 시산표 ⚠️ ──────────────┘        [D] ❌ 없음 ──┘
   [E] 프로젝션 게이지 ⚠️ (합계만)    [F] 멱등 3단 방어 ✅ (쓰기 시점)
```

| # | 검증 | 구현 | 커버 범위 |
|---|---|---|---|
| A | 일일 생성 대사 | `ReconciliationReport` — 캡처일 기준 양축(캡처 gross / 환불) | order↔settlement **금액 합계만** |
| B | PG 대사 | `pgreconciliation` — PG CSV↔내부, ROUNDING_DIFF 자동보정 | PG↔order |
| C | 원장 시산표 | copilot MCP `ledger_entries` 가 기간 entries 로 차/대 합계 계산 | **조회 시점에만**, 기간 내 균형만 |
| D | 정산 이후 구간 | — | **없음** (원장 완전성·지급·홀드백) |
| E | 프로젝션 상태 | `SettlementProjectionGauges` (rows/amount 게이지) | 합계 수준 |
| F | 멱등 방어 | event_id UNIQUE → processed_events PK → payment_id UNIQUE | 쓰기 시점 (사후 검증 아님) |

**핵심 관찰**: 검증이 파이프라인의 **앞쪽(order↔settlement)에 몰려 있고**, 돈이 실제로 나가는
**뒤쪽(정산→원장→지급)은 대사 사각지대**다. 그리고 존재하는 대사도 전부 **금액 합계** 기준이라
건수·행 단위 오류가 상쇄되면 통과한다.

---

## 2. 불변식 카탈로그 (INV Catalog)

정합성을 "깨지면 안 되는 불변식" 단위로 명세한다. 이 카탈로그가 스위트의 요구사항이자
skill(`integrity-invariants`) 문서의 뼈대가 된다.

| ID | 불변식 | 현재 | 깨질 때의 의미 |
|---|---|---|---|
| INV-1 | 캡처일 D의 order 캡처 gross = D 생성 정산 gross 합 | ✅ A(캡처 축) | 정산 누락/이중 생성 |
| INV-2 | 캡처분 반영 환불 = 정산 refunded 합 | ✅ A(환불 축) | 역정산 미반영 → 과지급 |
| INV-3 | PG 정산 파일 = 내부 결제 원장 | ✅ B | PG 미수/과수 |
| INV-4 | 기간 원장 차변 합 = 대변 합 | ⚠️ C | 반쪽 분개 |
| INV-5 | **확정(DONE) 정산 1건 ↔ CREATE_ENTRY 분개 존재, 조정 1건 ↔ REVERSE_ENTRY 존재** | ❌ | 원장 통짜 누락 — **시산표(INV-4)로는 절대 못 잡는다** (양변이 같이 없으면 균형은 유지됨) |
| INV-6 | payout 합 = 대상 정산 (net − holdback ± 조정) 합, 정산 1건당 payout ≤ 1 | ❌ | 과소/과다/이중 지급 |
| INV-7 | holdbackReleaseDate 경과 & 미해제 건 = 0, 누적 해제액 ≤ 누적 보류액 | ❌ | 셀러 돈 묶임 (스케줄러 정지 무감지) |
| INV-8 | COMPLETED 환불 ↔ 조정(adjustment) 존재 — **지연 환불 포함** | ⚠️ A는 당일 캡처분만 | 과거 정산 건 환불이 조정 없이 지나감 |
| INV-9 | 금액 대사와 **건수 대사** 동시 통과 | ❌ | +100/−100 상쇄 오류가 합계 대사를 통과 |
| INV-10 | outbox PUBLISHED 건수 = 컨슈머 processed 건수 + DLT 건수 | ❌ (published 건수만 노출) | 이벤트 유실 — 어느 쪽에서 새는지 특정 불가 |
| INV-11 | PROCESSING / SENDING / RUNNING / PENDING 장기 체류 건 = 0 | ❌ | stuck 정산·**stuck SENDING payout(이중지급 위험 1순위)** |
| INV-12 | order 원천 행 집합 = `settlement_*_view` 행 집합 (id 단위) | ❌ (게이지 합계만) | 프로젝션 행 누락/고아 — 어떤 건이 빠졌는지 특정 불가 |
| INV-13 | 행 단위 도메인 불변식: `net = gross − fee`, `refunded ≤ gross`, `holdback ≤ net`, 금액 ≥ 0, `commission_rate ∈ {0.0350, 0.0250, 0.0200}` | ❌ (쓰기 시점 도메인 검증만) | 버그·수동 개입으로 오염된 행 상시 탐지 불가 |

우선순위 근거: **INV-5, INV-6, INV-11** 이 최우선이다 — 돈이 실제로 나가는 구간이면서 현재 완전
무방비이고, 특히 INV-5 는 "시산표가 있으니 원장은 안전하다"는 착각을 정면으로 깨는 항목이다.
`LedgerOutboxTask` 가 비동기라 FAILED/유실 시 분개 자체가 안 생기는데, 양변이 같이 없으면
시산표는 여전히 balanced 다.

---

## 3. 설계 — 기존 3-Layer 에 얹기

원칙은 기존 플러그인과 동일: **서비스가 자기 DB 만 읽는 read-only API 를 신설**하고, MCP 는
프록시 + 기계 판정만, 에이전트는 해석과 보고만 한다. cross-DB 0 유지 (ADR 0020).

### 3.1 Layer 0 — 서비스 측 신설 read-only API (선행 조건)

**settlement-service `/admin/integrity/*`** (자기 DB 집계만 — 구현 난도 낮음):

| API | 응답 (기계 판정 포함) | 대응 불변식 |
|---|---|---|
| `GET /admin/integrity/ledger-completeness?date=` | 확정 정산 건수·합계 vs CREATE_ENTRY 건수·합계, `missingSettlementIds[]`(상한 N), 조정 vs REVERSE_ENTRY 동형 비교, ledger_outbox `{pending, failed, oldestAgeSec}`, `ok: boolean` | INV-5 |
| `GET /admin/integrity/payout-recon?date=` | 지급 대상 정산 (net−holdback±조정) 합 vs payout REQUESTED/COMPLETED 합, `settlementsWithoutPayout[]`, `duplicatePayoutSettlementIds[]`, `ok` | INV-6 |
| `GET /admin/integrity/holdback-status` | `overdueUnreleased[]`(해제일 경과 미해제), 누적 보류/해제액, 마지막 해제 배치 실행 시각, `ok` | INV-7 |
| `GET /admin/integrity/stuck?thresholdMinutes=` | 상태별 체류 초과 건 (settlement PROCESSING / payout SENDING / pg-recon RUNNING / ledger PENDING·outbox FAILED), `ok` | INV-11 |
| `GET /admin/integrity/row-invariants?date=&limit=` | INV-13 위반 행 목록 (규칙 id + settlement id + 실측값), `ok` | INV-13 |
| `GET /admin/integrity/processed-count?group=&from=&to=` | processed_events 건수 (이벤트 회계 분모) | INV-10 |

**order-service `/internal/recon/*` 확장** (기존 X-Internal-Api-Key 체계 재사용):

| API | 응답 | 대응 불변식 |
|---|---|---|
| `GET /internal/recon/daily-counts?date=` | 캡처 건수·환불 건수 (기존 daily-totals 의 건수 판) | INV-9 |
| `GET /internal/recon/payment-keys?date=&page=` | `{paymentId, amount}` 페이지 목록 — 프로젝션 행 diff 용. PII 없음(키+금액만) | INV-12 |
| `GET /internal/recon/refunds-completed?from=&to=` | 완료일 기준 환불 id+금액 목록 — 지연 환불 조정 대사용 | INV-8 |

설계 노트:
- 모든 응답에 `ok: boolean` + `reason` 을 포함한다 — 설계서 §11 "도구 응답에 기계 판정 포함,
  해석 여지 축소" 원칙의 연장. 에이전트 오진의 1차 방어선.
- **비동기 grace window**: ledger outbox 폴러 주기, 정산 confirm 배치 주기 안의 미처리분은
  정상이다. 판정 기준에 `graceMinutes`(기본: 폴러 주기 × 3)를 두고, grace 내 미완결은
  `ok=true, pendingWithinGrace=n` 으로 구분한다. 이거 없으면 오탐률이 §10 목표(10%)를 못 지킨다.
- 집계는 전부 날짜 파티션 단위 — `settlements(created_at)`, `payouts(requested_at)`,
  `ledger_entries(created_at)` 인덱스 유무를 구현 시 확인 (없으면 마이그레이션 1건 추가).

### 3.2 Layer ② — MCP 도구 추가

| 도구 | 백엔드 | 비고 |
|---|---|---|
| `integrity_check(date?)` | 위 5개 admin API 순회 + 종합 | **대표 도구** — `{checks:[{name, ok, detail}], allOk}`. 개별 도구는 드릴다운용 |
| `ledger_completeness(date)` | ledger-completeness | INV-5 |
| `payout_recon(date)` | payout-recon | INV-6 |
| `holdback_status()` | holdback-status | INV-7 |
| `stuck_states(thresholdMinutes?)` | stuck | INV-11 |
| `event_accounting(topic, from, to)` | order period-totals(published) + settlement processed-count + (Phase 후속) DLT 건수 | INV-10 — "발행−소비=유실 의심" 산식과 판정 포함 |
| `projection_diff(date, entity)` | order payment-keys ↔ settlement 뷰 키 목록을 MCP 서버가 양쪽 GET 해 **id 집합 diff** | INV-12 — 결과는 누락/고아 id 상위 N 건만 (컨텍스트 절약) |
| `recon_run` **확장** | 기존 + `counts` 축, `window=N`(D−1..D−N 재대사) | INV-9, INV-8(지연 환불 소급 탐지) |

기존 원칙 유지: GET 만 라우팅(read-only by construction), 서버 측 마스킹(신설 API 는 키+금액만
반환하므로 마스킹 대상 자체를 만들지 않는 게 우선), 금액은 십진 문자열.

### 3.3 Layer ① — Skill / 커맨드

- **skill `integrity-invariants`** (신규): §2 카탈로그 전문 + 불변식별 "깨졌을 때 원인 트리 →
  확인용 MCP 도구 → 조치 경로(조정/역분개/DLT 리플레이/projectionbackfill 만 — DB 직접 수정 금지)".
  `recon-playbook`(원인 분류)과 상호 링크 — recon 은 "어긋났다", integrity 는 "어디서 새는가".
- **커맨드 `/integrity-check [date]`** (신규): `/oncall` 이 "인프라가 아픈가"라면 이것은
  **"돈이 새는가"**. 순서: `recon_run(date, counts)` → `integrity_check(date)` → 🔴 항목만
  개별 도구로 드릴다운 → 불변식 id 기준 보고(결론 한 줄 → 양측 숫자 병기 → 증거 → 조치 제안).
- **기존 커맨드 확장**: `/ledger-verify` 에 INV-5 완전성 검사를 1단계로 추가 (시산표만 보고
  "균형 = 정상" 으로 결론 내리는 오진 차단), `/oncall` 순회에 `stuck_states` 추가.

### 3.4 Layer ③ — 가드레일 확장 (소폭)

`rules.mjs` 에 2건 추가:

| 규칙 | 감지 | 근거 |
|---|---|---|
| `event-accounting-guard` | `processed_events` / `outbox_events` 에 대한 `DELETE`·`TRUNCATE` (마이그레이션 포함) | 멱등 체크·이벤트 회계의 원천 데이터 — 지우면 INV-10 검증 불능 + 리플레이 시 중복 처리 |
| `grace-window-guard` (WARN) | 대사·정합성 코드에서 `LocalDate.now()` 직접 비교로 당일 판정 | 자정 경계 오탐의 전형적 원인 — cutoff 유틸 경유 유도 |

---

## 4. 커버리지 매트릭스 (설계 후)

| 불변식 | 현재 | Phase A | Phase B | Phase C |
|---|---|---|---|---|
| INV-1/2/3 (기존 대사) | ✅ | ✅ | ✅ | ✅ |
| INV-4 (시산표) | ⚠️ | ✅ (/ledger-verify 에 상시 편입) | | |
| **INV-5 (원장 완전성)** | ❌ | **✅** | | |
| **INV-6 (지급 대사)** | ❌ | **✅** | | |
| INV-7 (홀드백) | ❌ | ✅ | | |
| **INV-11 (stuck 상태)** | ❌ | **✅** | | |
| INV-8 (지연 환불 조정) | ⚠️ | | ✅ (재대사 윈도우) | |
| INV-9 (건수 대사) | ❌ | | ✅ | |
| INV-10 (이벤트 회계) | ❌ | | ✅ (DLT 건수는 dlt_inspect 선행 필요 — 기존 로드맵 Phase 3 잔여와 합류) | |
| INV-12 (프로젝션 행 diff) | ❌ | | | ✅ (구현 완료 — projection-diff 하이브리드) |
| INV-13 (행 불변식 스캔) | ❌ | | | ⬜ (후속) |

### 단계 정의

| 단계 | 범위 | 완료 기준 |
|---|---|---|
| **Phase A — 수직 대사** | settlement 자기 DB 만으로 가능한 admin API 4종(ledger-completeness, payout-recon, holdback-status, stuck) + MCP 4종 + `integrity_check` + `/integrity-check` + skill | 원장 분개 1건을 의도적으로 누락시킨 시나리오에서 시산표는 통과하지만 `/integrity-check` 가 INV-5 로 잡아낸다 (통합 테스트) |
| **Phase B — 건수·이벤트 회계** | order daily-counts / refunds-completed / processed-count API + `recon_run` counts·window 확장 + `event_accounting` | +N/−N 상쇄 케이스와 지연 환불(캡처 D, 환불 D+10) 케이스를 각각 탐지 |
| **Phase C — 행 단위** | payment-keys API + `projection_diff` + row-invariants 스캔 | 프로젝션 뷰에서 행 1건 삭제 시 누락 id 를 특정해 보고 |

Phase A 가 전체 가치의 절반 이상 — 신설 API 가 전부 자기 DB 집계라 구현이 얇고,
가장 위험한 사각지대(돈이 나가는 구간)를 즉시 커버한다.

---

## 5. 리스크와 완화

| 리스크 | 완화 |
|---|---|
| 비동기 파이프라인 오탐 (배치/폴러 주기 내 미완결을 불일치로 판정) | §3.1 grace window — 판정에 시간 여유를 구조적으로 내장. 오탐률 10% 이하 목표 유지 |
| 행 단위 도구(payment-keys, projection_diff)의 데이터량 | 날짜 파티션 + 페이지네이션 + diff 결과 상위 N 건만. Phase C 로 후순위화. 대안으로 id 정렬 해시 체크섬 비교(양쪽 해시만 교환) 검토 |
| 신설 admin API 의 노출 면 | 기존 체계 그대로: settlement admin JWT + order X-Internal-Api-Key. 전부 GET, 응답은 키+금액+건수만 (PII 원천 배제) |
| 대사 집계 쿼리의 운영 DB 부하 | 날짜 인덱스 확인, `/integrity-check` 는 온디맨드 (상시 폴링은 operation-service 시그널 BC 의 영역 — 중복 구축 금지) |
| 에이전트가 "ok=true 나열"을 정상으로 오독 | 모든 도구 응답에 `checkedInvariants[]` 명시 — `/compliance-scan` 의 침묵-통과 방지 패턴 재사용 |

## 6. 성공 지표

- 불변식 커버리지: 13개 중 검증 자동화 개수 (현재 3.5 → Phase A 후 8)
- 원장 누락·이중 지급의 **MTTD** (현재: 측정 불가 = 발견 못 함)
- `/integrity-check` 오탐률 < 10% (grace window 튜닝 지표)

## 7. 비고 — 의도적으로 뺀 것

- **자동 보정**: 스위트는 탐지까지만. 정정은 언제나 기존 운영 경로(조정/역분개/리플레이)로
  사람이 승인 — copilot read-only 원칙과 AGENTS.md "DB 직접 수정 금지" 규칙의 연장.
- **상시 알람 루프**: 주기 실행·알람은 operation-service(Alertmanager→인시던트)의 책임.
  이 스위트는 그 알람이 왔을 때의 **진단 도구**이고, 필요 시 operation-service 가
  `/admin/integrity/*` 를 폴링 원천으로 재사용하면 된다 (Phase 2a 시그널 BC 와 합류 지점).
