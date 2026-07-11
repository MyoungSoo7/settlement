---
name: interview-harness
description: |
  막연한 요청 한 줄을 받아 Socrates + Evolve-step + Ontology를 Ralph 루프로 묶어, 사용자 확인을 포함한 반자동 루프로 ontology 유사도가 0.85 이상 수렴할 때까지 돌려 닫힌 Seed를 산출한다. socrates.md에 Seed가 이미 있으면 Socrates 단계를 건너뛴다(Seed Gate).
  Triggers (KO): interview-harness, /interview-harness, 하네스로 돌려줘, 인터뷰 하네스, seed까지 돌려줘
  Triggers (EN): interview harness, run interview harness, converge seed, socratic harness
  Do NOT use when: 단일 의미 발산만 필요할 때 (-> wonder), Seed만 만들고 진화/ontology가 필요 없을 때 (-> socrates), 이미 있는 Seed의 사각지대만 검토할 때 (-> evolve-step), Seed 위에 ontology만 얹을 때 (-> ontology)
  vs socrates: socrates는 Seed 생성까지만 담당하고, interview-harness는 Seed Gate 이후 evolve-step + ontology 수렴 루프까지 오케스트레이션한다.
---

# Interview Harness — Ralph Loop으로 Seed까지

## 상세 설명

막연한 요청을 닫힌 Seed로 수렴시키는 마스터 하네스다. `/socrates`로 Seed를 만들거나 기존 Seed를 재사용하고, `/evolve-step`으로 사각지대를 반영한 뒤, `/ontology`로 의미 경계를 확인한다. 각 사이클에는 사용자 채택과 boundary 판단이 포함되므로 완전 자동이 아니라 사용자 확인을 포함한 반자동 루프다.

## 역할

sub 스킬 셋(`/socrates`, `/evolve-step`, `/ontology`)을 한 사이클로 묶고, 사용자 확인을 거쳐 ontology 유사도가 임계값(0.85)을 넘을 때까지 사이클을 반복한다. sub 스킬의 본문을 다시 적지 않고 **호출만** 한다. sub 스킬 단독 호출(`/socrates`, `/evolve-step`, `/ontology`)은 그대로 동작한다.

## 입력

사용자의 막연한 요청 한 줄(vagueness input). 예: "task 관리 CLI 만들고 싶다", "보고서 좀 만들어 줘".

## 컴포넌트

- **Socrates**: `/socrates` — Seed가 비어 있을 때만 호출. Wonder/Reflect/Refine/Restate를 돌려 Seed(goal + constraints + acceptance_criteria)를 만든다.
- **Evolve-step**: `/evolve-step` — 매 사이클 호출. 사각지대 두 질문으로 Seed v_n → Seed v_(n+1).
- **Ontology**: `/ontology` — 매 사이클 끝에 호출. Seed.goal을 단어로 보고 boundary + properties 3개를 같이 정한다 (idea는 Seed.goal에서 자동 추출, action은 묻지 않음).

## 공유 스크래치 파일

- `.claude/scratch/socrates.md` — canonical 파이프라인. **최신 Seed 한 벌 + 최신 Ontology 한 벌만 유지**. `/socrates`, `/evolve-step`, `/ontology`가 공유한다.
- `.claude/scratch/evolve-step.md` — `/evolve-step`이 사이클별 사각지대 Q&A audit을 누적.
- `.claude/scratch/interview-harness.md` — 마스터 하네스 전용. 매 사이클 `/ontology` 호출 직전 직전 ontology를 스냅샷으로 저장하고, 호출 후 새 ontology와 비교한다. socrates.md가 최신 한 벌만 유지하므로 비교용 보존은 이 파일이 책임진다.

## 전체 흐름

```
[vagueness input]
   ↓
/interview-harness
   ↓
┌──────────────── Stage 1: Seed 생성 (한 번만) ────────────────┐
│  Seed Gate 점검:                                            │
│    socrates.md의 ## Seed에 goal + constraints +             │
│    acceptance_criteria 세 칸이 다 있나?                     │
│      YES → Socrates 단계 스킵 (기존 Seed 그대로 사용)       │
│      NO  → /socrates 호출 (Wonder/Reflect/Refine/Restate)   │
│            → socrates.md의 ## Seed 채움                     │
└─────────────────────────────────────────────────────────────┘
   ↓
┌──────────────── Stage 2: 수렴 루프 (1~5회) ─────────────────┐
│  매 사이클:                                                 │
│    1. /ontology 호출 직전 — 현재 ## Ontology를              │
│       interview-harness.md에 직전값으로 스냅샷 저장          │
│       (첫 사이클은 직전값이 없으므로 스킵)                   │
│    2. /evolve-step 호출 → Seed v_(n+1)                      │
│       (socrates.md ## Seed 통째 교체, evolve-step.md에 audit)│
│    3. /ontology 호출 → ontology v_(n+1)                     │
│       (socrates.md ## Ontology 통째 교체)                   │
│    4. Stop rule 점검:                                       │
│       - 직전 ontology vs 이번 ontology 유사도 ≥ 0.85        │
│         → stop_reason = "convergence", 종료                 │
│       - cycle > 5                                           │
│         → stop_reason = "safety_valve", 종료                │
│       - 첫 사이클(직전값 없음)은 비교 스킵, 다음 사이클로    │
└─────────────────────────────────────────────────────────────┘
   ↓
[output: final_seed YAML + cycles + final_similarity + stop_reason]
```

## Stage 1 — Seed Gate

`.claude/scratch/socrates.md`를 읽는다.

- 파일이 없거나, 최상위 `## Seed` 섹션이 없거나, 섹션 안의 YAML에서 `goal` / `constraints` / `acceptance_criteria` 중 하나라도 비어 있거나 `empty`이면:
  → `/socrates` 호출. Seed를 만든 뒤 Stage 2로 넘어간다.
- 세 칸이 모두 채워져 있으면:
  → Socrates 스킵. 그대로 Stage 2로 넘어간다.

이 게이트가 있어서, 같은 vagueness input을 여러 번 다듬는 중간에 다시 `/interview-harness`를 호출해도 Seed를 처음부터 다시 만들지 않는다.

## Stage 2 — 수렴 루프

### 한 사이클

1. **직전 ontology 스냅샷** — `/ontology`를 호출하기 전에 socrates.md의 현재 `## Ontology` 섹션을 읽어 `.claude/scratch/interview-harness.md`에 `## Previous Ontology (cycle {{n-1}})` 블록으로 저장한다. 첫 사이클이라 `## Ontology`가 아예 없으면 스킵.
2. **`/evolve-step` 호출** — Seed v_n → Seed v_(n+1). 사각지대 두 질문 + 사용자 채택. socrates.md의 `## Seed` 섹션이 새 Seed로 통째 교체된다. evolve-step.md에 audit 블록이 append된다.
3. **`/ontology` 호출** — 새 Seed.goal을 idea로, boundary + properties 3개를 사용자에게 받아 socrates.md의 `## Ontology` 섹션을 통째 교체한다.
4. **Stop rule 점검** (아래).

### Stop Rule

다음 둘 중 하나면 종료.

**수렴 (semantic similarity ≥ 0.85)**

직전 ontology(`interview-harness.md`에 저장해 둔 스냅샷) vs 이번 ontology(`socrates.md`의 새 `## Ontology`)를 세 항목으로 비교.

- **idea**: 같은 문자열이면 1점, 다르면 0점. (idea는 Seed.goal 그대로이므로 Seed.goal이 바뀌지 않으면 자동 1점)
- **boundary**: 같은 의미면 1점, 다르면 0점. 반드시 AskUserQuestion으로 사용자에게 "직전 boundary와 이번 boundary가 같은 의미인가요?"를 묻고, 사용자가 같다고 답한 경우에만 1점으로 둔다.
- **properties**: overlap 비율. 두 properties 배열의 교집합 크기 / 합집합 크기 (Jaccard). 예: 3개 중 2개 같으면 2/4 = 0.5. (배열 길이가 같고 정확히 동일하면 1.0)
- **총점 = (idea + boundary + properties) / 3**
- 총점 ≥ 0.85 → `stop_reason = "convergence"`, 종료.

첫 사이클(직전 ontology 없음)은 비교 자체를 스킵하고 무조건 다음 사이클로 넘어간다.

**안전밸브 (Cycle > 5)**

사이클 카운터가 5를 넘으면 강제 종료. 현재 Seed를 그대로 반환. `stop_reason = "safety_valve"`.

### `interview-harness.md` 저장 형식

```markdown
# Interview Harness — 사이클 추적

## 사용자 입력
{{vagueness input 한 줄}}

## Previous Ontology (cycle 1)
```yaml
ontology:
  idea: ...
  boundary: ...
  properties:
    - ...
    - ...
    - ...
```

## Cycle 2 비교
- idea: 1
- boundary: 0
- properties overlap: 2/4 = 0.5
- 평균: (1 + 0 + 0.5) / 3 ≈ 0.50
- decision: 미수렴, 다음 사이클로

## Previous Ontology (cycle 2)
...
```

`## Previous Ontology` 블록은 매 사이클 직전 ontology로 갱신(통째 교체)하고, `## Cycle N 비교` 블록은 사이클별로 append한다 (audit).

## 출력

루프가 끝나면 socrates.md의 최신 `## Seed` + `## Ontology`를 묶어 다음 형식으로 한 번 출력한다.

```yaml
final_seed:
  goal: <한 줄>
  constraints:
    - ...
  acceptance_criteria:
    - ...
  ontology:
    idea: <Seed.goal 그대로>
    boundary: <한 줄>
    properties:
      - ...
      - ...
      - ...

cycles: <N>
final_similarity: <0.0~1.0>
stop_reason: <"convergence" | "safety_valve">
```

## 원칙

1. **호출만 한다.** sub 스킬의 질문 본문이나 흐름을 흉내내지 않는다.
2. **socrates.md는 최신 한 벌.** Seed 한 벌 + Ontology 한 벌만 유지(누적 X). 비교용 직전 ontology는 `interview-harness.md`가 책임진다.
3. **Seed Gate.** Seed가 이미 있으면 Socrates를 스킵한다. 같은 입력을 다시 다듬지 않는다.
4. **수렴 임계값은 0.85 고정.** 세 항목 평균이 0.85 이상이면 stop.
5. **Cycle > 5 안전밸브 절대 넘지 않는다.**
6. **사용자 채택 없이 자동 채움 금지.** `/evolve-step`의 사각지대 채택은 사람이 한다.
7. **boundary 동일성은 사용자에게 확인한다.** 유사도 계산에서 모델이 boundary 의미 동일성을 단독 판정하지 않는다.
8. **sub 스킬 단독 호출이 그대로 동작해야 한다.** 하네스가 단독성을 깨면 안 된다.

## 완료 기준

**오케스트레이션**
- `/interview-harness` 한 번 호출로 Seed Gate → (필요시 Socrates) → 1~5 사이클의 evolve+ontology 루프가 돌고 닫힌 Seed가 떨어진다.
- 마스터 하네스가 sub 스킬을 호출만 하고 본문을 흉내내지 않는다.

**중간 결과 관리**
- socrates.md는 매 사이클 통째 교체되어 최신 Seed + 최신 Ontology만 남는다.
- `interview-harness.md`에 직전 ontology 스냅샷이 저장되어 있고, 매 사이클 비교 후 갱신된다.
- `evolve-step.md`에 사이클별 audit 로그가 누적된다.

**Stop rule 및 출력**
- 수렴 0.85 + 안전밸브 Cycle > 5 둘 다 명시되어 있다.
- 출력이 `final_seed` + `cycles` + `final_similarity` + `stop_reason`을 포함한다.

**회귀**
- `/socrates`, `/evolve-step`, `/ontology` 단독 호출이 여전히 동작한다.

## 다음 단계

이 마스터 하네스가 떨어뜨린 final_seed(goal + constraints + acceptance_criteria + ontology)는 3장의 입력이 된다. 3장에서는 이 Seed를 실행 하네스(execute · evaluate · evolve)로 옮겨 코드 단계로 진입한다.
