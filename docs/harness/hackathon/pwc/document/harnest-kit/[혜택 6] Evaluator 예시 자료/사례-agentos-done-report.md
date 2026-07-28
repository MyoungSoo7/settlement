# 사례 — AgentOS: "완료 보고는 증거가 아니다"

결정론 게이트가 실제로 무엇을 가르는지 보여주는 참조 사례다. 본인 Evaluator를 설계할 때 "AI의 '완료했다'를 그대로 믿지 않는다"가 어떤 모양인지 가져온다.

> Source
> - 공개 발표 자료: https://wpti.dev/public-presentation ("Context to Contract — AgentOS" 덱)
> - Ouroboros v0.39.0 릴리스: https://github.com/Q00/ouroboros/releases/tag/v0.39.0 (2026-05-18, AgentOS Phase 1)
>
> 아래는 설치·성능·벤치마크 주장이 아니라 게이트 설계 개념의 참조 렌즈로만 쓴다.

---

## 1. 버그 — Done Report

발표가 여는 문제는 단순하다. **에이전트는 "완료했다"고 말할 수 있다. 그 말 자체는 증거가 아니다.**

| 항목 | 에이전트가 내민 것 |
|---|---|
| agent says | done |
| changed files | unknown (어떤 파일이 바뀌었는지 안 묶임) |
| test output | not bound (테스트 결과가 결과에 연결 안 됨) |
| AC verdict | self-reported (스스로 통과라고 말함) |
| **system action** | **reject** |

"done"이라는 보고만 있고 그 done을 떠받칠 증거가 없으면, AgentOS는 통과시키지 않고 거절한다. 완료 보고는 결론이 아니라 검증의 입력일 뿐이다.

## 2. Verification Boundary — 셋을 따로 다룬다

완료 보고를 입력으로만 보고, 완료 여부는 다른 면(surface)에서 확정한다. AgentOS는 셋을 분리한다 — **실행 증거 · verifier 판정 · replay 기록**.

```text
[Planning surface]
  Seed Contract (goal · AC · constraints)
        → AC Decomposition (profile-aware leaves)
              → Agent Runtime (tool execution · claim)
                                         │
                                    evidence only
                                         ▼
[Verification surface]
  Evidence Manifest (files · commands · artifacts)
        → Verifier (checks)
              → Store (verdict log)
```

핵심 한 줄: **Claim은 Agent Runtime 안에서만 유효한 상태**다. 완료 여부는 `Evidence Manifest → Verifier → Store` 순서로 확정된다. 검증 면으로는 claim이 아니라 evidence만 건너간다.

## 3. 완료의 정의가 바뀐다 — Execution Contract

| 면 | Before | v0.39.0 |
|---|---|---|
| AC 수용 | 에이전트 완료 텍스트 | verifier PASS + 형식 있는 증거 |
| 막힘 상태 | 자유 서술 설명 | 형식 있는 blocked 증거 |
| 기준선 | 사람이 매번 해석 | semantic-miss 지표 |

"다 했습니다"라는 텍스트를 완료로 받던 것을, "verifier가 PASS를 냈고 형식 있는 증거가 붙어 있다"로 바꿨다. 이것이 evidence 기반 결정론 verifier다.

## 4. 도메인마다 증거와 verifier가 다르다 — Profile-aware

같은 AC라도 도메인이 다르면 무엇을 증거로 보고 무엇으로 검증할지가 달라진다.

| profile | 증거(evidence) | verifier |
|---|---|---|
| code | file_change + test_command | structural + command_success |
| research | cited_claim + source_trace | citation_consistency |

본인 업무의 증거가 무엇인지부터 정해야 verifier를 만들 수 있다.

## 5. 증거가 있어도 어긋나면 거절한다 — Semantic Miss

verifier(TraceGuard)는 intent와 evidence를 **따로 읽은 뒤 claim-term guard로 대조**한다.

```text
intent 채널    Acceptance Criteria (기대 용어) → Deliver Claim (완료 주장)
evidence 채널  Run Artifacts (파일·명령·출력) → Evidence Facts (정규화 manifest)
                                  └──→ claim-term guard 대조 ──→ Verdict
```

증거가 아예 없는 경우만 거절하는 게 아니다. **증거가 있어도 그 증거가 AC 용어와 어긋나면 semantic miss로 판정**한다. "했다고 말한 것"과 "실제로 남긴 것"이 같은 대상을 가리키는지를 본다.

## 6. 본인 업무로 옮기면

AgentOS를 그대로 쓰자는 게 아니다. 설계 원리 세 가지를 본인 Evaluator에 옮긴다.

- **완료 보고와 증거를 같은 칸에 두지 않는다.** AI의 "다 했어요"(claim)와 실제 산출물(evidence)을 따로 적는다.
- **검증 면으로는 증거만 보낸다.** 판정은 claim이 아니라 evidence manifest를 기준으로 한다. claim은 검증의 입력일 뿐이다.
- **판정은 결정론으로.** 같은 결과를 두 번 넣어 같은 단어가 나오게 한다. 사람이 문장을 읽고 느낌으로 통과시키지 않는다.

이 세 가지를 `evaluator.template.md`의 3단 검수(형식·의미·목적)에 녹인다. 특히 "의미" 단계가 claim-term guard에 해당한다 — 결과가 주장하는 것과 증거가 가리키는 것이 같은 대상인지 보는 자리다.
