---
name: evolve-step
description: |
  Seed v1(goal + constraints + acceptance_criteria)을 입력으로 받아 사각지대 두 질문(놓친 사용자 시각 3 / 잘려 나간 가능성 3)을 던지고, 사용자가 ☑/☒로 채택한 의미만 반영해 Seed v2와 옮겨진 의미 한 줄을 산출한다. 새 Seed가 만들어지면 socrates.md의 최상위 ## Seed 섹션을 최신 값으로 통째 교체한다(누적 X). 사이클별 audit은 evolve-step.md에 누적한다.
  Triggers (KO): evolve-step, /evolve-step, 사각지대 검토, seed v1 v2, Seed 진화
  Triggers (EN): evolve step, evolve seed, seed blind spots, seed v1 v2
  Do NOT use when: 막연한 요청에서 Seed를 처음 만들어야 할 때 (-> socrates), Seed와 ontology 수렴까지 필요할 때 (-> interview-harness), ontology 경계만 정해야 할 때 (-> ontology)
  vs socrates: socrates는 Seed v1을 만들고, evolve-step은 이미 있는 Seed를 사용자 채택 기반으로 v2로 진화시킨다.
---

# Evolve-step — Seed v1 → v2 (손 버전)

## 상세 설명

이미 만들어진 Seed를 대상으로, 모델이 사각지대 후보를 여섯 개 제시하고 사용자가 채택한 후보만 Seed에 반영하는 단계다. canonical Seed는 `.claude/scratch/socrates.md`의 최상위 `## Seed`에 최신 한 벌로 유지하고, 사이클별 감사 로그는 `.claude/scratch/evolve-step.md`에 누적한다.

## 역할
한 번 모인 Seed는 본인 시야 안에서 만들어진 한 점이다. 그 점 바깥에 어떤 의미가 잘려 나갔는지 다시 보는 일은 AI에 위임하고, **채택은 사용자 본인이 한다.** Ouroboros가 `evolve_step`으로 자동화하는 작업을 손으로 한 번 흉내내는 실습이다.

## 입력
2002-2-3 Socrates 오케스트레이터(`/socrates`)가 떨어뜨린 Seed YAML 한 블록.

```yaml
goal: ...
constraints:
  - ...
acceptance_criteria:
  - ...
```

또는 같은 형식의 임의 Seed YAML 한 묶음.

## 공유 스크래치 파일 — 두 곳에 쓴다

**1. `.claude/scratch/socrates.md` (canonical Seed 파이프라인 — 최신 한 벌)**
- 입력 Seed는 socrates.md의 **`## Seed` 섹션**(항상 최신 한 벌만 존재)에서 읽는다.
- 새 Seed가 만들어지면 socrates.md의 **`## Seed` 섹션을 통째 교체**한다 (누적 X). 섹션이 없으면 파일 마지막에 새로 만든다. 버전 번호는 붙이지 않는다.
- 이렇게 해야 다음 사이클의 `/socrates`(narrow)가 최신 Seed를 입력으로 가져갈 수 있고, ontology 비교도 단순해진다.
- 직전 Seed 보존은 마스터 하네스(`/interview-harness`)가 호출 전 스냅샷으로 처리한다.

**2. `.claude/scratch/evolve-step.md` (사각지대 Q&A 감사 로그 — 누적)**
- 사각지대 두 질문 + 6개 후보 + ☑/☒ 표시 + 옮겨진 의미 한 줄 + 그 사이클의 v1/v2 Seed를 사이클마다 append.
- audit 용도 — 어느 후보가 채택돼서 사이클별 Seed 진화가 일어났는지 추적. 이 파일만 누적이다.

두 파일이 없으면 만든다. 정책은 다르다 — socrates.md는 최신 교체, evolve-step.md는 누적 append.

## 한 사이클 (5단계)

### 1. v1 보존
입력 YAML을 그대로 `## Seed v1` 섹션에 옮긴다. **수정·재해석 금지.** 들여쓰기·단어 그대로.

### 2. 사각지대 두 질문
Seed v1을 보고 두 질문을 한 번에 묶어 자유 서술로 답한다. 각 질문에 후보 정확히 3개.

- **Q1 — 이 정의가 놓친 사용자 시각 세 가지**: v1의 goal/constraints/acceptance_criteria가 암묵적으로 가정하고 있는 사용자 외에, 같은 산출물에 영향을 받지만 v1에는 안 들어 있는 시각 셋.
- **Q2 — 이 정의로 잘려 나간 가능성 세 가지**: v1의 좁힘으로 인해 의도치 않게 배제된 시나리오/대안/해석 셋.

후보는 한 줄씩, 추상 미사여구 없이 구체적으로. "사용자 입장에서 어떻게"가 아니라 "어느 사람·시점·행동"이 보이게.

### 3. 채택 게이트 (사용자 본인)
여섯 후보를 체크박스로 출력한다.

```
### Q1. 이 정의가 놓친 사용자 시각 세 가지
- [ ] 후보 1 — …
- [ ] 후보 2 — …
- [ ] 후보 3 — …

### Q2. 이 정의로 잘려 나간 가능성 세 가지
- [ ] 후보 1 — …
- [ ] 후보 2 — …
- [ ] 후보 3 — …
```

그 다음 사용자에게 묻는다 (AskUserQuestion 도구 사용 권장, 또는 직접 응답 대기):
> "여섯 후보 각각에 ☑(채택) / ☒(거절)를 표시해 주세요. 추천을 자동 채택하지 않습니다. 한 개도 채택 안 해도 됩니다."

사용자 응답을 받기 전까지 v2 도출로 넘어가지 않는다.

### 4. v2 도출
사용자가 표시한 ☑만 반영해 Seed v2를 새로 쓴다. v1과 같은 YAML 형식(`goal` / `constraints` / `acceptance_criteria` 세 칸).

- ☑가 한 개도 없으면: v2 = v1 그대로 두고 옮겨진 의미 줄에 "옮겨진 의미 없음"을 적는다.
- ☑가 있으면: 어느 칸에 어떻게 반영할지는 채택된 후보의 결에 따라 결정한다. 추정으로 다른 칸을 같이 손대지 않는다.
- v2는 v1을 덮어쓰지 않는다. 두 묶음을 나란히 둔다.

마지막에 **옮겨진 의미 한 줄**: v1 → v2 사이에 어느 칸의 어느 의미가 어떻게 바뀌었는지 한 줄로 적는다. 예시: "독자 범위가 임원 두 명에서 임원 + 후속 캠페인 실무자로 넓어졌고, 완료 신호가 '추가 질문 없이'에서 '임원 두 명이 같은 결정 항목에 합의한다'로 또렷해졌다."

### 5. socrates.md 갱신 (canonical Seed 파이프라인 — replace)

새 Seed가 만들어지면 **반드시** `.claude/scratch/socrates.md`의 `## Seed` 섹션을 **새 내용으로 통째 교체**한다. 섹션이 없으면 파일 마지막에 새로 만든다. 버전 번호는 붙이지 않는다.

socrates.md 교체 형식:

```markdown
## Seed
> 출처: /evolve-step Cycle {{n}} (옮겨진 의미: …)

```yaml
goal: ...
constraints:
  - ...
acceptance_criteria:
  - ...
```
```

- 이전 Seed는 덮어 쓰인다. 누적하지 않는다. 직전 Seed 보존은 마스터 하네스(`/interview-harness`)가 호출 전 스냅샷으로 처리한다 (사이클별 audit은 evolve-step.md에 그대로 누적되므로 별도 추적 가능).
- 채택된 ☑가 0개여서 v2 = v1이면, socrates.md의 `## Seed` 섹션은 그대로 두거나 같은 내용으로 다시 써도 된다. 옮겨진 의미 줄에는 "옮겨진 의미 없음"을 명시.
- 다음 `/socrates`(narrow) 호출은 socrates.md의 최상위 `## Seed` 섹션을 읽어 narrow 진행한다.

## 출력 — `evolve-step.md` audit 로그 한 블록 (사이클마다 append)

> socrates.md에는 Step 5에서 별도로 새 `## Seed` 섹션이 통째 교체된다. 아래 형식은 audit 로그 파일(`evolve-step.md`) 전용으로, v1/v2를 나란히 보존해 사이클별 추적을 가능하게 한다.

```markdown
# Evolve-step — Cycle {{n}}

## Seed v1 (보존)
```yaml
{{입력 그대로}}
```

## 사각지대 두 질문 — AI 응답
### Q1. 이 정의가 놓친 사용자 시각 세 가지
- ☑/☒ 후보 1 — …
- ☑/☒ 후보 2 — …
- ☑/☒ 후보 3 — …

### Q2. 이 정의로 잘려 나간 가능성 세 가지
- ☑/☒ 후보 1 — …
- ☑/☒ 후보 2 — …
- ☑/☒ 후보 3 — …

## Seed v2 (사용자가 채택한 의미만 반영)
```yaml
{{새 YAML 또는 v1과 동일}}
```

## 옮겨진 의미 (v1 → v2)
{{어느 칸의 어느 의미가 어떻게 바뀌었는지 한 줄. 채택이 없었으면 "옮겨진 의미 없음"}}
```

## 원칙
1. v1을 그대로 보존한다. 절대 덮어쓰지 않는다.
2. 채택은 사용자 본인이 한다. 모델이 자동 채택하지 않고, "이게 더 낫다"고 가중치를 주지도 않는다. 후보는 균등하게 제시한다.
3. 사용자가 채택하지 않은 의미는 v2에 들어가지 않는다.
4. 한 후보에 여러 칸을 동시에 손대지 않는다. 채택된 후보가 어느 칸에 가장 자연스럽게 반영되는지 한 칸을 골라 적는다.
5. 추천을 자동 채택하지 않는다. ☑가 0개여도 정상이다 — 그때는 v1 = v2.
6. 후보 6개는 각자 다른 결을 가진다. 비슷한 두 후보를 채워 넣지 않는다.
7. 옮겨진 의미 줄은 어느 칸의 어느 단어가 어떻게 바뀌었는지 가리킨다. "더 풍부해졌다" 류 추상 표현 금지.

## 완료 기준
- **socrates.md의 `## Seed` 섹션이 새 내용으로 통째 교체됐다** (누적 X, 항상 최신 한 벌). 섹션이 없었으면 새로 만들어졌다.
- **evolve-step.md에 이번 사이클 audit 블록이 append됐다** (Seed v1, 6개 후보 + ☑/☒, Seed v2, 옮겨진 의미 한 줄).
- Seed v1을 YAML 한 블록으로 그대로 보존했다.
- 사각지대 두 질문에 각 세 개씩, 총 여섯 후보가 있다.
- 여섯 후보 모두에 ☑ / ☒ 표시가 붙어 있다 (사용자 응답).
- Seed v2가 채택된 의미만 반영해 새로 적혀 있다 (또는 "v1 = v2"로 명시).
- 옮겨진 의미 한 줄이 어느 칸의 어느 의미가 어떻게 바뀌었는지 가리킨다 (또는 "옮겨진 의미 없음").

## 다음 단계
출력된 Seed v1, Seed v2, 옮겨진 의미 한 줄 — 이 셋이 2002-4-3 인터뷰 하네스의 Stage 3 입력이 된다.
