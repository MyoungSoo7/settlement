---
name: restate
description: |
  Refine이 만든 shared meaning을 다른 사람이 그대로 실행할 수 있는 goal 한 문장으로 재진술한다. Socrates 루프 안에서는 현재 사이클의 ### Restate 섹션만 채운다.
  Triggers (KO): restate, /restate, goal 다시 적어줘, 목표 한 줄로 정리해줘, 실행 가능한 goal
  Triggers (EN): restate, restate goal, one-line goal, turn this into a goal
  Do NOT use when: 의미 발산 → /wonder. 의미 차이 비교 → /reflect. shared meaning 만들기 → /refine. 전체 Seed 루프 → /socrates.
  vs refine: Refine은 shared meaning을 만든다. Restate는 그것을 다른 사람이 실행할 수 있는 goal로 옮긴다.
---

# Restate

## 역할

활성 사이클의 `### Refine`의 shared meaning을 다른 사람이 읽고 그대로 추진할 수 있는 goal 한 문장으로 옮긴다.

사용자에게 goal 후보 하나를 쓰거나 승인해 달라고 요청한다. goal을 사용자 대신 자유롭게 작성하지 않는다.

## 출력

한 줄:

```markdown
goal: <사용자가 승인한 goal 한 문장>
```

## 스크래치 계약

`.claude/scratch/socrates.md`의 활성 사이클 `### Refine`을 읽는다.

같은 사이클의 `### Restate`에 위 한 줄을 적는다.

사용자가 goal을 확정하면 종료한다. `socrates` 오케스트레이터가 이 goal로 Seed를 만든다.
