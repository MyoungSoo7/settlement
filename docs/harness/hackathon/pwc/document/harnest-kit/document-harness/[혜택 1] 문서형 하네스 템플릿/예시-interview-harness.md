# 예시 — 요구사항 인터뷰를 md형으로 고정

`skill.template.md`를 채운 예시 1. 막연한 요청에서 닫힌 Seed YAML까지 가는 인터뷰 자동화를 한 장에 고정한 사례.

이 예시는 2장 `/interview-harness` 스킬을 단순화해 *md형 한 장*으로만 옮긴 것이다. 실제 4-skill 오케스트레이션은 분업형(가이드 02)으로 올라간다.

---

```markdown
---
name: interview-harness
description: |
  막연한 요청 한 줄을 입력으로 Wonder → Reflect → Refine → Restate 사이클을 돌려 닫힌 Seed YAML(goal · constraints · acceptance_criteria) 한 묶음을 만든다.
  Triggers (KO): 인터뷰 하네스, 요구사항 정리, Seed로 닫아줘, 막연한 요청 정리
  Triggers (EN): interview harness, close into a seed, requirements interview
  Do NOT use when: 이미 Seed가 있고 사각지대 점검만 필요 → /evolve-step. 의미 발산만 필요 → /wonder.
---

# Interview Harness

## 역할

이 스킬은 막연한 한 줄 요청을 입력으로 닫힌 Seed YAML 한 묶음을 만든다. 사용자 답을 강제하지 않고, 답이 안 정해진 칸은 `empty`로 둔다.

## 입력

한 줄 또는 짧은 문단의 막연한 요청. 예: "task 관리 CLI 만들고 싶다."

## 출력

```yaml
goal: <한 줄. 동사로 끝남.>
constraints:
  - <제약 한 줄>
  - <제약 한 줄>
acceptance_criteria:
  - <검증 가능한 한 줄>
  - <검증 가능한 한 줄>
```

불변 조건:
- 세 칸 모두 채워져야 "닫힌 Seed"다. 하나라도 비면 `empty`로 표기.
- `goal`은 정확히 한 문장.
- `constraints`와 `acceptance_criteria`는 각 1개 이상.

## 원칙

1. 사용자에게 한 번에 한 질문만 한다.
2. 사용자의 원문 어휘를 가능한 유지한다 (의미 변형 금지).
3. 매 사이클 끝에 현재 Seed 상태를 보여주고 다음 사이클로 갈지 묻는다.
4. 5사이클을 넘으면 안전밸브로 종료, 현재 Seed를 그대로 반환.

## 금지사항

- 사용자가 말하지 않은 제약을 임의로 추가하지 않는다.
- `acceptance_criteria`에 검증 불가한 문장을 넣지 않는다 ("사용자 친화적", "자연스럽게" 등).
- Seed를 닫지 않은 채 코드 생성으로 넘어가지 않는다.

## 예시

### 입력

```
task 관리 CLI 만들고 싶다.
```

### 출력

```yaml
goal: 단일 사용자가 로컬에서 task를 만들고 조회하는 CLI를 만든다.
constraints:
  - Python 3.14+
  - 로컬 파일 저장만 (외부 동기화 없음)
acceptance_criteria:
  - `task add "..."` 명령으로 task가 생성된다
  - `task list` 명령으로 task 목록이 출력된다
```

## 인접 스킬과의 관계

- vs `/wonder`: `/wonder`는 의미 발산만 한다. 이 스킬은 발산을 포함해 Seed 닫기까지 간다.
- vs `/evolve-step`: 이 스킬이 만든 Seed의 사각지대 점검은 `/evolve-step`이 담당.
- 이 스킬의 출력은 `/ontology`와 분업형 인터뷰 하네스의 입력이 된다.

## 자가 검증

같은 요청 한 줄을 두 번 입력해 출력 YAML의 키 구조가 같은지 본다. 사용자 답이 달라 *값*이 다른 것은 정상. 하지만 *키 구조*가 어긋나면 위 출력 스키마를 더 좁힌다.
```

---

## 이 예시에서 봐야 할 것

- frontmatter의 description이 3부(정의·트리거·금지)로 깔끔하게 나뉨.
- 출력 스키마가 *코드 블록*으로 명시되어 있고 불변 조건이 본문에 한 줄씩 적힘.
- 원칙 4개·금지사항 3개가 모두 검증 가능한 한 줄.
- 예시 입출력은 한 쌍만.
- 인접 스킬 경계가 명시되어 있어 *이 스킬이 다루지 않는 영역*이 분명하다.
