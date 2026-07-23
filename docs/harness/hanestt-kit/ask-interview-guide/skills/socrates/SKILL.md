---
name: socrates
description: |
  Wonder → Reflect → Refine → Restate를 Ralph 루프로 묶어 막연한 요청 한 줄을 닫힌 Seed(goal · constraints · acceptance_criteria)로 만든다. 최신 Seed는 .claude/scratch/socrates.md의 최상위 ## Seed 섹션에 저장된다.
  Triggers (KO): 소크라테스, /socrates, Seed 만들어줘, 요청 정리해줘, 막연한 요청 닫아줘
  Triggers (EN): socrates, run socrates, create seed, clarify vague request, close this request into a seed
  Do NOT use when: 이미 Seed가 있고 사각지대만 점검 → /evolve-step. ontology 수렴까지 → /interview-harness. 의미 발산 한 단계만 → /wonder.
  vs interview-harness: Socrates는 Seed를 만든다. Interview Harness는 그 뒤로 evolve-step과 ontology까지 간다.
---

# Socrates

## 역할

이 스킬은 4개 sub 스킬(`wonder`, `reflect`, `refine`, `restate`)을 한 사이클로 묶어 호출한다. 내부 흐름을 다시 적지 않는다.

## 스크래치 계약

`.claude/scratch/socrates.md`를 사용한다. 사이클은 누적된다.

```markdown
## User Input
<원문 그대로>

## Cycle 1
### Wonder
...
### Reflect
...
### Refine
shared meaning: ...
### Restate
goal: ...
```

최신 Seed는 항상 최상위 섹션으로 둔다.

````markdown
## Seed
> Source: /socrates Cycle N

```yaml
goal: ...
constraints:
  - ...
acceptance_criteria:
  - ...
```
````

매번 닫힌 Seed가 나올 때 최상위 `## Seed` 섹션을 통째로 교체한다. 누적하지 않는다.

## Seed Gate

매 사이클 끝에 점검:

- `goal`: 이번 사이클의 `### Restate`에 goal 한 줄이 있는가.
- `constraints`: 사용자가 말한 제약 또는 경계가 한 개 이상 있는가.
- `acceptance_criteria`: 검증 가능한 기준이 한 개 이상 있는가.

세 칸이 모두 채워졌고 사용자가 "이번 사이클의 goal이 직전 사이클과 실질적으로 다르지 않다"고 답하면 종료.

빈 칸이 있으면 다음 Wonder 질문을 그 칸 쪽으로 좁힌다.

- goal 누락 → "이 요청에서 실제로 얻고 싶은 결과가 무엇인가?"
- constraints 누락 → "절대 일어나면 안 되는 것 또는 넘으면 안 되는 경계가 있는가?"
- acceptance_criteria 누락 → "이 결과가 충분히 좋다고 판단할 기준은 무엇인가?"

빈 칸을 추측으로 채우지 않는다.

## 안전밸브

최대 5사이클. 5사이클 후에도 채워지지 않은 칸이 있으면 `empty`로 표기하고 종료.

## 출력

최종 출력은 최상위 `## Seed`의 YAML 블록이다.
