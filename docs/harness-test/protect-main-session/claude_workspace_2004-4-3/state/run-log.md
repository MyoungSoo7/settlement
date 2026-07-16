# run-log — 리프별 verdict + route 상태판

메인 세션이 도구가 바뀌어도 남기는 것: 리프별 verdict와 route 한 줄. sub agent는 매번 새로
띄워지므로 상태는 여기 메인에만 쌓인다.

## run 2004-4-3-dry — meeting-to-1page 한 바퀴

입력: `inputs/handoff-L3-result.md` (L3+L4 build가 돌려준 6칸 handoff) · 근거 원문 `inputs/meeting.md`

| 순서 | 리프 | sub agent | handoff | guard | verdict | route → 다음 자리 |
|---|---|---|---|---|---|---|
| 1 | L1 결정 항목 추출 질문 | ask | (이번 dry-run 미실행) | format | — | — |
| 2 | L3+L4 6칸 맵핑 + 확인 필요 항목 | build (inline) | `inputs/handoff-L3-result.md` | format | (입력으로 사용) | → L5 |
| 3 | L5 보존 표현 인용 검사 | review | (이번 dry-run 미실행) | evidence | — | — |
| 4 | **L6 도메인 제약 위반 검사** | **review** | `handoffs/handoff-L6-review.md` | false-negative | **ask-back (재질문)** | → **L1 ask / 사람** |

## 이번 verdict 한 줄

L6: format pass · evidence pass · false-negative 보정에서 성공 기준이 목표 문장인지 측정 지표인지
미결정 → **ask-back**. 첫 행동: 성공 기준을 목표로 둘지 지표로 쓸지 정하고, 지표면 측정 단위·기간을
정해 build에 되돌린다.

## 멈춤·재현 메모

- exit 조건(외부 결정자 답 대기 / 같은 rework 2회 반복)에는 해당하지 않는다 — 사내에서 목표/지표만 정하면 진행 가능.
- 같은 `handoff-L3-result.md`를 다시 넣으면 review-gate §3 규칙 6에 같이 걸려 ask-back으로 떨어진다
  (`inputs/review-gate.md` §8 replay와 동일). 단 이 재현은 LLM-judge의 의미 판정에 기댄 것이라
  v1에서 100% 보장하지 않는다 — 5장 과제.
- 결정론 사전 검수: `handoffs/handoff-L6-review.md`는 PostToolUse hook(format·인용 줄·금지값)을 통과(exit 0)했다.

## run 2026-06-04 — meeting.md 전체 한 바퀴 (실제 dispatch)

입력: `inputs/meeting.md` (회의록 원문 37줄). dry-run(L6만)과 달리 L1 → build → L5 → L6를 모두 실제로 띄웠다.

| 순서 | 리프 | sub agent | handoff | guard | verdict | route → 다음 자리 |
|---|---|---|---|---|---|---|
| 1 | L1 결정 항목 추출 질문 | ask (Task) | `handoffs/handoff-L1-ask.md` | format | 통과 | → build |
| 2 | L3+L4 6칸 맵핑 + 확인 필요 항목 | build (inline) | `handoffs/handoff-L3L4-build.md` | format | 통과 | → L5 |
| 3 | L5 보존 표현 인용 검사 (1회차) | review (Task) | `handoffs/handoff-L5-review.md` | evidence | **재작업** | → build (rework) |
| 3' | L3+L4 rework — 인용 3 교체 | build (inline) | `handoffs/handoff-L3L4-build.md` | format | 통과 | → L5 재검 |
| 4 | L5 보존 표현 인용 검사 (2회차) | review (Task) | `handoffs/handoff-L5-review.md` | evidence | 통과 | → L6 |
| 5 | **L6 도메인 제약 위반 검사** | review (Task) | `handoffs/handoff-L6-review.md` | false-negative | **재질문(ask-back)** | → **L1 ask / 사람** |

### 이번 verdict 한 줄
- L5 1회차: build가 `meeting.md:14`(큰따옴표 없는 산문)에 따옴표를 씌워 보존 인용으로 표기 → evidence 위반 **rework**. build가 운영 제약 인용을 `meeting.md:16` 실제 큰따옴표 발언("환불 기준 자체가 명문화돼 있지 않다")으로 교체 → 2회차 **통과**.
- L6: 값 단정(60% 본문 미진입)·범위 오염(두 갈래 유지)·미결정 결론 톤·외부 사실 단정 4항목 전부 통과. 성공 기준이 목표 문장인지 측정 지표인지 미결정 → **ask-back** (`inputs/review-gate.md` §3 규칙 6).

### 멈춤·재현 메모
- exit 아님 — 사내에서 성공 기준을 목표/지표로 정하면 진행 가능, 외부 회신 대기 아님. 같은 rework 2회 반복도 아님(L5 rework 1회, 교체 후 통과).
- 보류 상태: 성공 기준 목표/지표 결정 1건 + 확인 필요 항목 5건(담당자·일정·60% 출처·환불 명문화·일정 승인)은 외부 회차(월요일 정기 미팅·운영팀 회신) 대기.
- 재현: dry-run(L6만)과 이번 실제 한 바퀴 모두 L6에서 같은 규칙 6에 걸려 ask-back으로 수렴. 단 의미 판정은 LLM-judge라 100% 재현은 5장 과제.
- 결정론 사전 검수: L1·build·L5·L6 handoff 모두 PostToolUse hook을 통과. build 1회차에서 `60%` 무단정 위반(exit 2) 1건을 같은 줄에 "출처 미확인" 단서를 붙여 보정 후 통과.

### ask-back 해소 후 닫힘 (2026-06-04)

| 순서 | 리프 | sub agent | handoff | guard | verdict | route → 다음 자리 |
|---|---|---|---|---|---|---|
| 5' | L3+L4 rework — 성공 기준 지표화 | build (inline) | `handoffs/handoff-L3L4-build.md` | format | 통과 | → L6 재검 |
| 6 | L6 도메인 제약 위반 검사 (2회차) | review (Task) | `handoffs/handoff-L6-review.md` | false-negative | **통과(pass)** | → **메인 통합** |
| 7 | 메인 통합 | main | `1page-education-signup-v1.md` | — | 7칸 초안 확정 | (닫힘) |

- ask-back 결정 회수: 성공 기준을 측정 지표로 확정 — "다음 정기 미팅까지(약 4주) 15% 줄인다" (의사결정자 확정 2026-06-04). 15%·4주는 회의록 밖 값이지만 확인된 요구사항이라 근거를 `의사결정자 ask-back 확정`으로 표기(meeting.md 인용 아님).
- 최종 상태: **닫힘(pass)**. 7칸 1page 초안 = `1page-education-signup-v1.md`. 확인 필요 항목 5건(담당자·일정·60% 출처·환불 명문화·감소율 기준선)은 외부 회차 대기로 본문 단정 보류.
- 전체 한 바퀴 verdict 흐름: L1 통과 → build 통과 → L5 rework→통과 → L6 ask-back→(의사결정자 회수)→통과 → 통합. exit 없음, 같은 rework 2회 반복 없음.
