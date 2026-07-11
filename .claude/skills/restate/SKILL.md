---
name: restate
description: |
  Refine 단계에서 만든 shared meaning을 토대로 사용자 goal을 한 줄로 다시 진술한다. Socrates 루프에서는 현재 Cycle의 ### Restate 섹션만 채운다.
  Triggers (KO): restate, /restate, goal 다시 적어줘, 목표 한 줄로 정리해줘
  Triggers (EN): restate, restate goal, one-line goal
  Do NOT use when: 의미 후보를 더 캐야 할 때 (-> wonder), 의미 차이를 확인해야 할 때 (-> reflect), shared meaning을 만들어야 할 때 (-> refine), 전체 Seed 루프가 필요할 때 (-> socrates)
  vs refine: refine은 shared meaning을 만들고, restate는 그 shared meaning을 실행 가능한 goal 한 줄로 바꾼다.
---

# Restate

## 상세 설명

Refine이 만든 shared meaning을 다른 사람이 읽어도 같은 결과를 떠올릴 수 있는 goal 한 줄로 바꾸는 단계다. 결과는 현재 cycle의 `### Restate`에 저장한다.

## 역할 — 소크라테스의 질문
> "이 shared meaning을 그대로 두고, 본인이 원하는 결과를 한 문장 goal로 다시 적는다면 어떻게 됩니까?"
> "이 한 줄이면 다른 사람이 받아도 같은 결과를 떠올릴 수 있습니까?"
> "빠진 조건이나 범위가 있습니까? 있으면 어떤 단어를 더해야 합니까?"

이 질문은 모두 AskUserQuestion 도구로 사용자에게 던진다. 모델이 goal 문장을 자유 서술해 채우지 않는다. 같은 cycle의 `### Refine` 섹션에 있는 shared meaning을 입력으로 두고, 사용자가 직접 한 줄 goal을 적도록 묻는다.

## 원칙
1. 한 번에 한 goal 후보만 묻는다.
2. 사용자가 적은 단어를 평가하지 않는다.
3. shared meaning과 어긋난 goal은 통과시키지 않는다 — 어긋나면 다시 묻는다.

## 출력 형식
한 줄짜리 goal 문장 한 개.

```
goal: {{한 문장으로 다시 진술된 사용자 목표}}
```

## 저장 위치
`.claude/scratch/socrates.md`의 현재 `## Cycle N` 아래 `### Restate` 섹션에 위 한 줄을 기록한다. 사용자가 동의하면 저장하고 종료한다. 입력은 같은 cycle의 `### Refine` 섹션을 읽어 가져오고, 필요 시 `## 사용자 입력`의 원문과도 대조한다.
