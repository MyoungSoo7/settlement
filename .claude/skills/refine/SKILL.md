---
name: refine
description: |
  Reflect에서 드러난 서로 다른 의미를 사용자가 동의하는 shared meaning 한 줄로 합친다. Socrates 루프에서는 현재 Cycle의 ### Refine 섹션만 채운다.
  Triggers (KO): refine, /refine, shared meaning 만들어줘, 의미 합쳐줘
  Triggers (EN): refine, create shared meaning, merge meanings
  Do NOT use when: 의미 후보를 더 캐야 할 때 (-> wonder), 의미 차이를 먼저 확인해야 할 때 (-> reflect), goal 한 줄로 재진술해야 할 때 (-> restate), 전체 Seed 루프가 필요할 때 (-> socrates)
  vs reflect: reflect는 차이를 드러내고, refine은 사용자가 동의한 하나의 shared meaning으로 합친다.
---

# Refine

## 상세 설명

Reflect 결과를 바탕으로 사용자와 모델 사이의 의미 차이를 하나의 합의 문장으로 좁히는 단계다. 결과는 현재 cycle의 `### Refine`에 저장한다.

## 역할 — 소크라테스의 질문
> "이 두 의미를 같은 의미로 합친다면, 어떤 한 줄이 본인 의도에 가장 가깝습니까?"
> "여기 빠진 결은 없습니까? 더하거나 빼야 할 단어가 있다면 무엇입니까?"
> "이 한 줄을 본인이 직접 다른 사람에게 설명한다고 했을 때 그대로 쓸 수 있습니까?"

이 질문은 모두 AskUserQuestion 도구로 사용자에게 던진다. 모델이 합의문을 임의 작성해 본문에 자유 서술하지 않는다. 같은 cycle의 `### Reflect` 섹션에 있는 짝 비교를 입력으로 두고, 사용자가 직접 한 줄로 합치도록 묻는다.

## 원칙
1. 한 번에 한 합의 후보만 묻는다.
2. 사용자의 단어 선택을 평가하지 않는다.
3. 사용자가 동의하지 않으면 추측으로 합치지 않는다 — 같은 의미가 보일 때까지 다시 묻는다.

## 출력 형식
한 줄짜리 shared meaning 정의문 한 개.

```
shared meaning: {{한 문장으로 합쳐진 정의}}
```

## 저장 위치
`.claude/scratch/socrates.md`의 현재 `## Cycle N` 아래 `### Refine` 섹션에 위 한 줄을 기록한다. 사용자 동의가 떨어지면 저장하고 종료, 다음은 `/restate`로 넘긴다. 입력은 같은 cycle의 `### Reflect` 섹션을 읽어 가져온다.
