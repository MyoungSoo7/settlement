---
name: evolve-step
description: |
  기존 Seed의 사각지대를 6개 후보(놓친 사용자 시각 3 + 잘려 나간 가능성 3)로 드러내고, 사용자가 채택한 후보만 반영해 다음 Seed를 만든다. 최신 Seed는 .claude/scratch/socrates.md의 최상위 ## Seed 섹션을 교체하고, audit 로그는 .claude/scratch/evolve-step.md에 누적된다.
  Triggers (KO): evolve-step, /evolve-step, Seed 진화, 사각지대 봐줘, Seed v1 v2
  Triggers (EN): evolve-step, evolve seed, seed blind spots, seed v1 v2, review blind spots
  Do NOT use when: 처음부터 Seed 만들기 → /socrates. ontology 수렴 → /interview-harness. 의미 경계만 정의 → /ontology.
  vs socrates: Socrates는 Seed v1을 만든다. Evolve-step은 기존 Seed를 사용자 승인 다음 Seed로 옮긴다.
---

# Evolve-step

## 역할

모델이 사각지대 후보를 제시하고, 사용자가 Seed에 들어갈 것을 결정한다. 모델은 자기가 만든 제안을 자동 채택하지 않는다.

## 입력

`.claude/scratch/socrates.md`의 최상위 `## Seed`에서 최신 Seed를 읽거나, 명시적 Seed YAML을 입력으로 쓴다:

```yaml
goal: ...
constraints:
  - ...
acceptance_criteria:
  - ...
```

## 절차

1. v1을 audit 로그에 그대로 보존.
2. 정확히 6개 후보 생성:
   - Q1: Seed에서 놓친 사용자 시각 3개.
   - Q2: Seed로 잘려 나간 가능성 3개.
3. 사용자에게 각 후보를 채택/기각으로 표시할 것을 요청.
4. 채택된 후보만 Seed v2에 반영.
5. `.claude/scratch/socrates.md`의 최상위 `## Seed`를 v2로 교체.
6. audit 블록을 `.claude/scratch/evolve-step.md`에 누적 기록.

## 사용자 채택 게이트

질문:

> 6개 후보 각각을 채택/기각으로 표시하세요. 기본값은 채택이 아닙니다.

사용자가 답하기 전에 v2를 도출하지 않는다.

채택된 후보가 0개면 v2 = v1이고 옮겨진 의미는 `No transferred meaning`.

## Audit 형식

```markdown
# Evolve-step — Cycle N

## Seed v1
```yaml
...
```

## Blind Spot Candidates
### Q1. 놓친 사용자 시각
- accepted / rejected — ...
- accepted / rejected — ...
- accepted / rejected — ...

### Q2. 잘려 나간 가능성
- accepted / rejected — ...
- accepted / rejected — ...
- accepted / rejected — ...

## Seed v2
```yaml
...
```

## Transferred Meaning
...
```

## 원칙

- audit의 v1은 그대로 보존.
- 채택된 후보만 v2에 반영.
- 사용자가 명시적으로 요청하지 않는 한, 한 후보가 Seed의 여러 칸을 동시에 바꾸지 않는다.
- 옮겨진 의미는 구체적으로 적는다: 어느 칸이 어떻게 바뀌었는가.
