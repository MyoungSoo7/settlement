# 2003-3-3. 내 업무에서 문서형 하네스 한계 진단하기

## Source Mapping

- Curriculum: `curriculum.md` row `2003-3-3` in `3-3. 문서의 강점과 한계` defines the clip as `[실습] 내 업무에서 문서형 하네스 한계 진단하기`, with key message `어디까지 문서로 버틸지 판단한다` and required output `한계 진단표`.
- Interview: `interview.md` provides the conceptual boundary for this worksheet: Use the interview framing that a harness must show where documents are enough and where order, state, retry, or evaluation needs another layer.
- Guardrail: follow `decks/approved-source-checklist.md`; do not add new external examples, unsupported statistics, prices, discounts, volatile marketing claims, or unverifiable repo behavior.

## Asset File Scope

This is the separate Chapter 03 practice asset for required practice clip 3, `2003-3-3`. It prepares the learner to decide what remains inside a document-style harness before the `2003-4-1` Superpowers case-study observation. Any Superpowers bridge in this worksheet is only a transition to the approved chapter 03 reference case; it does not introduce repo behavior, runtime, benchmark, or adoption claims.

## Practice Task and Prompt

- Task: create `한계 진단표` for clip `2003-3-3` so the learner can apply the curriculum message `어디까지 문서로 버틸지 판단한다` to their own work.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2003-3-3을 도와줘.

Curriculum mapping:
- Module: 3-3. 문서의 강점과 한계
- Clip: [실습] 내 업무에서 문서형 하네스 한계 진단하기
- Key message: 어디까지 문서로 버틸지 판단한다
- Required output: 한계 진단표

Interview mapping:
- Use the interview framing that a harness must show where documents are enough and where order, state, retry, or evaluation needs another layer.

Task:
내 입력을 바탕으로 `한계 진단표` 초안을 작성해줘. 아래 실습자료의 작성 템플릿 구조를 따르고, 모르는 정보는 추측하지 말고 "다시 물을 질문" 또는 "확인 필요"로 남겨.

Boundaries:
- curriculum.md와 interview.md의 범위를 벗어난 외부 사례를 추가하지 않는다.
- 검증되지 않은 수치, 가격, 할인, 마케팅성 주장을 넣지 않는다.
- 최종 책임 판단이 필요한 항목은 사람이 결정할 수 있게 분리한다.

My input:
[여기에 내 업무 상황, 원문, 제약, 기존 산출물을 붙인다]
```

## 목적

문서형 하네스가 강한 범위와 약한 범위를 나눈다. 반복 규칙과 출력 포맷은 md 템플릿으로 묶고, 순서, 상태, 재시도가 필요한 부분은 다음 하네스가 필요한 후보로 표시하는 한계 진단표를 만든다.

## 권장 시간

25분

## 준비물

- 2003-1-3에서 만든 문서 하네스 체크리스트
- 2003-2-3에서 만든 md 템플릿 v1
- 문서형 하네스로 만들고 싶은 업무 1개
- 업무 중 순서, 상태, 실패 후 재질문 또는 재시도가 생기는 지점 목록

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 진단할 업무 |  |
| 반복되는 규칙 |  |
| 반복되는 출력 포맷 |  |
| 반드시 지켜야 할 순서 |  |
| 추적해야 할 중간 상태 |  |
| 실패 후 재질문 또는 재시도 지점 |  |
| 문서형으로 충분하다고 보는 범위 |  |
| 다음 하네스가 필요할 수 있는 범위 |  |

## 단계별 진행

1. 진단할 업무의 입력과 출력을 한 문장으로 쓴다.
2. 입력은 달라도 매번 반복되는 규칙과 출력 포맷을 적는다.
3. 이 항목들이 md 템플릿의 역할, 원칙, 금지사항, 출력 형식으로 고정 가능한지 표시한다.
4. 반드시 지켜야 하는 추출 순서, 중간 상태, 실패 후 재질문 또는 재시도 지점을 적는다.
5. 문서가 "해야 한다"고 쓸 수는 있지만 실제로 강제하기 어려운 항목을 따로 표시한다.
6. 문서형으로 충분한 범위와 분업형 또는 MCP형으로 넘길 후보를 한 줄씩 정리한다.
7. 마지막에 프로젝트 1에서 md 템플릿에 넣을 규칙과 문서 밖으로 넘길 위험을 구분한다.

## 작성 템플릿

```markdown
# 문서형 하네스 한계 진단표

## 진단 대상
- 업무명:
- 입력:
- 출력:
- 원하는 반복 결과:

## 문서형으로 강한 부분
| 항목 | 내용 | md에 넣을 위치 | 이유 |
|---|---|---|---|
| 반복 규칙 |  |  |  |
| 출력 포맷 |  |  |  |
| 금지사항 |  |  |  |
| 예시 기준 |  |  |  |

## 문서형으로 약한 부분
| 항목 | 내용 | 문서만으로 약한 이유 | 다음 하네스 후보 |
|---|---|---|---|
| 순서 |  |  |  |
| 상태 |  |  |  |
| 재질문 |  |  |  |
| 재시도 |  |  |  |

## Decision
- 문서형으로 만들 범위:
- 다음 하네스가 필요한 범위:
- 지금 단계에서 보류할 판단:

## Review Check
- [ ] 반복 규칙과 출력 포맷은 문서형 범위에 있다.
- [ ] 순서, 상태, 재시도는 문서 밖 실행 구조 후보로 검토했다.
- [ ] 문서형으로 충분한 이유 또는 부족한 이유가 한 줄로 설명된다.
```

## 예시

```markdown
# 문서형 하네스 한계 진단표

## 진단 대상
- 업무명: 회의록을 1page 기획안으로 바꾸기
- 입력: 회의록 원문과 확인된 요구사항
- 출력: 제목, 배경, 문제, 제안, 확인 질문이 있는 1page 기획안 초안
- 원하는 반복 결과: 입력이 달라도 같은 항목으로 초안이 정리된다.

## 문서형으로 강한 부분
| 항목 | 내용 | md에 넣을 위치 | 이유 |
|---|---|---|---|
| 반복 규칙 | 입력에 있는 내용만 사용한다 | Processing Principles | 매번 같은 판단 기준이 필요하다 |
| 출력 포맷 | 제목, 배경, 문제, 제안, 확인 질문 | Output Format | 사람이 검수하기 쉽다 |
| 금지사항 | 없는 사실과 확정되지 않은 결론을 추가하지 않는다 | Forbidden | 추측을 막는다 |
| 예시 기준 | 짧은 항목형 문장으로 쓴다 | Example | 톤과 길이를 맞춘다 |

## 문서형으로 약한 부분
| 항목 | 내용 | 문서만으로 약한 이유 | 다음 하네스 후보 |
|---|---|---|---|
| 순서 | 원문 보존 후 추출, 그다음 출력 작성 | 문서가 순서를 안내할 수는 있어도 실행을 강제하지 않는다 | 분업형 또는 MCP형 |
| 상태 | 확인 질문 답변 여부 | 답변이 여러 번 오가면 상태 저장이 필요하다 | MCP형 |
| 재질문 | 목표 독자나 제외 범위가 비어 있을 때 | 질문 후 답변을 다시 넣는 흐름이 필요하다 | 분업형 |
| 재시도 | 출력 형식이 깨진 경우 다시 작성 | 실패 감지와 재실행 기준이 필요하다 | MCP형 |

## Decision
- 문서형으로 만들 범위: 반복 규칙, 금지사항, 출력 포맷, 짧은 예시 기준
- 다음 하네스가 필요한 범위: 확인 질문 답변 추적, 실패 후 재시도, 여러 단계의 순서 강제
- 지금 단계에서 보류할 판단: 최종 우선순위와 실행 가능성 판단

## Review Check
- [x] 반복 규칙과 출력 포맷은 문서형 범위에 있다.
- [x] 순서, 상태, 재시도는 문서 밖 실행 구조 후보로 검토했다.
- [x] 문서형으로 충분한 이유 또는 부족한 이유가 한 줄로 설명된다.
```

## 완료 기준

- 한계 진단표가 작성되어 있다.
- 반복 규칙과 출력 포맷이 문서형으로 강한 부분에 들어 있다.
- 순서, 상태, 재질문, 재시도가 문서형으로 약한 부분에 들어 있다.
- 문서형으로 만들 범위와 다음 하네스가 필요한 범위가 이유와 함께 분리되어 있다.
- 프로젝트 1에서 md 템플릿에 넣을 규칙과 문서 밖 실행 구조로 넘길 위험이 구분된다.

## 제출/검토 체크리스트

- [ ] 어디까지 문서로 버틸지 판단한다는 목적이 보인다.
- [ ] 반복 규칙과 출력 포맷을 문서형 하네스의 강점으로 분류했다.
- [ ] 순서, 상태, 재시도는 문서만으로 강제하지 않는다고 표시했다.
- [ ] 다음 하네스가 필요한 범위를 과장하지 않고 후보로만 남겼다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.
