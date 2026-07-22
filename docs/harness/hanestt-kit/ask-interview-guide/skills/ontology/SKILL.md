---
name: ontology
description: |
  닫힌 Seed 위에 의미 경계를 한 층 얹는다. Seed.goal을 idea로 두고, 사용자에게 boundary와 정확히 세 properties를 물어 .claude/scratch/socrates.md의 최상위 ## Ontology 섹션을 교체한다.
  Triggers (KO): ontology, /ontology, 의미 경계, idea boundary 정해줘, ontology 만들기
  Triggers (EN): ontology, create ontology, define semantic boundary, add meaning boundary
  Do NOT use when: Seed가 아직 없음 → /socrates. Seed 본문 변경 → /evolve-step. 전체 수렴 하네스 → /interview-harness.
  vs evolve-step: Evolve-step은 Seed를 바꾼다. Ontology는 Seed 위에 경계 층을 얹는다.
---

# Ontology

## 역할

Seed.goal이 무엇을 의미하고 그 의미가 어디서 멈추는지를 정의한다. action은 묻지 않는다(`acceptance_criteria`가 이미 action을 적고 있다).

## 입력

`.claude/scratch/socrates.md`의 최상위 `## Seed`에서 최신 Seed를 읽는다. Seed가 없으면 멈추고 사용자에게 `socrates`를 먼저 돌리라고 안내한다.

## Ontology 형식

```yaml
ontology:
  idea: <Seed.goal 그대로>
  boundary: <이 idea의 안과 밖이 어디서 갈리는지>
  properties:
    - <속성 1>
    - <속성 2>
    - <속성 3>
```

## 절차

1. `Seed.goal`을 `ontology.idea`에 그대로 복사.
2. boundary 한 질문: "이 idea의 의미는 어디서 시작해 어디서 끝나는가?"
3. 그 boundary 안의 정확히 3개 properties를 묻는다.
4. `.claude/scratch/socrates.md`의 최상위 `## Ontology` 섹션을 교체.

질문 도구: Codex Plan 모드에서는 `request_user_input`, Claude에서는 `AskUserQuestion`, 그 외에는 일반 대화형 질문.

## 원칙

- `idea`와 `boundary`는 서로 다른 내용이다.
- 모든 property는 boundary 안에 있어야 한다.
- properties는 정확히 3개.
- 데이터 타입을 묻지 않는다.
- action을 묻지 않는다.
- 사용자가 제공한 boundary와 property 용어만 YAML에 들어간다.

## 수렴 비교

`interview-harness`가 직전 ontology와 이번 ontology를 비교한다.

- idea: 동일하면 1, 아니면 0.
- boundary: 두 boundary가 같은 뜻인지 사용자가 확인해야만 1.
- properties: 두 속성 집합의 Jaccard overlap.

세 점수의 평균이 0.85 이상이면 수렴.
