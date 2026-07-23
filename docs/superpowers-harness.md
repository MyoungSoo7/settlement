# Superpowers의 하네스 효과 — 측정 계층(L1~L4)과 A/B 프로토콜

> superpowers 플러그인(v6.1.1)이 **이 프로젝트의 하네스에서** 내는 효과를 "무엇을, 어디까지
> 측정할 수 있는가" 기준으로 정리한다. 실측 데이터 정본은 하네스 텔레메트리
> (`.claude/harness/logs/*.jsonl`, 집계 `node scripts/harness/telemetry-report.mjs`).
>
> **Last updated:** 2026-07-22 · 관련: [`HARNESS.md`](../HARNESS.md) 라우팅 맵·이중 라우팅 경계

## 1. 전제 — 이 프로젝트에서 superpowers가 맡는 표면

절차 규율 3종(`tdd-discipline`·`debugging-discipline`·`verify-before-done`)을 저장소에 내재화했고
(2026-07-22, `ef21852e3`), 요구사항 인터뷰는 `socrates` 패밀리, 플래닝·에이전트는 OMC가 담당한다.
따라서 superpowers의 **고유 잔여 표면**은 두 층뿐이다:

1. **세션 주입층** — SessionStart(`startup|clear|compact`)마다 `using-superpowers` 전문 주입:
   "1%라도 해당하면 스킬 호출" 습관 압박 + Red Flags(합리화 차단) 표.
2. **잔여 프로세스 스킬** — writing-plans/executing-plans, subagent-driven-development,
   code-review 요청·수신, writing-skills 등 프로젝트가 내재화하지 않은 범용 절차 14종 중 일부.

지침 충돌 시 우선순위는 프로젝트 CLAUDE.md/HARNESS.md > 플러그인 주입(superpowers v6도 이를 명시).

### 기대효과의 인과 사슬

```
세션 주입 → 스킬 확인 습관 → 절차 생략률 ↓
  → 원인 미규명 수정·검증 없는 완료 선언 ↓
  → 가드 발화·CI red·재작업 커밋 ↓
```

문서 규율이므로 효과는 **차단이 아니라 확률 인하**다. 반면 비용(세션당 주입 토큰,
using-superpowers 62줄 고정)은 **결정적**이다 — 효과는 확률적인데 비용은 확정적인 비대칭.

## 2. 측정 계층 L1~L4

| 계층 | 질문 | 측정 수단 | 가능 여부 |
|---|---|---|---|
| **L1 호출** | superpowers 스킬이 실제 로드됐나 | `skill-usage.jsonl` (skill-router PreToolUse 훅이 네임스페이스 포함 전 스킬 로드 기록) | ✅ 즉시·정확 |
| **L2 선행 대리지표** | 규율 행동이 좋아졌나 | 라우터 **제안 대비 로드율**(`skill-suggestions.jsonl` vs usage) · 세션당 **가드 발화**(`guard-hits.jsonl`) | ✅ 가능 — 단 혼재변수(모델·OMC·자체 스킬) 존재 |
| **L3 결과 지표** | 산출물 품질이 좋아졌나 | CI red 원인 분류(`gh run list`) · fix-after-feat 재작업 커밋 비율(git log) · JaCoCo 추이 | ⚠️ 측정은 되나 **귀속 불가** |
| **L4 인과 귀속** | superpowers **덕분**인가 | on/off 준실험(§4 A/B 프로토콜)만 유일 경로 | ❌ 반사실 측정 불가 — 상한은 **방향성 관찰** |

### 2026-07-22 실측 스냅샷 (재현: `node scripts/harness/telemetry-report.mjs`)

- 스킬 로드 19회 중 **superpowers:\* 0회** — 도메인 규칙·자체 절차 스킬(`debugging-discipline` 1회)·ouroboros가 전부.
- 라우터 제안 무시 2건(`tdd-discipline` 제안 후 미로드) — 자체 규율층의 다음 개선 신호.
- 가드 발화 2건(MONEY-PRIMITIVE 1). STATUS 드리프트발 CI red 3연속(7/18~7/21)은
  superpowers·문서 규율 모두 못 막았고 **기계 게이트(harness-audit)가 잡았다**.

### 현재 결론

이 프로젝트의 규율 효과는 실측상 **자체 하네스(라우터 주입 + 3중 가드 + 게이트)가 내고 있다**.
superpowers의 실기여는 L1에서 관찰되지 않으며, 남는 것은 측정 불가능한 주입 습관층뿐이다.
"기여가 있는가"에 데이터로 답하려면 §4의 준실험이 상한이다.

## 3. 측정 불가능한 것 (한계 명시)

- **반사실**: 주입이 없었어도 모델이 같은 절차를 밟았을지 — 관측 불가.
- **주입의 내부 효과**: Red Flags 문구가 사고 과정에 미친 영향 — 텔레메트리에 흔적이 없다.
- **과제 이질성 보정**: 세션마다 작업 성격이 달라 지표의 세션 간 비교는 항상 오염된다.

## 4. A/B 프로토콜 — on/off 준실험 설계

### 4.1 설계

| 항목 | 값 |
|---|---|
| 단위 | 세션(session_id) — 텔레메트리 3종이 모두 세션 id·타임스탬프를 기록 |
| Arm | **ON** = superpowers enabled / **OFF** = `claude plugin disable superpowers@superpowers-marketplace` |
| 배정 | **일 단위 교차(day-parity)**: 홀수일 ON, 짝수일 OFF — arm이 날짜에서 유도되므로 별도 원장 불필요 |
| 기간·표본 | 2주, arm당 **최소 10 세션**(미달 시 연장) |
| 범위 | 이 저장소의 대화형 세션만. 서브에이전트·CI·cron 세션 제외(주입 자체가 없거나 SUBAGENT-STOP) |
| 토글 시점 | 날짜 경계에서 `claude plugin enable/disable` 실행 후 새 세션 시작(주입은 SessionStart에만 발생) |

### 4.2 사전 등록 지표 (분석 전에 고정 — 사후 선택 금지)

| 지표 | 정의 | 수집 |
|---|---|---|
| 제안 무시율 | 라우터 제안 스킬 중 같은 세션에서 미로드된 비율 | `skill-suggestions.jsonl` ⋈ `skill-usage.jsonl` (session_id 조인) |
| 세션당 가드 발화 | hook mode 차단 건수 / 세션 | `guard-hits.jsonl` |
| 절차 스킬 선행률 | 세션 첫 Write/Edit **이전에** 절차·규칙 스킬 로드가 있었던 세션 비율 | usage·suggestions 타임스탬프 비교 |
| superpowers 로드 수 | ON arm에서만 의미 — 0에 머물면 주입층 외 기여 없음이 확정 | `skill-usage.jsonl` grep `superpowers:` |
| push당 CI red | harness-guard failure / push (보조지표 — 귀속 불가, 방향만) | `gh run list --branch develop --workflow harness-guard.yml` |

### 4.3 판정 기준 (사전 고정)

- **방향성 신호**: arm당 n≥10에서 제안 무시율·가드 발화 중앙값이 **2배 이상** 차이 → 해당 방향의
  운영 결정 근거로 채택(통계적 유의가 아니라 방향성 관찰임을 명시).
- **차이 없음**: OFF arm에서 지표 악화 없음 → "이 프로젝트에선 자체 하네스로 충분" 확정 →
  superpowers는 **전역(타 프로젝트) 층으로만 유지**하고 이 프로젝트 최적화에서 제외.
- **ON arm superpowers 로드 0 지속**: 결과와 무관하게 잔여 스킬 표면은 미사용 확정 —
  기여 후보는 주입 습관층으로 좁혀진다(그 층은 본 실험으로도 분리 측정 불가, §3).

### 4.4 오염원과 통제

- 과제 이질성(최대 오염원): 세션 수로 평균화 + 판정 임계를 2배로 보수화. 대형 캠페인(랄프 루프
  등)이 낀 날은 양 arm에서 제외 기록.
- 병행 세션: 같은 날 여러 세션이 같은 arm이 되도록 day-parity 유지(세션 단위 임의 토글 금지).
- 로그 이동: 텔레메트리는 `.claude/harness/logs` 가 정본(2026-07-22 `.omc` 에서 이전) —
  실험 시작 시점 이후 데이터만 사용.

### 4.5 실행 절차 (하루 루틴)

```bash
# 날짜 경계(첫 세션 전) — 홀수일 ON / 짝수일 OFF
claude plugin enable  superpowers@superpowers-marketplace   # 홀수일
claude plugin disable superpowers@superpowers-marketplace   # 짝수일

# 실험 종료 후 집계
node scripts/harness/telemetry-report.mjs                    # 전체 요약
# 세션×arm 상세: .claude/harness/logs/*.jsonl 을 session_id 로 조인, 날짜 홀짝으로 arm 부여
```

> 실험 종료 후 결과·판정은 이 문서 하단에 스냅샷 날짜와 함께 추가한다(사전 등록 지표 외
> 추가 지표는 "탐색적"으로 별도 표기).
