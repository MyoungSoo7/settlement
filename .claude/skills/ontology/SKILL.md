---
name: ontology
description: |
  Seed.goal을 한 단어처럼 보고, 그 단어가 어디까지 가리키는지 boundary와 properties 3개로 정해 Seed 위에 ontology 한 층을 얹는다. idea는 Seed.goal에서 그대로 가져오고, action은 묻지 않는다. 호출마다 socrates.md의 최상위 ## Ontology 섹션을 최신 값으로 통째 교체한다(누적 X). 직전 ontology와의 비교는 interview-harness가 호출 전/후 스냅샷으로 처리한다.
  Triggers (KO): ontology, /ontology, 온톨로지 만들어줘, 의미 경계 정해줘
  Triggers (EN): ontology, create ontology, define semantic boundary
  Do NOT use when: Seed가 아직 없을 때 (-> socrates), Seed 자체를 진화시켜야 할 때 (-> evolve-step), Seed 생성부터 수렴까지 필요할 때 (-> interview-harness)
  vs evolve-step: evolve-step은 Seed 내용을 바꾸고, ontology는 Seed 위에 의미 경계 레이어를 얹는다.
---

# Ontology — 단어의 경계를 정하는 일

## 상세 설명

닫힌 Seed의 goal이 가리키는 의미 영역을 boundary와 properties 3개로 고정하는 단계다. 결과는 `.claude/scratch/socrates.md`의 최상위 `## Ontology` 섹션에 최신 한 벌로 저장한다.

## 역할

단어 하나는 자기보다 큰 영역을 품고 있다. "성과"라는 한 단어 안에 매출, 학습 속도, 팀 분위기, 출시 속도, 채용 성공률, 고객 만족도가 다 들어간다. 단어 자체는 이 영역을 통째로 가리킨다. 그 안의 어느 한 점을 가리키는 게 아니다.

그래서 한 명은 매출을 떠올리고, 다른 한 명은 팀 분위기를 떠올린다. 같은 단어를 쓰지만 다른 것을 본다.

ontology는 이 단어(=Seed.goal)가 **어디까지를 가리키고 어디부터는 아닌지** — 그 경계를 같이 정해 Seed 위에 한 층을 얹는다. 세 항목으로 그 층을 짠다.

1. **idea** — Seed.goal 그대로. 사용자에게 다시 묻지 않고 `.claude/scratch/socrates.md`의 최상위 `## Seed`에서 자동으로 가져온다.
2. **boundary** — 그 단어가 가리키는 영역의 가장자리. "어디까지가 이 단어 안이고 어디부터는 밖인가." (예: idea가 "성과"라면 boundary는 "이번 분기 매출만, 학습 속도·팀 분위기는 제외")
3. **properties** — boundary 안에 반드시 들어가는 핵심 요소 **정확히 3개**. property 하나를 빼거나 더하면 새 ontology다. (예: boundary가 "이번 분기 매출"이라면 properties는 [매출액, 거래 건수, 신규 고객 수])

action은 묻지 않는다. Seed의 `acceptance_criteria`가 이미 "무엇이 어떻게 동작해야 한다"를 적고 있다. ontology는 그 위에 의미의 경계만 추가로 얹는 층이다.

## 입력

`.claude/scratch/socrates.md`의 최상위 **`## Seed` 섹션**(항상 최신 한 벌만 존재)을 읽는다. Seed가 없으면 호출하지 않는다 — ontology는 닫힌 Seed 위에 얹는 한 층이지, Seed보다 먼저 만들어지지 않는다.

## 공유 스크래치 파일

`.claude/scratch/socrates.md` 한 곳. 호출이 끝나면 그 파일 안의 최상위 **`## Ontology` 섹션을 최신 값으로 통째 교체**한다. 섹션이 없으면 마지막에 새로 만들고, 이미 있으면 기존 블록을 통째 들어내고 새 블록을 그 자리에 쓴다. 누적하지 않는다. 직전 ontology와의 비교는 마스터 하네스(`/interview-harness`)가 `/ontology` 호출 전 ontology를 메모리에 스냅샷으로 잡아두고, 호출 후 새 값과 비교하는 방식으로 처리한다.

## 한 사이클 (4단계)

### 1. Seed 읽기 + idea 자동 추출

socrates.md의 `## Seed` 섹션 YAML 블록을 읽는다. **`goal` 필드 값을 그대로 ontology의 `idea`로 옮긴다.** 사용자에게 다시 묻지 않는다.

### 2. boundary 질문 — AskUserQuestion 도구 사용 (질문 1개)

- header: "boundary"
- question: "이 idea(`<Seed.goal>`)가 가리키는 영역의 경계는 어디까지입니까?"
- options: Seed.goal과 constraints 컨텍스트를 보고 후보 boundary 3~4개를 미리 채운다. 각 후보는 "X까지만, Y는 제외" 형식으로 한 줄. (예: idea가 "성과를 높인다"라면 후보 셋 — "이번 분기 매출만, 나머지는 제외" / "매출과 채용 둘 다" / "매출·채용·고객 만족도까지"). 사용자가 후보를 고르거나 "Other"로 자유 입력. 고른 답을 그대로 boundary로 쓴다.

### 3. properties 질문 — AskUserQuestion 한 번 호출, 질문 3개 묶음

한 번의 AskUserQuestion 호출에 `questions` 배열로 3개의 property 질문을 묶어 보낸다 (루프 없음).

```
questions: [
  { header: "property 1", question: "이 boundary 안에 반드시 들어가야 하는 첫 번째 핵심 요소는 무엇입니까?", options: [...candidate1...] },
  { header: "property 2", question: "두 번째 핵심 요소는 무엇입니까?", options: [...candidate2...] },
  { header: "property 3", question: "세 번째 핵심 요소는 무엇입니까?", options: [...candidate3...] }
]
```

각 질문의 options에는 boundary 안에 들어갈 법한 후보를 3~4개 미리 채운다. (예: boundary="이번 분기 매출"이라면 property 1 후보는 "매출액 / 거래 건수 / 결제 완료 수" 같은 식). 사용자가 후보를 고르거나 "Other"로 자유 입력. 세 답을 그대로 properties 배열에 넣는다.

3개로 고정한 이유: boundary가 또렷하면 핵심 요소 셋으로 ontology가 충분히 식별된다. 그 이상이 되면 boundary 정의가 흐릿한 신호 — boundary를 다시 좁힐 자리이지, property를 더 늘릴 자리가 아니다.

### 4. socrates.md 갱신 (replace)

세 항목을 YAML 한 블록으로 묶어 socrates.md 안의 **`## Ontology` 섹션을 통째 교체**한다. 섹션이 없으면 파일 마지막에 새로 만든다. **사용자가 답한 단어만** YAML에 들어간다. 추측 금지.

````markdown
## Ontology
> 출처: /ontology 호출 (최신 Seed 위에 얹음)

```yaml
ontology:
  idea: <Seed.goal 그대로>
  boundary: <한 줄>
  properties:
    - <property 1>
    - <property 2>
    - <property 3>
```
````

버전 번호를 붙이지 않는다. socrates.md에는 항상 최신 Seed 한 벌 + 최신 Ontology 한 벌만 남는다.

## 원칙

1. **idea와 boundary는 다르다.** idea는 영역을 통째로 가리키는 단어, boundary는 그 영역의 가장자리. 둘을 섞지 않는다.
2. **property는 boundary 안에 있어야 의미가 있다.** boundary 밖의 것은 property가 아니다.
3. **property 한 개의 차이가 새 ontology다.** 운전 ontology에서 운전자 제거 → 자율주행 ontology. 이 인식이 stop rule의 토대다.
4. **action은 묻지 않는다.** Seed.acceptance_criteria가 이미 "무엇이 어떻게 동작해야 한다"를 적고 있다. ontology는 그 위에 의미의 경계만 얹는다.
5. **property에 data type을 묻지 않는다.** type은 코드 단계의 일이고, ontology는 그 전의 의미 단계다.
6. properties는 정확히 3개. 더 적거나 더 많으면 boundary 정의가 모자라거나 흐릿한 신호.
7. 추측으로 idea/boundary/property를 만들지 않는다. AskUserQuestion으로 받은 답만 YAML에 들어간다.
8. socrates.md의 Ontology 섹션은 호출마다 통째로 교체한다. 누적하지 않는다 — 직전 ontology 보존은 마스터 하네스의 책임.

## 완료 기준

- socrates.md 안에 `## Ontology` 섹션이 정확히 한 벌 있다 (이전 ontology가 있었다면 통째 교체됐다).
- ontology YAML 한 블록에 idea / boundary / properties 세 항목이 모두 채워져 있다.
- idea는 socrates.md의 `## Seed` Goal과 글자 그대로 동일하다.
- properties는 정확히 3개이고, 각 property가 한 줄짜리 핵심 요소다.

## 다음 단계 — stop rule 비교

마스터 하네스(`/interview-harness`)는 매 사이클 끝에 직전 ontology와 이번 ontology를 비교한다. socrates.md에는 최신 ontology만 남으므로, 마스터 하네스는 `/ontology` 호출 직전에 현재 ontology를 메모리에 스냅샷으로 잡아두고, 호출 후 새 값과 비교한다.

- **idea 같음** → 1점, 다름 → 0점
- **boundary 같음** → 1점, 다름 → 0점. 이 값은 모델이 단독 판정하지 않고 AskUserQuestion으로 사용자에게 "직전 boundary와 이번 boundary가 같은 의미인가요?"를 확인해 정한다.
- **properties overlap 비율** → 0~1점 (예: 3개 중 2개 같으면 0.67)
- 총점 / 3 ≥ 0.85이면 **수렴(stop)**, 미만이면 한 사이클 더.

본인 학습 자료에서는 두 ontology의 세 항목을 나란히 두고 "같은 의미를 가리키는가?"를 직관 판단해도 충분하다.
