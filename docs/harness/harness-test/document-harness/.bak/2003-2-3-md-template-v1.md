# 2003-2-3. md 템플릿 v1 완성하기

## Source Mapping

- Curriculum: `curriculum.md` row `2003-2-3` in `3-2. 규칙 템플릿화` defines the clip as `[실습] md 템플릿 v1 완성하기`, with key message `바로 붙여 넣을 수 있는 규칙서를 만든다` and required output `md 템플릿 v1`.
- Interview: `interview.md` provides the conceptual boundary for this worksheet: Use the interview framing that reusable structure lets AI start from the same contract each time instead of reconstructing tacit rules from a fresh prompt.
- Guardrail: follow `decks/approved-source-checklist.md`; do not add new external examples, unsupported statistics, prices, discounts, volatile marketing claims, or unverifiable repo behavior.

## Practice Task and Prompt

- Task: create `md 템플릿 v1` for clip `2003-2-3` so the learner can apply the curriculum message `바로 붙여 넣을 수 있는 규칙서를 만든다` to their own work.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2003-2-3을 도와줘.

Curriculum mapping:
- Module: 3-2. 규칙 템플릿화
- Clip: [실습] md 템플릿 v1 완성하기
- Key message: 바로 붙여 넣을 수 있는 규칙서를 만든다
- Required output: md 템플릿 v1

Interview mapping:
- Use the interview framing that reusable structure lets AI start from the same contract each time instead of reconstructing tacit rules from a fresh prompt.

Task:
내 입력을 바탕으로 `md 템플릿 v1` 초안을 작성해줘. 아래 실습자료의 작성 템플릿 구조를 따르고, 모르는 정보는 추측하지 말고 "다시 물을 질문" 또는 "확인 필요"로 남겨.

Boundaries:
- curriculum.md와 interview.md의 범위를 벗어난 외부 사례를 추가하지 않는다.
- 검증되지 않은 수치, 가격, 할인, 마케팅성 주장을 넣지 않는다.
- 최종 책임 판단이 필요한 항목은 사람이 결정할 수 있게 분리한다.

My input:
[여기에 내 업무 상황, 원문, 제약, 기존 산출물을 붙인다]
```

## 목적

문서 하네스 체크리스트를 바로 붙여 넣을 수 있는 md 규칙서로 바꾼다. 역할, 입력, 처리 원칙, 금지사항, 출력 형식, 예시를 포함해 같은 업무에 반복 적용할 수 있는 md 템플릿 v1을 만든다.

## 권장 시간

30분

## 준비물

- 2003-1-3에서 만든 문서 하네스 체크리스트
- 문서형 하네스가 받을 입력 예시 1개
- 원하는 출력 형식의 간단한 예시 또는 항목 목록
- AI가 추측하지 말아야 할 금지사항 목록

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 템플릿 이름 |  |
| AI의 역할 |  |
| 받을 입력 |  |
| 처리 원칙 |  |
| 금지사항 |  |
| 출력 형식 |  |
| 예시로 보여줄 기준 |  |
| 다른 업무에 복사할 때 바꿀 빈칸 |  |

## 단계별 진행

1. 2003-1-3 체크리스트에서 문서로 고정할 항목만 가져온다.
2. 역할, 입력, 처리 원칙, 금지사항, 출력 형식, 예시 순서로 빈 템플릿을 만든다.
3. 역할은 AI가 책임질 범위와 책임지지 않을 범위를 함께 적는다.
4. 입력 섹션에는 원문 보존 위치와 확인된 요구사항만 넣도록 쓴다.
5. 금지사항에는 없는 사실 추가, 임의 결론, 빈칸 추측을 막는 문장을 넣는다.
6. 출력 형식은 사람이 검수하거나 다음 하네스가 재사용할 수 있게 항목을 고정한다.
7. 다른 업무에 복사할 때 바꿔야 할 자리만 대괄호 빈칸으로 남긴다.
8. 예시는 정답을 강요하지 않고 톤, 길이, 구조를 맞추는 기준으로 짧게 둔다.

## 작성 템플릿

```markdown
# [업무명] md 템플릿 v1

## Role
- 너의 역할:
- 책임 범위:
- 책임지지 않을 범위:

## 입력
- 원문:
- 확인된 요구사항:
- 반드시 보존할 표현:
- 비어 있으면 질문할 항목:

## Processing Principles
1.
2.
3.

## Forbidden
- 하지 말아야 할 일:
- 입력에 없으면 확정하지 말 것:
- 질문으로 남길 조건:

## Output Format
1.
2.
3.
4.

## 예시
### Input
-

### Output
-

## Done Check
- [ ] 역할, 입력, 원칙, 금지사항, 출력 형식, 예시가 모두 있다.
- [ ] 바꿔야 할 빈칸과 고정할 규칙이 구분되어 있다.
- [ ] 입력에 없는 사실을 확정하지 않는다.
```

## 예시

```markdown
# 회의록을 1page 기획안으로 바꾸는 md 템플릿 v1

## Role
- 너의 역할: 회의록을 읽고 1page 기획안 초안을 구조화하는 보조자
- 책임 범위: 입력된 회의록과 확인된 요구사항 안에서 배경, 문제, 제안, 확인 질문을 정리한다.
- 책임지지 않을 범위: 최종 우선순위 확정, 없는 사실 보강, 실행 가능성 단정

## 입력
- 원문: [회의록 원문]
- 확인된 요구사항: [목표, 제약, 성공기준]
- 반드시 보존할 표현: [원문 그대로 남길 표현]
- 비어 있으면 질문할 항목: [결정권자, 일정, 제외 범위]

## Processing Principles
1. 회의록에 있는 내용과 확인된 요구사항만 사용한다.
2. 배경, 문제, 제안, 확인 질문을 분리한다.
3. 불확실한 내용은 결론으로 쓰지 않고 확인 질문으로 남긴다.

## Forbidden
- 하지 말아야 할 일: 입력에 없는 수치, 외부 사례, 확정되지 않은 결론 추가
- 입력에 없으면 확정하지 말 것: 최종 일정, 담당자, 우선순위
- 질문으로 남길 조건: 목표 독자, 제외 범위, 결정 기준이 비어 있을 때

## Output Format
1. 제목
2. 배경
3. 문제
4. 제안
5. 확인 질문

## 예시
### Input
- 회의록: 온보딩 문서가 흩어져 있어 신규 참여자가 같은 질문을 반복한다.
- 확인된 요구사항: 첫 검토자가 빠진 정보를 확인할 수 있어야 한다.

### Output
- 제목: 신규 참여자 온보딩 문서 정리 초안
- 배경: 온보딩 문서가 여러 곳에 흩어져 있다.
- 문제: 신규 참여자가 같은 질문을 반복하고 검토자가 매번 설명한다.
- 제안: 필요한 문서 위치, 첫 실행 순서, 확인 질문을 한 페이지로 묶는다.
- 확인 질문: 반드시 포함할 문서 범위와 제외할 항목은 무엇인가?

## Done Check
- [x] 역할, 입력, 원칙, 금지사항, 출력 형식, 예시가 모두 있다.
- [x] 바꿔야 할 빈칸과 고정할 규칙이 구분되어 있다.
- [x] 입력에 없는 사실을 확정하지 않는다.
```

## 완료 기준

- md 템플릿 v1이 작성되어 있다.
- 역할, 입력, 처리 원칙, 금지사항, 출력 형식, 예시가 모두 포함되어 있다.
- 다른 업무에 복사할 때 바꿔야 할 빈칸과 고정할 규칙이 구분되어 있다.
- 금지사항이 AI의 추측, 없는 사실 추가, 빈칸 임의 보강을 막는다.
- 출력 형식이 사람이 검수할 수 있을 만큼 구체적이다.

## 제출/검토 체크리스트

- [ ] 바로 붙여 넣을 수 있는 규칙서 형태다.
- [ ] 2003-1-3 체크리스트의 고정 항목이 템플릿에 반영되어 있다.
- [ ] 예시는 빠른 정렬을 위한 기준이며 정답을 과하게 강요하지 않는다.
- [ ] 복사해서 쓸 때 바꿔야 할 자리만 빈칸으로 남겼다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.
