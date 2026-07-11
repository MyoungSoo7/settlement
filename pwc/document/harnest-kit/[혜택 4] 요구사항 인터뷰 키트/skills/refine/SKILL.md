---
name: refine
description: |
  Reflect에서 드러난 의미 차이를 사용자 승인 shared meaning 한 줄로 합친다. Socrates 루프 안에서는 현재 사이클의 ### Refine 섹션만 채운다.
  Triggers (KO): refine, /refine, shared meaning 만들어줘, 의미 합쳐줘, 합의 의미
  Triggers (EN): refine, create shared meaning, merge meanings, consolidate intent
  Do NOT use when: 의미 발산 → /wonder. 의미 차이 비교 → /reflect. goal 재진술 → /restate. 전체 Seed 루프 → /socrates.
  vs reflect: Reflect는 차이를 드러낸다. Refine은 사용자가 채택한 차이를 한 줄로 합친다.
---

# Refine

## 역할

활성 사이클의 `### Reflect` 비교를 가져와 사용자에게 유용한 부분을 합쳐 한 줄 shared meaning을 만들 것을 요청한다.

shared meaning 후보를 한 번에 하나씩 제시한다. 사용자가 승인하지 않으면 계속 묻는다. 추측으로 합의 문장을 만들지 않는다.

## 출력

한 줄:

```markdown
shared meaning: <사용자가 승인한 한 문장>
```

## 스크래치 계약

`.claude/scratch/socrates.md`의 활성 사이클 `### Reflect`를 읽는다.

같은 사이클의 `### Refine`에 위 한 줄을 적는다.

사용자가 shared meaning을 승인하면 종료하고 `restate`로 넘긴다.
