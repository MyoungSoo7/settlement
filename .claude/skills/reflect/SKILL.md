---
name: reflect
description: |
  Wonder에서 나온 의미 후보와 사용자 의도 사이의 차이를 한 짝씩 비춰서 깨닫게 한다. Socrates 루프에서는 현재 Cycle의 ### Reflect 섹션만 채운다.
  Triggers (KO): reflect, /reflect, 의미 차이 짚어줘, 내가 뜻한 것과 다른 점 봐줘
  Triggers (EN): reflect, compare meanings, surface meaning gap
  Do NOT use when: 의미 후보를 더 캐야 할 때 (-> wonder), 차이를 하나의 shared meaning으로 합쳐야 할 때 (-> refine), goal로 재진술해야 할 때 (-> restate), 전체 Seed 루프가 필요할 때 (-> socrates)
  vs wonder: wonder는 후보를 늘리고, reflect는 후보와 사용자 의도 사이의 같은 점/다른 점을 확인한다.
---

# Reflect

## 상세 설명

Wonder가 만든 의미 후보를 입력으로 받아 사용자가 실제로 떠올린 의미와 모델이 읽은 의미의 차이를 확인하는 단계다. 결과는 현재 cycle의 `### Reflect`에 저장한다.

## 역할 — 소크라테스의 질문
> "제가 이해한 의미는 X인데, 본인이 떠올린 의미와 같습니까?"
> "어디가 같고 어디가 다릅니까?"
> "혹시 제가 놓친 결이 있다면 무엇입니까?"

이 질문은 모두 AskUserQuestion 도구로 사용자에게 던진다. 모델이 차이를 임의로 해석해 본문에 자유 서술하지 않는다. 같은 cycle의 `### Wonder` 섹션에 있는 의미 후보 각각에 대해 사용자 본인의 의미와 모델의 의미를 짝지어 비춘다.

## 원칙
1. 한 번에 한 짝(사용자 의미 vs 모델 의미)만 비춘다.
2. 차이를 평가하거나 어느 쪽이 맞다고 판단하지 않는다.
3. 사용자가 명시하지 않은 의미를 추측으로 채우지 않는다 — 다시 묻는다.

## 출력 형식
짝지은 차이 표 또는 리스트.

- 의미 A
  - 사용자가 떠올린 결: …
  - 모델이 떠올린 결: …
  - 같은 점 / 다른 점: …

## 저장 위치
`.claude/scratch/socrates.md`의 현재 `## Cycle N` 아래 `### Reflect` 섹션에 위 짝 비교를 기록한다. 의미마다 차이가 명시되면 저장하고 종료, 다음은 `/refine`으로 넘긴다. 입력은 같은 cycle의 `### Wonder` 섹션을 읽어 가져온다.
