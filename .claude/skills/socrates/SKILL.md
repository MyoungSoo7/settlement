---
name: socrates
description: |
  막연한 요청 한 줄을 받아 Wonder/Reflect/Refine/Restate를 Ralph 루프로 돌려 닫힌 Seed(goal + constraints + acceptance_criteria) 한 묶음을 산출한다. 결과 Seed는 .claude/scratch/socrates.md의 최상위 ## Seed 섹션에 최신 한 벌로 저장한다.
  Triggers (KO): socrates, /socrates, 소크라테스, seed 만들어줘, 막연한 요청 정리
  Triggers (EN): socrates, run socrates, create seed, clarify vague request
  Do NOT use when: 이미 Seed가 있고 사각지대만 검토할 때 (-> evolve-step), Seed와 ontology 수렴까지 필요할 때 (-> interview-harness), 의미 발산 한 단계만 필요할 때 (-> wonder)
  vs interview-harness: socrates는 Seed 생성까지만 담당하고, interview-harness는 Seed 생성 후 evolve-step + ontology 수렴 루프까지 담당한다.
---

# Socrates — 5단 묻기 오케스트레이터 (Ralph 루프)

## 상세 설명

Wonder, Reflect, Refine, Restate를 순서대로 호출해 사용자의 막연한 요청을 Seed YAML로 닫는다. 각 단계는 `.claude/scratch/socrates.md`의 현재 `## Cycle N` 아래 `### Wonder`/`### Reflect`/`### Refine`/`### Restate`에 기록하고, 최종 Seed는 최상위 `## Seed` 섹션에 최신 한 벌로 저장한다.

## 역할
네 스킬(Wonder · Reflect · Refine · Restate)을 한 사이클로 묶고, Seed의 세 칸이 다 채워질 때까지 사이클을 반복한다. 네 스킬의 본문은 다시 적지 않고 호출만 한다. 단독 호출(`/wonder` 등)은 그대로 동작한다.

## 입력
사용자의 막연한 요청 한 줄.

## 공유 스크래치 파일
`.claude/scratch/socrates.md` 한 개. 모든 사이클이 같은 파일에 누적된다. 단, 최종 Seed와 Ontology는 최상위 섹션에 최신 한 벌만 둔다.

초기화: 파일이 없으면 만든다. 있으면 그 아래에 이번 호출의 블록을 이어 붙인다.

파일 구조 (사이클 단위로 반복):

```
## 사용자 입력
{{한 줄 원문}}

## Cycle 1
### Wonder
- …
### Reflect
- …
### Refine
shared meaning: …
### Restate
goal: …

## Cycle 2
…
```

마지막에는 최상위 `## Seed` 섹션을 최신 Seed YAML 한 블록으로 통째 교체한다.

최상위 canonical 섹션:

````markdown
## Seed
> 출처: /socrates Cycle {{n}}

```yaml
goal: ...
constraints:
  - ...
acceptance_criteria:
  - ...
```

## Ontology
...
````

## 한 사이클
1. **Wonder 호출** — `/wonder` 스킬을 호출한다. 이번 사이클의 Wonder 질문은 직전 사이클에서 비어 있던 칸 방향으로 좁힌다 (아래 Ralph 루프 규칙 참조).
2. **Reflect 호출** — `/reflect` 스킬을 호출한다. 입력은 방금 채워진 `### Wonder` 섹션.
3. **Refine 호출** — `/refine` 스킬을 호출한다. 입력은 `### Reflect` 섹션.
4. **Restate 호출** — `/restate` 스킬을 호출한다. 입력은 `### Refine` 섹션.
5. **Seed 게이트 점검** (아래) → 통과면 종료, 아니면 다음 사이클로.

각 sub-skill은 현재 `## Cycle N` 아래 자기 `###` 섹션만 채운다. 오케스트레이터는 sub-skill 본문을 다시 적지 않고 차례로 호출만 한다.

## Seed 게이트
사이클이 끝날 때마다 다음 세 칸을 점검한다.

- **goal** — 현재 사이클의 `### Restate` 섹션에 한 줄이 들어 있나?
- **constraints** — 기술적 제한 또는 범위 차단이 한 개 이상 적혀 있나? (예: "한 페이지를 넘기지 않는다", "외부 사실 인용 금지")
- **acceptance_criteria** — 행동 동사로 끝나는 검증 기준이 한 개 이상 적혀 있나? (예: "임원이 추가 질문 없이 결정으로 넘어간다")

세 칸이 다 차 있고, 직전 사이클의 `### Restate`와 비교해 큰 차이가 없으면 (사용자 본인 판단으로 같은 의미라고 인정하면) 수렴 → 종료.

한 칸이라도 비었으면 다음 사이클로 넘어간다.

## Ralph 루프 규칙
- 다음 사이클의 Wonder 질문을 **비어 있는 칸 방향**으로 좁힌다.
  - goal이 비었다 → "이 요청으로 본인이 정말 얻고 싶은 결과 한 줄은 무엇입니까?"
  - constraints가 비었다 → "이 일에서 하지 말아야 할 것, 넘지 말아야 할 선은 무엇입니까?"
  - acceptance_criteria가 비었다 → "이 결과가 본인이 원한 결과라고 판단하는 기준은 무엇입니까?"
- 같은 칸을 두 사이클 연속 못 채우면 사용자에게 다시 묻는다. 추측으로 채우지 않는다.
- 빈칸을 모델이 자유 서술로 메우지 않는다. 모든 칸은 사용자 본인 답변에서 나온다.

## 안전밸브
- 최대 5사이클.
- 5사이클을 돌고도 안 채워진 칸이 있으면 그 칸은 `empty`로 명시하고 종료한다.

## 루프 구조 (개념)

```python
cycle = 0
while True:
    if seed_gate_pass():       # goal & constraints & acceptance_criteria 다 차고 직전 사이클과 큰 차이 없음
        return seed_yaml()
    if cycle >= 5:
        return seed_yaml(mark_empty=True)
    cycle += 1
    wonder(narrow_to=empty_slot())   # 비어 있는 칸 방향으로 질문 좁힘
    reflect()
    refine()
    restate()
```

## 출력
공유 스크래치 파일의 최상위 `## Seed` 섹션을 Seed YAML 한 블록으로 통째 교체한다. 섹션이 없으면 새로 만든다.

```yaml
goal: <한 줄>
constraints:
  - <조건 1>
  - <조건 2>
acceptance_criteria:
  - <행동 동사로 끝나는 검증 기준 1>
  - <행동 동사로 끝나는 검증 기준 2>
```

세 칸 중 끝까지 안 채워진 칸이 있으면 그 칸 값에 `empty`라고 적는다. 추측으로 채우지 않는다.

## 원칙
1. 네 스킬의 본문을 다시 적지 않는다. 차례로 호출만 한다.
2. 빈칸을 추측으로 메우지 않는다. 같은 칸이 두 번 비면 사용자에게 다시 묻는다.
3. 5사이클 안전밸브를 넘기지 않는다.
4. 단독 호출(`/wonder`, `/reflect`, `/refine`, `/restate`)이 그대로 동작해야 한다. 이 오케스트레이터는 차례 호출만 더한다.

## 완료 기준
- `/socrates` 한 번 호출로 Seed YAML 한 블록이 떨어진다.
- 사이클이 1~5회 도는 동안 빈 칸이 채워지는 모습이 스크래치 파일에 누적된다.
- Seed의 세 칸 중 끝까지 안 채워진 게 있으면 `empty`로 명시되어 있다.
