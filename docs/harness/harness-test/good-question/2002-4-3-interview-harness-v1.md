# 2002-4-3. Ralph 하네스 — Socrates · Define · Ontology · Stop Rule 묶기

![interview-harness-v1](../../assets/interview-harness-v1.png)

## Practice Task and Prompt

- Task: 지금까지 만든 상위 스킬 셋(`/socrates`, `/evolve-step`, `/ontology`)을 한 번의 호출로 묶어 ontology 유사도가 수렴할 때까지 자동으로 돌리는 마스터 하네스 `../../../../.claude/skills/interview-harness/SKILL.md`를 만든다.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2002-4-3을 도와줘.

Curriculum mapping:
- Module: 2-4. 인터뷰 하네스 만들기
- Clip: [프로젝트 준비] Ralph 하네스 (Socrates + Define + Ontology + Stop Rule)
- Key message: Socrates 내부 4개 스킬(`/wonder`, `/reflect`, `/refine`, `/restate`)과 상위 3개 호출(`/socrates`, `/evolve-step`, `/ontology`)을 한 Ralph 루프로 묶는다
- Required output: .claude/skills/interview-harness/SKILL.md 한 개

Reference (이미 만들어진 상위 스킬):
- /socrates    : 막연한 입력 한 줄 → Seed v1 (goal + 제약 + 성공기준). 내부에서 `/wonder` → `/reflect` → `/refine` → `/restate`를 호출한다.
- /evolve-step : Seed v_n → Seed v_(n+1) (사각지대 사용자 채택 반영)
- /ontology    : Seed.goal을 단어로 보고 그 단어의 경계를 같이 정함 → idea(=Seed.goal 그대로) + boundary + properties (action은 묻지 않음 — Seed.acceptance_criteria가 이미 적고 있음)

한 사이클 흐름 (모든 사이클 같은 구조, 입력만 narrow됨):
- Cycle 1: 원래 입력(broad) → /socrates(broad W/R/R/R) → Seed → /evolve-step → 닫힌 Seed v1 → /ontology(v1)
- Cycle 2+: 직전 Seed v_n(narrow 입력) → /socrates(narrow W/R/R/R — 직전 Seed가 입력이라 질문이 더 좁혀짐) → Seed → /evolve-step → Seed v_(n+1) → /ontology(v_(n+1))

Stop rule (둘 중 하나):
- 수렴: ontology v_n vs v_(n+1) semantic similarity ≥ 0.85
- 안전밸브: Cycle > 5

Boundaries:
- 사이클 무한 루프 방지: Cycle > 5 안전밸브 필수.
- 사용자 채택 없이 자동 채움 금지 (/evolve-step의 사각지대 채택은 사람이).
- socrates.md는 최신 Seed 한 벌 + 최신 Ontology 한 벌만 유지(누적 X). 직전 ontology는 마스터 하네스가 `/ontology` 호출 직전 `.claude/scratch/interview-harness.md`에 별도 저장해 둔다(다음 사이클 비교용).
- 하위/상위 스킬의 본문을 흉내내지 않는다. 호출만.

My input:
[검증용 막연한 요청 한 줄, 예: "task 관리 CLI 만들고 싶다"]
```

## 목적

각 스킬은 단독으로도 동작하지만, 본인이 매번 손으로 호출 순서를 외울 필요는 없다.
마스터 하네스가 `/socrates`(내부 4개 스킬 포함), `/evolve-step`, `/ontology`를 정확한 순서로 묶고, ontology 유사도가 임계값을 넘을 때까지 자동으로 돈다.
한 번의 `/interview-harness` 호출이 막연한 입력에서 수렴한 Seed까지 간다.

분리는 그대로(각 스킬 단독 호출 가능), 사용은 통합(한 호출로 전체 수렴).

## 권장 시간

40분. 하네스 SKILL.md 작성 20분, 검증 20분.

## 준비물

- 상위 스킬 셋 단독 호출 가능 상태: `/socrates`, `/evolve-step`, `/ontology`
- 각 스킬의 공유 스크래치 파일 경로 확인:
  - `../../../../.claude/scratch/socrates.md` — canonical 파이프라인. **최신 Seed 한 벌 + 최신 Ontology 한 벌만 유지**(호출마다 통째 교체, 누적 X). `/socrates`, `/evolve-step`, `/ontology` 가 공유한다.
  - `../../../../.claude/scratch/evolve-step.md` — `/evolve-step`이 사각지대 두 질문 + 후보 6개 + 채택 표시 + v1/v2 Seed를 audit 로그로 사이클마다 누적. 사이클 추적은 이 파일로 한다.
  - `.claude/scratch/interview-harness.md` — 마스터 하네스가 직접 관리. 매 사이클 ontology 호출 직전 직전값을 여기에 저장해 두고, 이번 사이클 ontology와 비교한다. socrates.md가 최신만 유지하므로 비교용 보존은 이 파일이 책임진다.
- 검증용 막연한 요청 한 줄 (예: "task 관리 CLI 만들고 싶다", "보고서 좀 만들어 줘")

## 단계별 진행

1. **frontmatter 작성** — name: `interview-harness`, description은 "막연한 요청 한 줄을 받아 Socrates + Define + Ontology를 Ralph 루프로 묶어 수렴한 Seed까지 자동으로 간다." 한 줄.

2. **한 사이클 구조 정의** — 모든 사이클이 같은 흐름을 따른다. 입력만 narrow해진다.
   - **Cycle 1**: 원래 입력(broad) → `/socrates`(W/R/R/R 처음부터) → 초기 Seed → `/evolve-step` → 닫힌 Seed v1 → `/ontology(v1)`
   - **Cycle 2+**: 직전 Seed v_n을 입력으로 → `/socrates`(같은 W/R/R/R인데 직전 Seed가 입력이라 질문이 더 좁혀짐 = **narrow**) → 새 Seed → `/evolve-step` → Seed v_(n+1) → `/ontology(v_(n+1))`
   - 핵심: 사이클이 진행될수록 `/socrates`의 W/R/R/R 질문이 좁혀지고 사용자 답도 빠르게 떨어진다. 그래서 ontology가 점점 안정된다.

3. **Stop rule 점검 — semantic similarity** — 매 사이클 끝에:
   - `.claude/scratch/interview-harness.md`에 저장해 둔 직전 ontology와 이번 ontology를 세 항목(idea / boundary / properties)으로 비교
   - 세 항목 각각에 점수(0~1) → 평균
   - **≥ 0.85이면 수렴 → stop**, 미만이면 다음 사이클
   - 비교 후 이번 ontology를 같은 파일에 덮어써서 다음 사이클을 준비한다.

4. **안전밸브** — Cycle > 5이면 강제 종료. 현재 Seed를 그대로 반환, stop_reason은 "safety_valve".

5. **출력 형식 명시** — 최종 산출은 닫힌 Seed YAML 한 블록 + 사이클 메타데이터(Cycle 횟수, similarity, stop_reason).

6. **검증 1회** — 막연한 요청 하나로 `/interview-harness` 호출. 1~5 사이클 돌면서 ontology가 수렴해 가는지, 최종 Seed가 닫혀서 떨어지는지 본다.

7. **회귀 점검** — `/wonder`, `/reflect`, `/refine`, `/restate`, `/socrates`, `/evolve-step`, `/ontology` 단독 호출이 여전히 동작하는지 확인한다. 하네스가 각 스킬의 단독성을 깨면 안 된다.

## 작성 템플릿 — `../../../../.claude/skills/interview-harness/SKILL.md`

````markdown
---
name: interview-harness
description: 막연한 요청 한 줄을 받아 Socrates + Define + Ontology를 Ralph 루프로 묶어, ontology 유사도가 0.85 이상으로 수렴할 때까지 자동으로 돌려 닫힌 Seed를 산출한다. "interview-harness", "/interview-harness", "하네스로 돌려줘" 호출.
---

# Interview Harness — Ralph Loop으로 Seed까지

## 역할
상위 스킬 셋(`/socrates`, `/evolve-step`, `/ontology`)을 한 사이클로 묶고, ontology 유사도가 임계값을 넘을 때까지 사이클을 반복한다. `/socrates`는 내부에서 `/wonder`, `/reflect`, `/refine`, `/restate`를 호출한다. 각 스킬의 본문을 다시 적지 않고 호출만 한다.

## 컴포넌트
- **Socrates 컴포넌트**: `/socrates` — 첫 사이클에만 호출. Seed v1 생성.
- **Define 컴포넌트**: `/evolve-step` — 매 사이클 호출. Seed v_n → Seed v_(n+1).
- **Ontology**: `/ontology` — 매 사이클 끝에 호출. Seed.goal을 단어로 보고, 그 단어의 boundary와 properties를 같이 정한다 (idea는 Seed.goal에서 자동 추출, action은 묻지 않음).

## 한 사이클 (모든 사이클 같은 구조)

1. **입력 준비**
   - Cycle 1: 사용자 원문(broad)을 그대로 입력.
   - Cycle 2+: 직전 사이클의 Seed v_n을 입력으로 (자동으로 narrow해짐).
2. `/socrates` 호출 — W/R/R/R 사이클을 돌려 초기 Seed 생성.
   - Cycle 1: broad한 W/R/R/R (사용자가 처음부터 답함).
   - Cycle 2+: narrow한 W/R/R/R (직전 Seed가 입력이라 질문이 좁혀지고 답도 빠름).
3. `/evolve-step` 호출 — 사각지대 채택을 반영해 Seed v_(n+1) 완성.
4. `/ontology` 호출 (입력: Seed v_(n+1)) → ontology v_(n+1).
5. Stop rule 점검 (아래).

## Stop Rule

다음 둘 중 하나면 종료.

**수렴 (semantic similarity)**:
ontology v_n vs v_(n+1)을 세 항목으로 비교.
- idea 같음 → 1점, 다름 → 0점 (idea는 Seed.goal에서 그대로 가져오므로 Seed.goal이 바뀌지 않으면 자동 1점)
- boundary 같음 → 1점, 다름 → 0점
- properties overlap 비율 → 0~1점 (예: 3개 중 2개 같으면 0.67)
- 총점 / 3 ≥ 0.85이면 수렴, stop_reason = "convergence"

**안전밸브 (Cycle > 5)**:
사이클이 5회를 넘으면 강제 종료, stop_reason = "safety_valve".
현재 Seed를 그대로 반환.

## 출력

```yaml
final_seed:
  goal: <한 줄>
  constraints:
    - ...
  acceptance_criteria:
    - ...
  ontology:
    idea: <Seed.goal 그대로>
    boundary: ...
    properties:
      - ...

cycles: <N>
final_similarity: <0.0~1.0>
stop_reason: <"convergence" 또는 "safety_valve">
```

## 원칙
1. 하위/상위 스킬의 질문 본문이나 흐름을 다시 적지 않는다. 호출만.
2. socrates.md는 최신 Seed 한 벌 + 최신 Ontology 한 벌만 유지한다(누적 X). 직전 ontology는 `.claude/scratch/interview-harness.md`에 별도 저장해 다음 사이클 비교용으로 쓴다.
3. 수렴 임계값은 0.85로 고정한다. 세 항목 평균이 ≥ 0.85면 stop.
4. Cycle > 5 안전밸브는 절대 넘기지 않는다.
5. /evolve-step의 사각지대 채택은 사람이 한다. 자동 채택 금지.
6. 각 스킬 단독 호출이 여전히 동작해야 한다.
````

## 예시 — 입력 "task 관리 CLI 만들고 싶다"

사이클 흐름 예시 (실제 호출 결과는 본인의 답에 따라 다름):

```
Cycle 1 (입력 broad: "task 관리 CLI 만들고 싶다")
  /socrates(broad W/R/R/R): "task가 뭔가요?", "어떤 의미를 가지나요?" 같은 발산 질문
  → Seed v0
  /evolve-step → Seed v1: {goal: "단순한 task 관리 CLI를 만든다",
                            constraints: [Python 3.14+, 로컬만],
                            acceptance: [task 생성이 된다, task 목록 조회가 된다]}
  /ontology(Seed v1) → ontology v1:
    {idea: "단순한 task 관리 CLI를 만든다",            ← Seed.goal 그대로
     boundary: "한 사용자 본인의 task만, 협업·공유·동기화는 제외",
     properties: [task, list, 명령어]}

Cycle 2 (입력 narrow: Seed v1을 입력으로)
  /socrates(narrow W/R/R/R): "task에 우선순위가 있나요?", "deadline은요?" 같이 좁혀진 질문
  → 더 빠르게 답이 떨어짐 (broad 질문은 이미 v1에서 해결됨)
  /evolve-step → Seed v2: {... acceptance: [..., 우선순위 표시가 된다]}
  /ontology(Seed v2) → ontology v2:
    {idea: "단순한 task 관리 CLI를 만든다",            ← Seed.goal 변화 없음 → idea도 그대로
     boundary: "우선순위가 있는 한 사용자 task만, 공유·동기화는 제외",  ← v1보다 좁아짐
     properties: [task, list, 명령어, priority]}
  similarity v1 vs v2:
    idea 1점 (goal 동일), boundary 0점 (가장자리가 우선순위 쪽으로 좁아짐), properties 3/4 = 0.75
    평균 = (1 + 0 + 0.75) / 3 ≈ 0.583
    → 0.85 미만, 계속

Cycle 3 (입력 더 narrow: Seed v2)
  /socrates(매우 narrow): 거의 확인 질문만 ("v2의 priority는 어떻게 구성되나요?")
  /evolve-step → Seed v3 ≈ Seed v2 (사각지대 채택 거의 없음)
  /ontology(Seed v3) → ontology v3 ≈ ontology v2 (boundary, properties 안정)
  similarity v2 vs v3:
    idea 1점, boundary 1점, properties 4/4 = 1.0
    평균 = (1 + 1 + 1) / 3 = 1.00
    → 0.85 이상, 수렴, stop

최종 Seed v3 반환, cycles: 3, final_similarity: 1.00, stop_reason: "convergence"
```

**핵심**: Cycle이 진행될수록 `/socrates`의 W/R/R/R 질문이 좁혀진다(narrow). 첫 사이클은 broad 발산, 둘째부터는 직전 Seed가 입력이라 자연스럽게 좁혀지면서 ontology가 수렴해 간다.

## 완료 기준

채점의 무게중심은 **유사도 수치의 정확성**이 아니라 **스킬 오케스트레이션 + 중간 결과 관리**에 있다.

**오케스트레이션 (상위 흐름과 내부 4개 스킬의 경계를 지켰는가)**
- `../../../../.claude/skills/interview-harness/SKILL.md`가 한 장 분량으로 존재한다.
- 한 사이클 구조(/socrates → /evolve-step → /ontology)가 본문에 명시되어 있고, Cycle 2+에서 /socrates 입력이 직전 Seed로 바뀌어 narrow됨이 명시되어 있다.
- `/socrates` 내부 4개 스킬(`/wonder`, `/reflect`, `/refine`, `/restate`)의 경계가 유지된다.
- 마스터 하네스가 각 스킬을 호출만 하고 본문을 흉내내지 않는다.
- `/interview-harness` 호출 1회로 1~5 사이클 돌면서 닫힌 Seed가 떨어진다.

**중간 결과 관리 (사이클 사이의 상태를 제대로 보관·교체했는가)**
- socrates.md는 매 사이클 통째 교체되어 최신 Seed 한 벌 + 최신 Ontology 한 벌만 남는다.
- `.claude/scratch/interview-harness.md`에 직전 ontology가 저장되어 있고, 매 사이클 비교 후 이번 ontology로 덮어써진다.
- `../../../../.claude/scratch/evolve-step.md`에 사이클별 audit 로그가 누적된다.

**Stop rule 및 출력**
- 수렴 임계값 0.85 + 안전밸브 Cycle > 5 둘 다 명시되어 있다.
- 출력이 `final_seed` + `cycles` + `final_similarity` + `stop_reason`을 포함한다.
- `/wonder`, `/reflect`, `/refine`, `/restate`, `/socrates`, `/evolve-step`, `/ontology` 단독 호출이 여전히 동작한다.

## 제출/검토 체크리스트

- [ ] 마스터 하네스가 각 스킬의 본문을 흉내내지 않고 호출만 한다.
- [ ] 한 사이클이 모두 같은 구조(/socrates → /evolve-step → /ontology)이고, Cycle 2+에서 /socrates 입력이 narrow됨이 명시되어 있다.
- [ ] socrates.md가 매 사이클 통째 교체되어 최신 한 벌만 유지된다.
- [ ] `.claude/scratch/interview-harness.md`에 직전 ontology가 저장·갱신된다 (다음 사이클 비교용).
- [ ] `../../../../.claude/scratch/evolve-step.md`에 사이클별 audit 로그가 남는다.
- [ ] Stop rule이 0.85 수렴 + Cycle > 5 안전밸브 둘 다 명시되어 있다.
- [ ] 출력이 final_seed YAML + 메타데이터(cycles / similarity / stop_reason) 형식이다.
- [ ] `/wonder`, `/reflect`, `/refine`, `/restate`, `/socrates`, `/evolve-step`, `/ontology` 단독 호출이 여전히 동작한다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.

## 다음 단계

이 마스터 하네스가 떨어뜨린 final_seed (goal + 제약 + 성공기준 + ontology)는 3장의 입력이 된다.
3장에서는 이 Seed를 실행 하네스(execute · evaluate · evolve)로 옮겨 코드 단계로 진입한다.
