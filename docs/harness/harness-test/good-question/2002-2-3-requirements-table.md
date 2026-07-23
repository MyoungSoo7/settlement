# 2002-2-3. Socrates 오케스트레이터 — Ralph 루프로 Seed까지

## Practice Task and Prompt

- Task: 2002-1-3에서 만든 네 스킬(`/wonder`, `/reflect`, `/refine`, `/restate`)을 한 사이클로 묶고, **Seed의 세 칸(goal + 제약 + 성공기준)이 다 채워질 때까지 사이클을 반복**하는 오케스트레이터 `../../../../.claude/skills/socrates/SKILL.md`를 만든다. 한 번의 호출로 막연한 요청에서 닫힌 Seed까지 간다.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2002-2-3을 도와줘.

Curriculum mapping:
- Module: 2-2. 요구사항 구조화 (Sub A goal + Sub B 제약·성공기준)
- Clip: [실습] Socrates 오케스트레이터 (Ralph 루프로 Seed 도달)
- Key message: 네 스킬을 한 흐름으로 묶고, 루프를 돌려 Seed의 세 칸을 다 채운다
- Required output: .claude/skills/socrates/SKILL.md 한 개

Reference:
- 4 sub skills (이미 만들어진 상태): .claude/skills/{wonder,reflect,refine,restate}/SKILL.md

Task:
한 사이클 = Wonder → Reflect → Refine → Restate 차례 호출.
사이클 끝날 때마다 Seed 세 칸 점검 (goal / 제약 / 성공기준).
빈 칸 있으면 다음 사이클의 Wonder 질문을 그 칸 방향으로 좁혀 한 번 더 돈다.
세 칸 다 채워지고 직전 사이클과 큰 차이가 없으면 종료.
최대 5사이클 (안전밸브).
출력은 Seed YAML 한 블록.

Boundaries:
- 네 스킬 본문을 다시 적지 않는다. 호출만.
- 빈칸을 추측으로 메우지 않는다.
- 단독 호출(/wonder 등)이 여전히 동작해야 한다.

My input:
[검증용 막연한 요청 한 줄, 예: "보고서 좀 만들어줘"]
```

## 목적

네 스킬이 분리되어 있어도, 본인이 매번 네 번 호출하고 결과를 손으로 묶는 건 번거롭다.
오케스트레이터가 한 사이클을 자동으로 돌리고, Seed의 세 칸 중 비어 있는 칸이 있으면 사이클을 한 번 더 돈다.
Ouroboros가 Ralph 루프로 evolve_step을 반복해 ambiguity가 0.2 아래로 떨어질 때까지 도는 것과 같은 패턴이다.
분리는 그대로 (단독 호출 가능), 사용은 통합 (한 번에 Seed까지).

## 권장 시간

30분. 오케스트레이터 SKILL.md 작성 15분, 검증 15분.

## 준비물

- 2002-1-3에서 만든 네 스킬 (단독 호출 가능 상태)
- 공유 스크래치 파일 경로 합의 (`../../../../.claude/scratch/socrates.md`, 다섯 섹션)
- 검증용 막연한 요청 한 줄 (예: "보고서 좀 만들어줘", "성과 잘 내는 팀을 만들고 싶다")

## 단계별 진행

1. **frontmatter** — name: `socrates`, description은 "막연한 요청 한 줄을 받아 W/R/R/R 사이클을 Ralph 루프로 돌려 닫힌 Seed를 산출한다"로 한 줄.
2. **한 사이클 정의** — Wonder → Reflect → Refine → Restate 차례 호출. 각 호출은 직전 단계의 스크래치 섹션을 입력으로 읽고 자기 섹션을 채운다.
3. **Seed 게이트 점검** — Restate가 끝나면 다음 셋을 점검한다.
   - goal: Restate에 한 줄이 있나?
   - constraints: 기술적 제한 또는 범위 차단이 한 개 이상 있나?
   - acceptance_criteria: 행동 동사로 끝나는 검증 기준이 한 개 이상 있나?
4. **Ralph 루프 규칙** — 셋 중 하나라도 비어 있으면 다음 사이클을 돈다. 다음 사이클의 Wonder 질문을 비어 있는 칸 쪽으로 좁힌다 (예: 제약이 비었으면 "이 일에서 하지 말아야 할 게 뭔가요?"로).
5. **수렴 조건** — 셋이 다 채워지고, 직전 사이클의 Restate 결과와 큰 차이가 없으면 종료. (Ouroboros의 ontology similarity ≥ 0.85 임계값을 본인 판단으로 흉내.)
6. **안전밸브** — 최대 5사이클. 그래도 안 채워진 칸은 "빈칸"으로 명시하고 종료한다.
7. **출력** — 공유 스크래치 파일 마지막에 Seed YAML 한 블록을 추가한다.
8. **검증** — 막연한 요청 하나로 `/socrates` 호출. 사이클이 도는 동안 빈 칸이 채워지는 모습이 스크래치 파일에 보이는지, 마지막에 Seed YAML 한 블록이 떨어지는지 본다.

## 작성 템플릿 — `../../../../.claude/skills/socrates/SKILL.md`

````markdown
---
name: socrates
description: 막연한 요청 한 줄을 받아 Wonder/Reflect/Refine/Restate를 Ralph 루프로 돌려 닫힌 Seed (goal + 제약 + 성공기준) 한 묶음을 산출한다. "socrates", "/socrates" 호출.
---

# Socrates — 5단계 묻기 오케스트레이터 (Ralph 루프)

## 역할
네 스킬(Wonder, Reflect, Refine, Restate)을 한 사이클로 묶고, Seed의 세 칸이 다 채워질 때까지 사이클을 반복한다.

## 한 사이클
1. /wonder 호출 → `## Wonder` 섹션 채움
2. /reflect 호출 → `## Reflect` 섹션 채움
3. /refine 호출 → `## Refine` 섹션 채움
4. /restate 호출 → `## Restate` 섹션 채움
5. Seed 게이트 점검 (아래)

## Seed 게이트
다음 셋이 다 채워졌나?
- goal: Restate에 한 줄이 있다
- constraints: 기술적 제한 또는 범위 차단이 한 개 이상 있다
- acceptance_criteria: 행동 동사로 끝나는 검증 기준이 한 개 이상 있다

세 칸 다 차고 직전 사이클과 큰 차이가 없으면 종료.
한 칸이라도 비었으면 다음 사이클로.

## Ralph 루프 규칙
- 다음 사이클의 Wonder 질문을 비어 있는 칸 방향으로 좁힌다.
- 같은 칸을 두 사이클 연속 못 채우면 본인에게 다시 묻는다 (자동 추측 금지).
- 최대 5사이클 (안전밸브). 그래도 안 차면 "empty"으로 명시하고 종료.

```python
seedGate = goalFinish and constraintsFinish && acceptance_criteriaFinish
while true:
  if seedGate:
    exit
  if cycle > 5:
    return empty
  wonder()
  reflect()
  refine()
  restate()
```

## 출력
공유 스크래치 파일 마지막에 Seed YAML 한 블록을 추가한다:

```yaml
goal: <한 줄>
constraints:
  - <조건>
acceptance_criteria:
  - <행동 동사로 끝나는 검증 기준>
```

## 원칙
1. 네 스킬의 본문을 다시 적지 않는다. 호출만.
2. 빈칸을 추측으로 메우지 않는다.
3. 5사이클 안전밸브를 넘기지 않는다.
````

## 예시 — 입력 "보고서 좀 만들어줘"

오케스트레이터를 호출했을 때 떨어지는 산출물의 예시 (사이클 2회 후 수렴 가정).

스크래치 파일 마지막 블록:

```yaml
goal: 마케팅 PM이 다음 분기 예산 결정을 위한 캠페인 ROI 한 페이지 요약을 임원 두 명에게 만든다
constraints:
  - 한 페이지 분량을 넘기지 않는다
  - 외부 사실(미공개 마진 등)을 인용하지 않는다
acceptance_criteria:
  - 임원이 추가 질문 없이 결정으로 넘어간다
  - 결정 항목과 확인 질문이 분리되어 적혀 있다
```

(스크래치 파일에는 ## 사용자 입력 ~ ## Restate 다섯 섹션이 사이클 횟수만큼 누적되어 있고, 마지막에 위 YAML 한 블록이 추가됨.)

## 완료 기준

- `/socrates` 한 번 호출로 Seed YAML 한 블록이 떨어진다.
- 사이클이 1~5회 도는 동안 빈 칸이 채워지는 모습이 스크래치 파일에 보인다.
- Seed의 세 칸 중 끝까지 안 채워진 게 있으면 "빈칸"으로 명시되어 있다.
- `/wonder`, `/reflect`, `/refine`, `/restate` 단독 호출이 여전히 동작한다.

## 제출/검토 체크리스트

- [ ] 한 사이클 = 4 스킬 차례 호출이 본문에 명시되어 있다.
- [ ] Seed 게이트 점검(goal / 제약 / 성공기준)이 본문에 명시되어 있다.
- [ ] Ralph 루프 종료 조건(수렴 또는 5사이클)이 명시되어 있다.
- [ ] 출력이 Seed YAML 한 블록 형식이다.
- [ ] 단독 호출과 통합 호출 모두 동작한다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.
