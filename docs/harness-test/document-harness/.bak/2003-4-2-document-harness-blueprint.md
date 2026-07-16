# 2003-4-2. 문서형 하네스 설계도 만들기

## Source Mapping

- Curriculum: `curriculum.md` row `2003-4-2` in `3-4. 프로젝트 1 및 케이스 스터디` defines the clip as `회의록 → 기획문서 변환 흐름 설계하기`, with key message `인터뷰 결과를 문서 템플릿에 맵핑하는 규칙 설계` and required output `문서형 하네스 설계도`.
- Interview: `interview.md` provides the conceptual boundary for this worksheet: Use the interview framing that students should learn how a reference harness thinks, then adapt the principle to their own domain rather than clone the source project.
- Reference repo: Superpowers is approved by `decks/approved-source-checklist.md` for chapter 03 through `lecture-decks.seed.yaml` reference repo `https://github.com/obra/superpowers`; use only observable repo paths and do not claim install or runtime behavior.
- Source-fidelity note: this worksheet uses Superpowers only through the prior `2003-4-1` observation output. Do not introduce new repo behavior claims; carry forward only the observed document-structure categories of authority, constraint, sequence, and review point.
- Guardrail: follow `decks/approved-source-checklist.md`; do not add new external examples, unsupported statistics, prices, discounts, volatile marketing claims, or unverifiable repo behavior.

## Asset File Scope

This is the separate Chapter 03 practice asset for required practice clip 5, `2003-4-2`. It turns the Superpowers-grounded observation from `2003-4-1` into a meeting-notes -> 1page planning-document blueprint. The reference grounding is applicable only as a design lens: learners reuse the categories `권한`, `제약`, `순서`, and `검수 포인트`, then map their own meeting notes into a document harness without claiming Superpowers install, execution, benchmark, adoption, or production behavior.

## Practice Task and Prompt

- Task: create `문서형 하네스 설계도` for clip `2003-4-2` so the learner can apply the curriculum message `인터뷰 결과를 문서 템플릿에 맵핑하는 규칙 설계` to their own work.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2003-4-2을 도와줘.

Curriculum mapping:
- Module: 3-4. 프로젝트 1 및 케이스 스터디
- Clip: 회의록 → 기획문서 변환 흐름 설계하기
- Key message: 인터뷰 결과를 문서 템플릿에 맵핑하는 규칙 설계
- Required output: 문서형 하네스 설계도

Interview mapping:
- Use the interview framing that students should learn how a reference harness thinks, then adapt the principle to their own domain rather than clone the source project.

Task:
내 입력을 바탕으로 `문서형 하네스 설계도` 초안을 작성해줘. 아래 실습자료의 작성 템플릿 구조를 따르고, 모르는 정보는 추측하지 말고 "다시 물을 질문" 또는 "확인 필요"로 남겨.

Boundaries:
- curriculum.md와 interview.md의 범위를 벗어난 외부 사례를 추가하지 않는다.
- 검증되지 않은 수치, 가격, 할인, 마케팅성 주장을 넣지 않는다.
- 최종 책임 판단이 필요한 항목은 사람이 결정할 수 있게 분리한다.

My input:
[여기에 내 업무 상황, 원문, 제약, 기존 산출물을 붙인다]
```

## 목적

회의록 입력을 1page 기획문서 출력으로 바꾸기 전에, 어떤 내용을 추출하고 어떤 항목으로 보낼지 설계한다. 확인되지 않은 내용은 기획문서에 넣지 않고 확인 질문으로 남기는 문서형 하네스 설계도를 만든다.

## 권장 시간

30분

## 준비물

- 2003-4-1에서 만든 하네스 작성 프랙티스
- 2003-2-3에서 만든 md 템플릿 v1
- 회의록 샘플 또는 수업 중 사용할 짧은 회의 기록
- 1page 기획문서에 필요한 출력 항목: 제목, 문제, 사용자, 해결 방향, 성공 기준, 확인 필요 항목
- 입력에 없는 사실을 만들지 않기 위한 금지사항 목록

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 회의록 원문 |  |
| 확인된 요구사항 |  |
| 목표 또는 배경 |  |
| 요구사항 또는 제약 |  |
| 미결정 질문 |  |
| 출력으로 보낼 항목 |  |
| 확인 질문으로 남길 항목 |  |

## 단계별 진행

1. 회의록 원문을 먼저 붙여 넣고, 원문에 없는 내용을 추가하지 않는다.
2. 회의록에서 목표, 배경, 요구사항, 제약, 미결정 질문을 분리할 입력 칸을 만든다.
3. 각 입력 칸을 1page 기획문서의 제목, 문제, 사용자, 해결 방향, 성공 기준, 확인 필요 항목 중 어디로 보낼지 표시한다.
4. 회의록에서 확인되지 않은 배경, 수치, 사용자 니즈, 결론은 출력으로 보내지 않고 확인 질문으로 남긴다.
5. 2003-4-1에서 정리한 권한, 제약, 순서, 검수 포인트를 설계도 오른쪽에 붙인다.
6. md 템플릿 v1에 들어갈 항목과 프로젝트 1 산출물에서 예시로 보여 줄 항목을 구분한다.
7. 입력 -> 추출 -> 맵핑 -> 출력 -> 확인 질문 흐름이 끊기지 않는지 점검한다.

## 작성 템플릿

```markdown
# 문서형 하네스 설계도

## 목적
- 회의록을 1page 기획문서 초안으로 바꾼다.
- 확인되지 않은 내용은 결론으로 쓰지 않고 질문으로 남긴다.

## 입력 분해
| 입력 항목 | 회의록에서 찾을 내용 | 비어 있을 때 처리 |
|---|---|---|
| 목표 |  | 확인 질문 |
| 배경 |  | 확인 질문 |
| 요구사항 |  | 확인 질문 |
| 제약 |  | 확인 질문 |
| 미결정 질문 |  | 확인 필요 항목 |

## 출력 맵핑
| 추출 항목 | 1page 기획문서 항목 | 작성 원칙 |
|---|---|---|
| 목표 / 배경 | 제목 / 배경 |  |
| 문제 | 문제 |  |
| 사용자 또는 대상 | 사용자 |  |
| 요구사항 | 해결 방향 |  |
| 제약 / 성공 조건 | 성공 기준 |  |
| 비어 있는 정보 | 확인 필요 항목 |  |

## 금지사항
- 회의록에 없는 사실을 추가하지 않는다.
- 확정되지 않은 일정, 수치, 우선순위를 결론처럼 쓰지 않는다.
- 불명확한 내용은 확인 필요 항목으로 남긴다.

## 처리 순서
1. 원문 보존
2. 입력 항목 분해
3. 출력 항목 맵핑
4. 확인 질문 분리
5. 1page 기획문서 초안 작성

## 설계 검수
- [ ] 입력에서 출력까지 맵핑이 끊기지 않는다.
- [ ] 없는 내용은 질문으로 남긴다.
- [ ] md 템플릿 v1에 넣을 목적, 입력, 규칙, 출력, 금지사항이 보인다.
```

## 예시

```markdown
# 문서형 하네스 설계도

## 목적
- 회의록을 1page 기획문서 초안으로 바꾼다.
- 확인되지 않은 내용은 결론으로 쓰지 않고 질문으로 남긴다.

## 입력 분해
| 입력 항목 | 회의록에서 찾을 내용 | 비어 있을 때 처리 |
|---|---|---|
| 목표 | 신규 참여자가 필요한 정보를 한 번에 찾게 한다 | 비어 있지 않음 |
| 배경 | 문서가 흩어져 있고 같은 질문이 반복된다 | 비어 있지 않음 |
| 요구사항 | 첫 검토자가 빠진 정보를 확인할 수 있어야 한다 | 비어 있지 않음 |
| 제약 | 기존 문서를 모두 새로 쓰지는 않는다 | 비어 있지 않음 |
| 미결정 질문 | 포함할 문서 범위와 제외할 항목 | 확인 필요 항목 |

## 출력 맵핑
| 추출 항목 | 1page 기획문서 항목 | 작성 원칙 |
|---|---|---|
| 목표 / 배경 | 제목 / 배경 | 한 문장으로 요약한다 |
| 문제 | 문제 | 반복되는 질문과 검토 비용을 분리한다 |
| 사용자 또는 대상 | 사용자 | 신규 참여자와 첫 검토자로 나눈다 |
| 요구사항 | 해결 방향 | 필요한 문서 위치, 첫 실행 순서, 확인 질문을 묶는다 |
| 제약 / 성공 조건 | 성공 기준 | 첫 검토자가 누락 정보를 찾을 수 있어야 한다 |
| 비어 있는 정보 | 확인 필요 항목 | 문서 범위와 제외 항목을 질문으로 남긴다 |

## 금지사항
- 회의록에 없는 사실을 추가하지 않는다.
- 확정되지 않은 일정, 수치, 우선순위를 결론처럼 쓰지 않는다.
- 불명확한 내용은 확인 필요 항목으로 남긴다.

## 처리 순서
1. 원문 보존
2. 입력 항목 분해
3. 출력 항목 맵핑
4. 확인 질문 분리
5. 1page 기획문서 초안 작성

## 설계 검수
- [x] 입력에서 출력까지 맵핑이 끊기지 않는다.
- [x] 없는 내용은 질문으로 남긴다.
- [x] md 템플릿 v1에 넣을 목적, 입력, 규칙, 출력, 금지사항이 보인다.
```

## 완료 기준

- 문서형 하네스 설계도가 작성되어 있다.
- 회의록 입력 항목이 목표, 배경, 요구사항, 제약, 미결정 질문으로 분리되어 있다.
- 분리한 입력이 1page 기획문서 항목으로 맵핑되어 있다.
- 확인되지 않은 내용은 출력이 아니라 확인 질문으로 남겨져 있다.
- 2003-4-3 프로젝트 1 산출물에 넣을 목적, 입력, 처리 순서, 출력, 금지사항이 보인다.

## 제출/검토 체크리스트

- [ ] 인터뷰 결과나 회의록 입력을 문서 템플릿에 맵핑하는 규칙이 중심이다.
- [ ] 없는 배경, 수치, 사용자 니즈, 결론을 만들지 않는 금지사항이 있다.
- [ ] 입력 -> 추출 -> 출력 -> 확인 질문 흐름이 한눈에 보인다.
- [ ] 2003-4-1의 권한, 제약, 순서, 검수 포인트가 설계도에 반영되어 있다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.
