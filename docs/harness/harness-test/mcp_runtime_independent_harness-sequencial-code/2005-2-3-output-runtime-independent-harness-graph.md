# Runtime-independent Harness Graph — meeting-to-1page 분업 하네스

> 입력: `practice-materials/chapter-04/claude_workspace_2004-4-3/` (4-4 분업형 하네스 v1) + `2005-1-3-output-mcp-skill-classification.md` (MCP 전환 후보 · 스킬 분류)
> 보는 법: §1~6은 4-4 하네스를 그대로 옮긴 것이다. 새 작업이 아니라 같은 흐름을 다시 적은 것이라, 위에 §7 Capability Matrix와 §8 Skill Bundle 한 층을 얹어 어느 런타임에서도 같은 워크플로우가 작동하게 만든다. 5-1이 지점 1 하나(review gate 결정론 슬라이스의 MCP 도구화)로 좁혀 둔 결론을 그래프의 첫 노드로 세운다.

---

## 1. Candidate Workflow

- **업무**: 회의록 한 건을 1page 7칸 초안으로 옮긴다.
- **시작 입력**: `inputs/meeting.md` (회의록 원문 한 건).
- **최종 산출물**: L5·L6 review를 통과한 7칸 1page 초안(6칸 본문 + 확인 필요 항목 1칸) + 리프별 verdict 한 줄.
- **사람 판단 항목**: 성공 기준을 목표 문장으로 둘지 측정 지표로 쓸지(ask-back 회수), 인용이 주장을 실제로 뒷받침하는지 의미 대조, "여기서 멈추고 보고한다"는 exit 선언.

---

## 2. Outside Responsibility (사람)

- **문제 정의**: 어떤 회의록을 어떤 1page로 옮길지 정한다. 회의록 밖 맥락(이 문서를 누가 볼지, 무엇을 결정에 쓸지)은 사람만 안다.
- **원본 입력**: 회의록 원문(`inputs/meeting.md`)을 사람이 가져와 워크스페이스에 둔다.
- **최종 판단**: 성공 기준 목표/지표 결정은 의사결정자만 답한다(run-log가 `의사결정자 확정 2026-06-04`으로 기록). 인용↔주장 의미 대조는 review sub agent가 보지만, 마지막 채택은 사람. exit 선언("외부 답 대기 / 같은 rework 2회")도 메인의 책임이다.
- **결과 공유**: 닫힌 7칸 초안과 확인 필요 항목 목록을 다음 사람에게 넘긴다.

---

## 3. Inside Responsibility (코드)

| 단계 | 입력 | 처리 | 출력 | 통과 조건 |
|---|---|---|---|---|
| 입력 정리 (`fill_handoff`) | leaf + 직전 verdict/route(run-log) | 4-2 handoff 양식 §1을 채워 brief 한 장으로 묶는다 | 채운 handoff brief 한 장 | From task 4칸·검수 대상·근거 경로가 한 화면에 들어온다 |
| 명세 (`dispatch`) | handoff brief 한 장 | 리프 1개에 sub agent 1명을 띄운다(런타임 의존 단계) | sub agent가 `handoffs/`에 돌려준 handoff out | 들어가고 나오는 것이 언제나 handoff 한 장이다 |
| 실행 (sub agent) | handoff brief + 근거 파일 경로 | ask=질문 생성 / build=6칸 맵핑 / review=검수 verdict | handoff out(결과 요약·판정·남은 질문·다음 행동) | 양식대로 닫히고 결과에 보조 세션의 대화 흔적이 없다 |
| 검수 (`review_gate`) | handoff out | **결정론 슬라이스**: format 구조·`meeting.md:NN` 줄 실재·금지값 `60%` 단정 검사 / **의미 슬라이스**: 인용↔주장 대조·성공 기준 모호 판정 | verdict(통과·재작업·재질문·종료) + route | 결정론 3검사는 매번 같은 결과, 의미 판정은 verdict 한 줄로 닫힌다 |

읽기: 결정론 슬라이스는 지금 `review_gate.py`가 `Write|Edit` 직후에만 도는 워크스페이스 전용 사후 안전망이다. §7~§8에서 이 슬라이스를 들어올려 어디서든 호출하는 MCP gate 도구로 만든다. 의미 슬라이스는 그대로 review sub agent가 본다 — 그리고 MCP gate 도구가 이 의미 슬라이스를 위해 review sub agent를 안에서 호출한다. 툴이 LLM을 쓴다.

---

## 4. State & Checkpoints

- **현재 단계**: 리프별 verdict + route를 `state/run-log.md` 상태판에 한 줄씩 쌓는다. sub agent는 매번 새로 띄워지므로 상태는 메인에만 남는다.
- **결정**: ask-back 회수 결과(예: "성공 기준 = 다음 정기 미팅까지 15% 줄인다, 의사결정자 확정")를 회차로 기록한다.
- **실패 이력**: rework 사유 카운터. 같은 사유가 2회 반복되면 exit 가드가 켜진다.
- **검수 결과**: 각 리프의 verdict와 근거 줄.
- **checkpoint(5장 신규)**: rework가 build로 돌아갈 때 "여기까지 통과"를 고정할 중간 지점. v1엔 없고, MCP state_store 스키마 `{leaf, verdict, route, rework_count, pending_external}`로 잠근다(지점 3). 세션이 닫혀도 외부 회차(월요일 정기 미팅·운영팀 회신)를 거쳐 같은 자리로 복귀한다(targeted_resume).

---

## 5. Retry & Re-question

- **자동 재시도(rework)**: 같은 build(L3+L4)로 되돌린다. 누락 칸·잘못된 수치·없는 근거 줄·범위 오염 중 하나를 한 줄로 고쳐 재제출한다. 전체 재작성은 하지 않는다.
- **사람 재질문(ask-back)**: L1 ask 또는 사람으로 되돌린다. 막힌 항목(성공 기준 목표/지표)만 좁혀 다시 묻는다.
- **복귀 지점**: rework는 build, ask-back은 L1. 전이표(pass→다음 / rework→build / ask-back→L1 / exit→보고)는 순수 장부라 코드가 깔끔하게 소유한다(지점 2 — route 카운터·전이를 MCP 상태 기계로).
- **다음 행동(exit)**: 외부 결정 대기 또는 같은 rework 2회 반복 상태로 보존하고 메인이 보고한다.

---

## 6. Output Contract

- **최종 산출물**: 7칸 1page 초안(`1page-education-signup-v1.md`).
- **판단 근거**: 리프별 verdict + `meeting.md` 인용 줄. 회의록 밖 값(15%·4주)은 `의사결정자 ask-back 확정`으로 표기해 인용과 구분한다.
- **남은 질문**: 확인 필요 항목 5건(담당자·일정·`60%` 출처·환불 명문화·감소율 기준선)은 외부 회차 대기로 본문 단정을 보류한다.
- **다음 사람이 볼 요약**: 전체 한 바퀴 verdict 흐름 한 줄 — L1 통과 → build 통과 → L5 rework→통과 → L6 ask-back→(의사결정자 회수)→통과 → 통합.

---

## 7. Capability Matrix (NEW)

| layer | 고정/변동 | 본인 업무의 항목 |
|---|---|---|
| Workflow Layer | 어디서나 같음 | 메인 루프 4단계(fill_handoff → dispatch → review_gate → route) · 리프 1개 = sub agent 1명 · guard 정의(format·evidence·false-negative) · route 전이표(pass/rework/ask-back/exit) · handoff 입출력 계약(한 화면 한 장) |
| Runtime Layer | 런타임마다 다름 | dispatch 실행 어댑터(Claude Code Task / Codex sub-agent 또는 새 세션 / 다른 CLI 호출) · MCP gate 도구 호출 transport(stdio/HTTP) · state_store 저장 백엔드 |
| Integration Surface | UX 차이 | SessionStart 규약 주입 방식 · PostToolUse hook 등록 · Task/슬래시 호출 UI · `handoffs/` 파일이 보이는 위치 · skill 디렉터리 위치(`~/.claude/skills/`, `~/.codex/skills/`) |

### capability 후보 → layer 매핑

| capability | layer | 잠그는 것 |
|---|---|---|
| `guard` | Workflow | review gate 결정론 슬라이스(format·인용 줄 실재·금지값). **지점 1 — 첫 노드인 MCP gate 도구.** 의미 슬라이스를 위해 review sub agent를 안에서 호출한다 |
| `structured_output` | Workflow | handoff 한 장의 고정 양식(From task / Result / 판정 4종 / 다음 행동) |
| `skill_dispatch` | Workflow → Runtime | 리프마다 같은 협업 규약(고정) + sub agent를 띄우는 실제 명령(런타임) |
| `tool_call` | Runtime | dispatch 한 줄 + MCP gate 호출. v1이 "도구 교체 지점"으로 명시한 자리 |
| `state_store` | Runtime | run-log 스키마 저장·조회(지점 3) |
| `checkpoint` | Runtime | rework가 build로 돌아갈 중간 지점 고정 |
| `targeted_resume` | Runtime | 외부 회차로 멈춘 ask-back을 다음 세션에서 같은 자리로 복귀 |

읽기: workflow layer는 도구가 바뀌어도 한 글자도 안 바뀌는 부분이다. runtime layer만 갈아끼우면 같은 워크플로우가 Claude Code에서도, 다른 CLI에서도, 새 대화창 복붙에서도 같은 결과를 낸다. `guard`를 workflow에 둔 것이 5장 척추다 — gate가 LLM이 아니라 MCP 도구가 되고, 그 도구가 의미 판정만 LLM에 위임한다.

---

## 8. Skill Bundle (NEW)

| SKILL.md 묶음 | 선언할 capability | 런타임별 호출 방식 |
|---|---|---|
| `using-task-harness` (이미 존재 · MCP 호출로 갱신) | `skill_dispatch` · `state_store` · `tool_call` | Claude `~/.claude/skills/using-task-harness/` + Task 도구 · Codex `~/.codex/skills/` · 다른 CLI/새 대화창은 본문 규약 복붙. gate·route를 산문 설명 대신 MCP 도구 호출로 고쳐 부른다 |
| `review-against-gate` (신규 1순위) | `guard` · `structured_output` · `tool_call` | 세 런타임 모두에서 MCP gate 도구를 같은 프로토콜(stdio/HTTP)로 호출. L5+L6의 값·구조 절반은 MCP gate가 채우고, 의미 절반은 스킬 본문에 남는다 |
| `write-handoff` (신규) | `structured_output` | 양식(From task / Result / 판정 / 다음 행동) 규칙의 단일 출처. 모든 런타임 공용이고, MCP gate가 이 양식으로 검증한다 |

읽기: 메타 루프는 이미 올바르게 스킬이다 — 다시 만들지 않고 MCP 도구를 부르도록 본문만 고친다. 새로 뽑는 1순위는 `review-against-gate`다. L5·L6이 같은 review agent에 가드만 다른 반복 묶음이고, 도메인 제약이 늘면 L7·L8로 계속 늘어난다. 이 스킬의 `guard` capability가 지점 1의 MCP gate와 정확히 맞물린다.

---

## 첫 노드 한 줄 (5-1 → 5-2 이음)

런타임 독립 그래프의 첫 노드는 **review gate 결정론 슬라이스의 MCP gate 도구**다. 이미 결정론 코드(`review_gate.py`)라 이전이 즉시 가능하고, 이 노드가 서면 route(지점 2)와 state(지점 3)가 자연히 그 도구에 붙는다. review sub agent는 이 노드 안에서 호출되는 한 단계로 내려간다 — v1은 LLM(review)이 gate였지만, v2는 MCP gate 도구가 gate이고 그 도구가 LLM을 쓴다.

---

## 자기 검수

- [x] §1~6에 4-4 분업형 하네스가 그대로 옮겨져 있다(메인 루프 4단계·route 전이·run-log·output contract).
- [x] §7 Capability Matrix가 workflow / runtime / integration으로 분리되어 있고, capability 후보 7개가 layer에 매핑되어 있다.
- [x] §8 Skill Bundle에 SKILL.md 묶음 3개·선언 capability·런타임별 호출(Claude·Codex·다른 CLI)이 적혀 있다.
- [x] workflow layer(어디서나 같음)와 runtime layer(런타임마다 다름)가 한 칸에 섞이지 않았다 — guard·route·계약은 workflow, dispatch·state_store·skill 경로는 runtime.
- [x] 사람에게 남길 판단(§2 — 성공 기준 결정·의미 대조·exit 선언)이 코드 안쪽(§3)과 구분되어 있다.
- [x] 첫 노드가 지점 1(MCP gate) 하나로 서고, 그 안에서 review sub agent를 호출하는 "툴이 LLM을 쓴다" 구조가 명시되어 있다.
