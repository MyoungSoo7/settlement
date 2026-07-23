# 2005-4-3 — meeting-to-1page 하네스 v2 (runtime-independent harness)

4장 `meeting-to-1page` 하네스 v1을 5장 capability graph로 승격한 v2 workspace다.
2~4장에서 만든 실제 산출물을 read-only 입력으로 통합하고, graph node를 실제 함수로 돌려
이 workspace 안에서 파일을 읽고 쓴다.

핵심 시나리오는 두 번의 실행이다. 첫 실행(run)은 성공 기준 metric이 확정되지 않은 입력을
만나 `ask-back`으로 멈추고, 회신을 채운 뒤 두 번째 실행(resume)이 검수를 통과해 `exit`로
최종 1page를 낸다. 순서·state·route는 전부 코드가 정한다 — 모델이 정하지 않는다.

## 한 줄 graph

```
requirements_contract -> spec_contract -> session_surface -> structured_contract
-> state_store -> fill_handoff -> dispatch_plan -> invoke_runtime_adapter
-> collect_handoff -> review_gate -> review_parallel(L5 ∥ L6) -> merge_verdict -> route
```

13개 node가 잠긴 순서로 돈다. 검수는 인용 충실성(L5)과 사실·범위(L6) 두 leaf가 병렬이다.

---

## 워크스페이스 작업 가이드 (따라하기)

### 준비물

| 항목 | 필요 여부 |
|---|---|
| Python 3.10+ | 필수. 외부 패키지 없이 표준 라이브러리만 쓴다 (local/replay 경로 기준) |
| Claude Code CLI (`claude`) | 선택. live 검수·MCP 세션 호출 단계에서만 |
| Codex CLI (`codex`, 로그인 상태) | 선택. live 검수의 L6 쪽에서만 |
| 3장·4장 실습 산출물 | 필수. `practice-materials/chapter-03/2003-4-3-workspace`와 `chapter-04/codex_workspace_2004-4-3`가 같은 레포 안에 있어야 한다 (step 0이 여기서 복사한다) |

live 어댑터가 없어도 실습 전체가 돌아간다. 인증이 안 돼 있으면 검수는 정직하게 local
judge로 폴백하고 로그에 `review.parallel.fallback`을 남긴다.

### step 0 — read-only 입력 정렬 (최초 1회)

```bash
cd practice-materials/chapter-05/2005-4-3-workspace
bash setup-readonly-sources.sh
```

3장 스킬 2개(`../../../../../.claude/skills`)와 4장 회의록·handoff·state(`inputs`, `source`,
`../../../../../.codex`)를 이 workspace 안으로 복사한다. **원본은 수정하지 않는다.** 복사가 끝나면
`inputs/meeting.md`(줄번호 회의록)와 `contracts` 아래 frozen 계약 2개를 한 번 읽어 둔다.
이 두 계약이 graph의 0번·1번 node 입력이다.

### step 1 — RUN: 첫 실행은 일부러 멈춘다

```bash
python3 harness_v2_server.py run --review-adapter local
```

기대 결과는 성공이 아니라 **`route = ask-back`** 이다. 입력으로 들어가는
`inputs/handoff-L3-result.md`는 성공 기준이 목표 문장인지 측정 지표인지 미정인 상태라,
L6 검수가 "회의록 밖 결정이 필요하다"고 판단해 멈춘다.

실행 후 확인할 것 세 가지:

1. 터미널 마지막 줄 — `=== route = ask-back ===`
2. `state/pending-external.md` — 무엇이 미확정인지, 누구에게 물어야 하는지 적혀 있다
3. `state/run-log.md` — node별 진행 기록 (append-only, 지우지 않고 쌓인다)

### step 2 — 회신 확인: ask-back에 답을 채운다

```bash
cat inputs/answers.md
```

`inputs/answers.md`는 ask-back 질문에 대한 회신(2장 사각지대 채택 형식)이다. 실습에서는
이미 채워져 있다 — "환불·일정 문의 15% 감소 / 4주" 라는 metric 확정 답이 들어 있다.
본인 입력으로 바꿔 보고 싶으면 이 파일의 답 부분만 수정한다. 질문 구조(채택 ☑/☒ 형식)는
유지해야 requirements_update가 파싱한다.

### step 3 — RESUME: 회신을 반영해 끝까지 돌린다

```bash
python3 harness_v2_server.py resume --answers inputs/answers.md --review-adapter local
```

requirements_update node가 answers.md의 채택 내용을 계약에 반영하고, 이번에는 metric이
확정된 build handoff(`source/handoffs/handoff-L3L4-build.md`)가 검수를 통과한다.
기대 결과는 **`route = exit`** 이고, 최종 산출물이 `handoffs/final-1page-draft.md`에 생긴다.

`--review-adapter local`이면 같은 입력 → 같은 route가 보장된다(결정론 재현).
`--review-adapter live`로 바꾸면 L5는 실제 Claude Agent SDK, L6는 실제 `codex exec`가
검수한다 — 이때는 LLM verdict에 의존하므로 결과가 매번 같다고 보장하지 않는다.

### step 4 — 검증: 4장 결정론 guard로 직접 확인

```bash
python3 .codex/hooks/review_gate.py handoffs/handoff-L5-review.md --leaf L5   # route: pass
python3 .codex/hooks/review_gate.py handoffs/handoff-L6-review.md --leaf L6   # route: pass
```

`review_gate.py`는 4장에서 만든 결정론 guard를 수정 없이 복사한 것이다. 하네스가
생성한 검수 handoff가 이 gate를 통과하면 (필수 섹션 + 키워드 + 60% 마커) 실습 사슬이
4장과 끊기지 않고 이어졌다는 뜻이다.

### step 5 — 산출물 읽기

| 파일 | 보는 이유 |
|---|---|
| `handoffs/final-1page-draft.md` | 최종 1page. 3장 7칸 output contract를 채웠는지 |
| `state/run-log.md` / `run-log.jsonl` | run·resume 두 실행이 어떤 node를 어떤 verdict로 지났는지 |
| `state/checkpoints` | node 경계마다 회전 저장된 체크포인트 (rewind 재료) |
| `runtime-independent-harness-demo.md` | §1-7 실측 데모 기록. 본인 결과와 대조 |

### 다시 처음부터 돌리고 싶을 때

run은 fresh init이라 그냥 다시 실행하면 된다. `handoffs`와 `state`는 하네스가 다시
생성한다. `inputs`, `source`, `contracts`는 read-only 입력이므로 손대지 않는다.
실수로 수정했다면 `bash setup-readonly-sources.sh`를 다시 실행해 복원한다.

### 자주 걸리는 것

- **`No such file or directory` (setup 단계)** — 3장 `2003-4-3-workspace/.claude/skills/` 또는
  4장 `codex_workspace_2004-4-3/`가 없는 경우다. 이 workspace는 3·4장 산출물을 전제로 한다.
- **live에서 route가 매번 다르다** — 정상이다. live는 비결정적이다. 재현이 필요하면 모든
  단계를 `--review-adapter local`로 돌린다.
- **codex 미로그인** — L6 live 검수가 local judge로 폴백한다. 로그의
  `review.parallel.fallback`으로 확인할 수 있다.

---

## runtime adapter (5-2 런타임 독립)

| runtime | 호출 방식 | 인증 |
|---|---|---|
| Claude (L5 인용) | Claude Agent SDK `query()` | Claude Code 기존 인증 (raw API 키 불필요) |
| Codex (L6 사실·범위) | `codex exec --json --output-last-message` | Codex CLI 로그인 |
| replay/local | 4장 녹화 산출 회수 / 결정론 judge | 외부 호출 없음 (재현용) |

> live 어댑터(`review_server.py`)는 현재 Claude Code 세션 안에서 SDK가 claude CLI를 다시 띄울 때
> `env={"CLAUDECODE": ""}`로 중첩 실행 차단을 우회한다. 패키지/런타임 없으면 정직하게 local로 폴백한다.

## MCP 서버 등록 — review_parallel을 실제 세션에서 호출

`review_server.py`(FastMCP)를 MCP 서버로 등록하면, 실제 Claude Code / Codex 세션이
`review_parallel` 툴을 직접 호출하고 그 툴 안에서 Claude Agent SDK ∥ codex exec가 돈다
("툴이 LLM을 쓴다"를 세션 레벨에서 실현).

**등록 위치 (실측 — 도구가 실제로 읽는 곳)**

| 런타임 | 정의 위치 | 활성화 |
|---|---|---|
| Claude Code | `.mcp.json` (워크스페이스 **루트**, project scope) — Claude는 `../../../../../.claude` 안에서 MCP 정의를 읽지 않는다 | `../../../../../.claude/settings.json`의 `"enabledMcpjsonServers": ["meeting-1pager-review"]` |
| Codex | 전역 `~/.codex/config.toml` `[mcp_servers.*]` — Codex는 프로젝트-로컬 `.codex/config.toml` mcp_servers를 자동 로드하지 않는다 | `codex mcp add`로 등록 (워크스페이스 `.codex/config.toml`에 portable 템플릿만 둠) |

```bash
# Claude (이미 .mcp.json + settings.json에 등록됨). 인식·health 확인:
claude mcp get meeting-1pager-review            # → Status: ✓ Connected

# Codex (전역 등록이라 사용자가 직접):
codex mcp add meeting-1pager-review -- python3 "$PWD/review_server.py"
codex mcp get meeting-1pager-review
```

**실제 세션에서 호출**

> ⚠️ 결정론 주의: `local`/`replay`만 "같은 입력 → 같은 route"를 보장한다(외부 호출 없음).
> `live`/`mcp`는 실제 Claude·Codex LLM verdict에 의존해 **비결정적**이라 같은 입력에도 route가
> 달라질 수 있다(LLM flake, codex 미로그인 등). codex/claude 인증이 안 돼 있으면 review는
> 정직하게 local judge로 폴백한다(로그에 `review.parallel.fallback`). 아래 결과는 한 번의 실행
> 예시이지 매번 보장되는 값이 아니다. 재현이 필요하면 모든 단계를 `--review-adapter local`로.

```bash
# (a) MCP 클라이언트로 직접: 서버 spawn → tools/list → review_parallel 호출
python3 mcp_client_demo.py
#   → tools/list ['review_parallel'] → Claude Agent SDK ∥ codex exec → route=exit

# (b) 실제 Claude Code 세션이 툴 호출
claude -p "mcp__meeting-1pager-review__review_parallel 툴로 handoffs/handoff-L3L4-build.md(draft)와 inputs/meeting.md(source)를 검수하고 route만 답해라" \
  --allowedTools "Read,mcp__meeting-1pager-review__review_parallel" \
  --mcp-config .mcp.json --strict-mcp-config
#   → L5=pass L6=pass route=exit   (세션 → MCP 서버 → Claude ∥ Codex)
```

> 툴 fully-qualified 이름: `mcp__meeting-1pager-review__review_parallel`.
> 등록 해제: `claude mcp remove meeting-1pager-review -s project` / `codex mcp remove meeting-1pager-review`.

### 두 층위의 MCP — node vs graph

| 서버 | 노출 범위 | 메인 세션이 콜하면 |
|---|---|---|
| `review_server.py` (`meeting-1pager-review`) | graph 중 **review_parallel 한 node** | 검수만 돈다 (Claude 인용 ∥ Codex 사실·범위) |
| `harness_mcp_server.py` (`meeting-to-1page-harness`) | **capability graph 전체** | requirements_contract→…→route 가 한 번에 돌고 route+산출물 반환 |

```bash
# 메인 세션이 graph 전체를 MCP 한 콜로 실행 (검증됨)
claude -p "mcp__meeting-to-1page-harness__run_meeting_to_1page 툴을 work_adapter=replay, review_adapter=local 로 호출하고 route만 답해라" \
  --allowedTools "mcp__meeting-to-1page-harness__run_meeting_to_1page" \
  --mcp-config .mcp.json --strict-mcp-config
#   → route: ask-back · pending_external: state/pending-external.md
# 이어서 resume_meeting_to_1page 툴 콜 → route: exit · final_1page

# 툴 인자: work_adapter=replay|claude(실제 sub agent 디스패치),
#         review_adapter=local|live|mcp(review_server 호출)
```

> 핵심: MCP 등록은 툴을 *호출 가능*하게 만든다. 메인 세션이 `run_meeting_to_1page` 한 콜을 하면
> 그 안에서 **코드(harness_v2_server)가 13개 node를 잠긴 순서로** 돌린다 — 순서·state·route를
> 모델이 정하지 않는다(결정론). 이게 graph 레벨에서 "툴이 LLM을 쓴다".

## 실제 sub agent 디스패치 (work_adapter=claude)

`invoke_runtime_adapter`의 work leaf(ask/build)를 replay 대신 실제 Claude Agent SDK sub agent로 띄운다.

```bash
python3 harness_v2_server.py run    --work-adapter claude --review-adapter local   # ask·build sub agent 실제 디스패치 → ask-back
python3 harness_v2_server.py resume --work-adapter claude --review-adapter mcp     # 실제 sub agent + MCP review → exit
```

- 각 sub agent 디스패치 직후 `review_gate.py` precheck로 검증한다(`subagent.ask.dispatched`/`subagent.build.dispatched` 이벤트). build이 precheck를 못 넘기면 1회 재디스패치 후 그래도 실패면 retry route.
- `review-adapter mcp`는 review를 등록된 MCP 서버(`review_server.py`)를 stdio MCP 프로토콜로 호출해 수행한다.

## 폴더 구조

```
2005-4-3-workspace/
├── harness_v2_server.py        # graph 엔진 + CLI (run / resume / review)
├── harness_mcp_server.py       # graph 전체를 MCP 툴로 노출 (run/resume)
├── review_server.py            # review_parallel MCP node 예시 (Claude Agent SDK ∥ codex exec)
├── mcp_client_demo.py          # MCP 클라이언트 데모 (spawn → tools/list → call)
├── setup-readonly-sources.sh   # 4장·3장 read-only 복사 (원본 미수정)
├── runtime-independent-harness-demo.md  # 산출물 템플릿(§1-7) 실측 채움
├── contracts/                  # frozen 계약
│   ├── requirements-contract.md  # 2장 통합 (reader/decision/success/constraints + 사각지대 질문)
│   └── spec-contract.md          # 3장 통합 (7칸 output contract + 고정·남김·질문 + review_gate profile)
├── .claude/                    # Claude runtime surface (3장 계승: 메타/도메인 스킬, SessionStart, PostToolUse→precheck)
├── .codex/                     # Codex runtime surface (4장 계승: agents/skills, review_gate.py, session_start)
├── inputs/                     # read-only 시작 입력 (4장 복사) + answers.md(ask-back 회신)
├── source/                     # 4장 실제 산출 (replay adapter 소스 뱅크, read-only)
├── handoffs/                   # v2가 생성: L1/build/L5/L6 handoff + final-1page-draft
└── state/                      # run-log.md(append-only) + run-log.jsonl + checkpoints/ + pending-external
```

## 2~4장 통합 방식 (직접 재실행하지 않음)

| 출처 | 통합 위치 |
|---|---|
| 2장 인터뷰 하네스 (Seed: goal/constraints/acceptance, 사각지대 두 질문) | `contracts/requirements-contract.md` → requirements_contract node. ask-back의 requirements_update가 사각지대 채택 구조를 씀 |
| 3장 회의록→1page 명세 (7칸, 고정·남김·질문, 보존 표현) | `contracts/spec-contract.md` → spec_contract node |
| 3장 SessionStart hook / using-1page-harness / meeting-to-1page / check-1page-output | `../../../../../.claude` session surface + review_gate precheck (session_surface node) |
| 4장 inputs/ handoffs/ state/ review_gate.py | `inputs` `source` `.codex/hooks/review_gate.py` 복사. review_gate.py는 실제 subprocess로 호출 |
| 4-1 Ouroboros 원칙 | frozen contract / append-only run-log / checkpoint·rewind / 3단 evaluation gate / regression guard / satisfice exit (runtime-independent-harness-demo.md 부록 A) |

자세한 데모 결과는 `runtime-independent-harness-demo.md` 참고.
