# MCP 전환 후보 + 스킬 분류 — meeting-to-1page 분업 하네스 v1

> 입력: `practice-materials/chapter-04/claude_workspace_2004-4-3/` (4-4 분업형 하네스 v1)
> 보는 법: 모든 단계를 MCP로 옮기지 않는다. 순서가 어긋나거나, 상태가 세션을 넘어 살아남아야 하거나, 실패 후 같은 자리로 돌아가야 하거나, 여러 검사를 하나로 묶어야 하는 단계만 가린다. 의미를 판정하는 단계는 사람/문서에 남긴다.

---

## 0. 한 줄 진단 — 경계는 이미 그어져 있다

이 하네스는 `review_gate.py` 한 파일 안에서 **결정론으로 잡는 것**(format 구조·`meeting.md:NN` 줄 실재·금지값 `60%` 단정)과 **LLM-judge가 보는 것**(인용이 주장을 뒷받침하나·성공 기준이 목표인지 지표인지)을 이미 갈라 놓았다. 그래서 5장의 일은 "어디를 가를까"가 아니라, **이미 갈라 둔 결정론 쪽을 워크스페이스 전용 PostToolUse hook 밖으로 들어올려 어디서든 부를 수 있는 MCP 도구로 만드는 것**이다. v1의 §5 backlog가 묻는 "gate 전체인가, 결정론 슬라이스만인가"의 답은 분석 결과 **결정론 슬라이스만**이다.

---

## 1. 분업형 하네스 v1 (4-4)

```text
input(meeting.md)
   └→ [메인 루프, 리프마다 반복]
        fill_handoff → dispatch(sub agent) → collect → review_gate → route → state
                                                          │              │       │
                                          (format·evidence·FN)   (pass/rework/   (run-log.md)
                                                                  ask-back/exit)

리프 순서:  L1 ask → L3+L4 build → L5 review → L6 review → main 통합
```

- **메인이 들고 있는 것**: 리프별 verdict + route 상태판(`state/run-log.md`), 다음 리프 결정, 멈춤 판단(외부 회신 대기 / 같은 rework 2회).
- **보조에 넘기는 것**: 리프 한 개 = sub agent 한 명. ask는 질문 생성, build는 6칸 맵핑, review는 검수 verdict. 들어가고 나오는 것은 언제나 handoff 한 장.
- **이미 분리된 것**: `dispatch` 한 줄만 도구에 의존한다(v1이 직접 명시). 나머지 네 줄(fill·review_gate·route·state)은 "도구 무관"이라 적혀 있지만, **실제 구현은 워크스페이스 전용 hook + 메인 세션의 주의 + 자유 markdown**이다. 이 셋이 업그레이드 지점이다.

---

## 2. 단계별 Need Signals — 신호가 어디서 켜지나

표를 채우는 대신, 각 신호가 **켜지는 자리**와 그게 **왜 지금 약한 구현인지**를 같이 읽는다.

### 2-1. 루프 기계 (리프가 공유하는 부분)

| 단계 | sequence | state | retry | orchestration | 지금 구현 / 판정 |
|---|---|---|---|---|---|
| review_gate **결정론 슬라이스** | 강 — 작성 후·route 전 고정 | 강 — `meeting.md` 줄 실재·금지값 목록 대조 | 강 — exit2 후 위반만 보정 재작성 | 강 — format+인용+금지값 3검사 묶음 | PostToolUse hook(워크스페이스 전용) → **MCP 1순위** |
| review_gate **의미 슬라이스** | 강 | — | 강 | — | review sub agent(LLM-judge) → **문서 유지, 재현은 5장 TraceGuard** |
| route | 강 — 다음 node 결정 | 강 — "같은 rework 2회" 카운터 | 강 — 재시도 컨트롤러 | 중 — 다음 dispatch 선택 | 메인 세션 주의 → **전이표·카운터 MCP / verdict 의미 문서** |
| state(run-log) | — | 강 — 세션 넘어 누적, 외부 회차 대기 | — | — | 자유 markdown → **스키마·저장 MCP / 내용 문서** |
| fill_handoff | 약 | 중 — 직전 verdict/route를 읽어 brief 구성 | — | 중 — leaf+state를 한 brief로 묶음 | 상태 읽기만 MCP / brief 내용 문서 |
| dispatch | — | — | — | 강 — sub agent 1명 띄움 | **이미 분리됨. MCP 아님**(도구 교체 지점) |

### 2-2. 리프 (의미가 핵심인 부분)

| 리프 | sequence | state | retry | orchestration | 판정 |
|---|---|---|---|---|---|
| L1 ask | 강 — 첫 노드 | 중 — 외부 회신이 회차로 누적 | ask-back 루프 유발 | — | **문서/사람** (질문 생성은 의미) |
| L3+L4 build | 강 — L1 답 의존 | — | rework 대상 | — | **문서** (6칸 맵핑은 의미, 4-1 §5 분업가치 낮음→inline) |
| L5 review | 강 — build 후 | — | rework 유발 | format→evidence→FN 묶음 | 값·구조=MCP gate / **의미=문서** |
| L6 review | 강 — L5 후 | rework 2회 카운터와 연결 | ask-back·rework 유발 | 4항목 가드 묶음 | 값·구조=MCP gate / **의미=문서** |

**읽기**: 신호가 가장 세게 겹치는 자리는 리프가 아니라 **루프 기계의 review_gate·route·state 셋**이다. 리프(ask·build)는 sequence 하나만 켜지고 나머지는 의미라, MCP로 옮기면 손해다. build를 MCP에 넣는 건 전형적인 표 채우기 함정이다 — 분업가치조차 낮아 inline으로 도는 단계다.

---

## 3. MCP 전환 후보 — 업그레이드 지점 셋

### 지점 1 — review gate 결정론 슬라이스: **Hook → MCP 도구** (1순위)

- **켜진 신호**: orchestration(3검사 묶음) + state(줄 실재·금지값 목록) + retry(exit2 보정 루프) + sequence(작성 후·route 전).
- **지금 약한 이유**: `review_gate.py`는 `Write|Edit` 직후에만 도는 사후 안전망이고, 이 워크스페이스 폴더에만 묶여 있다. 다른 작업에서 같은 검사를 부를 길이 없고, hook의 결과는 route에 직접 입력으로 들어가지 못한 채 "위반이면 다시 써라"로만 돌아온다.
- **MCP가 잠그는 것**: format 구조 검사 · `meeting.md:NN` 줄 실재 대조 · 금지값 단정 검사. 이미 결정론 코드라 매번 같은 verdict가 나온다. MCP 도구로 올리면 (a) 어느 워크스페이스에서도 호출 가능, (b) 같은 입력에 같은 결과 보장, (c) 결과가 **route의 입력**이 된다.
- **유지하는 것**: 의미 슬라이스(인용↔주장 대조, 성공 기준 목표/지표 판정)는 그대로 review sub agent. MCP로 코드화하지 않는다 — 같은 입력에 같은 verdict 보장은 코드가 아니라 5장 TraceGuard(trace + replay)의 몫이다. 여기서 정직성을 지킨다.
- **이게 5장 척추다**: hook 대체. 그리고 관계가 뒤집힌다 — v1은 LLM(review)이 gate이고 hook이 곁에서 검사한다. v2는 **MCP gate 도구가 gate이고, 그 도구가 의미 슬라이스를 위해 review sub agent(LLM)를 호출한다.** 툴이 LLM을 쓴다.

### 지점 2 — route 카운터·전이: **메인 세션 주의 → MCP 상태 기계**

- **켜진 신호**: state("같은 rework 사유 2회 반복" 카운터) + sequence(verdict→node 전이표) + retry(루프 컨트롤러).
- **지금 약한 이유**: "이번이 같은 rework 두 번째인가"를 메인 세션이 run-log를 눈으로 짚어 판단한다. LLM이 회차를 세는 일은 매번 같지 않아, exit 가드가 조용히 안 켜질 수 있다. 무한 재작업을 막는 안전장치가 비결정적 판단에 기대고 있다.
- **MCP가 잠그는 것**: 전이표(pass→다음 / rework→build / ask-back→L1 / exit→보고)와 rework 사유 카운터. 순수 장부라 코드가 깔끔하게 소유한다.
- **유지하는 것**: verdict 자체(pass인가 ask-back인가)는 의미 판정이라 review sub agent. 깔끔하게 갈라진다.

### 지점 3 — run-log: **자유 markdown → MCP 상태 스토어(스키마)**

- **켜진 신호**: state(세션을 넘어 누적, 외부 회차 대기). v1 §5 backlog 1번 그대로다.
- **지금 약한 이유**: 성공 기준 ask-back은 월요일 정기 미팅·운영팀 회신 같은 외부 회차를 거쳐 풀린다. 세션이 닫히면 verdict가 자유 markdown 표에만 남아, 다음 회차로 이어 쓸 스키마가 없다.
- **MCP가 잠그는 것**: `{leaf, verdict, route, rework_count, pending_external}` 같은 고정 스키마와 저장/조회.
- **유지하는 것**: 각 항목의 내용(어떤 질문이 왜 보류인지)은 문서.

---

## 4. 스킬 분류 — 반복되는 단계 묶음

| SKILL.md 후보 | 묶이는 단계 | 한 줄 역할 | 상태 |
|---|---|---|---|
| `using-task-harness` (메타 루프) | fill→dispatch→gate→route 4단계 | 리프마다 같은 협업 규약 | **이미 존재**. 단 gate·route를 산문으로 설명 → **MCP 도구 호출로 갱신** |
| `review-against-gate` (신규) | L5 + L6 (반복, 제약 늘수록 N회) | handoff를 format→evidence→FN으로 검수해 verdict 산출 | **신규 1순위**. 의미는 여기 남고, 값·구조는 지점 1의 MCP gate를 호출 |
| `write-handoff` (신규) | ask·build·review 세 agent 공용 | handoff 양식(From task/Result/판정/다음 행동) 한 장 쓰기 | **신규**. 양식 규칙의 단일 출처, MCP gate가 이 양식으로 검증 |

**읽기**: 메타 루프는 이미 올바르게 스킬이다 — 다시 만들지 않고, MCP 도구를 부르도록 본문만 고친다. 새로 뽑을 1순위는 `review-against-gate`다. L5·L6이 같은 review agent에 가드만 다른 반복 묶음이고, 도메인 제약이 늘면 L7·L8로 계속 늘어난다. 이 스킬의 의미 절반은 문서로 남고, 값·구조 절반은 MCP gate를 호출해 채운다 — 지점 1과 정확히 맞물린다.

---

## 5. 사람 경계 — 남길 판단

| 사람에게 남길 판단 | 이유 |
|---|---|
| 성공 기준을 목표 문장으로 둘지 측정 지표로 쓸지 (ask-back 회수) | 회의록 밖 값. run-log가 `의사결정자 확정 2026-06-04`으로 기록 — 의사결정자만 답할 수 있다 |
| 인용이 주장을 실제로 뒷받침하는가 (의미 대조) | LLM-judge 핵심. 코드로 잠그지 않고 5장 TraceGuard로 재현 |
| build의 6칸 맵핑·ask의 질문 생성 | 회의록 의미를 칸으로 옮기는 일. 분업가치 낮아 inline. MCP에 넣으면 표 채우기 함정 |
| exit 선언 (외부 답 대기 / rework 2회) | 카운터는 MCP가 세지만, "여기서 멈추고 보고한다"는 메인의 책임 |

---

## 6. 다음(5-2)으로 넘길 것

- **MCP로 잠글 흐름 한 줄**: review_gate.py의 결정론 슬라이스(format·인용 줄 실재·금지값)를 워크스페이스 hook에서 들어올려, 어디서든 호출하는 MCP gate 도구로 만든다. 그 도구가 의미 슬라이스를 위해 review sub agent를 안에서 호출한다(툴이 LLM을 쓴다).
- **묶은 스킬 후보**: `review-against-gate`(L5+L6, 1순위) · `write-handoff`(3 agent 공용) · `using-task-harness`(MCP 호출로 갱신).
- **5-2에서 런타임 독립 그래프로 만들 대상 (하나로 좁힘)**: **지점 1 — review gate 결정론 슬라이스의 MCP 도구화.** 이미 결정론 코드라 이전이 즉시 가능하고, 5장 "Hook 대체 / 툴이 LLM을 쓴다" 척추를 가장 구체적으로 보여주며, 이 노드가 서면 route(지점 2)와 state(지점 3)가 자연히 그 도구에 붙는다. 런타임 독립 그래프의 첫 노드는 이 MCP gate이고, review sub agent는 그 노드 안에서 호출되는 한 단계로 내려간다.

---

## 자기 검수

- [x] 4-4 분업형 하네스 v1이 단계 흐름(루프 4단계 + 리프 직렬)으로 그려져 있다.
- [x] 단계별 sequence·state·retry·orchestration 신호가 근거(hook 코드·run-log·§5 backlog)와 함께 있다.
- [x] MCP로 잠글 단계(결정론 슬라이스·전이·카운터·상태 스키마)와 사람/문서에 남길 판단(의미·6칸 맵핑·exit 선언)이 구분되어 있다.
- [x] 반복 단계가 SKILL.md 후보 3개로 분류되어 있다 — 이미 존재 1 + 신규 2.
- [x] 5-2로 넘길 대상이 지점 1 하나로 좁혀져 있다.
