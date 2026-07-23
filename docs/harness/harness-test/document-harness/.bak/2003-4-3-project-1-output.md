# 2003-4-3. 프로젝트 1 산출물: 회의록 -> 1페이지 기획문서 하네스 v1

## Source Mapping

- Curriculum: `curriculum.md` row `2003-4-3` in `3-4. 프로젝트 1 및 케이스 스터디` defines the clip as `[프로젝트 1] 회의록 → 1페이지 기획문서 하네스 v1`, with key message `비개발자도 복붙해서 쓸 수 있는 문서형 하네스 완성` and required output `[산출물] 프로젝트 1`.
- Interview: `interview.md` provides the conceptual boundary for this worksheet: Use the interview framing that students should learn how a reference harness thinks, then adapt the principle to their own domain rather than clone the source project.
- Reference repo: Superpowers is approved by `decks/approved-source-checklist.md` for chapter 03 through `lecture-decks.seed.yaml` reference repo `https://github.com/obra/superpowers`; use only observable repo paths and do not claim install or runtime behavior.
- Source-fidelity note: this final project asset does not add a new Superpowers analysis. It reuses the `2003-4-1` observation and the `2003-4-2` blueprint as learner-owned inputs, keeping the reference case limited to document-structure grounding.
- Guardrail: follow `decks/approved-source-checklist.md`; do not add new external examples, unsupported statistics, prices, discounts, volatile marketing claims, or unverifiable repo behavior.

## Asset File Scope

This is the separate Chapter 03 practice asset for required practice clip 6, `2003-4-3`. It consolidates the learner's earlier Chapter 03 outputs into the final Project 1 markdown harness. Superpowers grounding is applicable only through the learner's `2003-4-1` practice result: the finished harness should preserve the adapted authority, constraints, sequence, and review points, while remaining a meeting-notes -> 1page planning-document tool rather than a copy of the reference repository.

## Practice Task and Prompt

- Task: create `[산출물] 프로젝트 1` for clip `2003-4-3` so the learner can apply the curriculum message `비개발자도 복붙해서 쓸 수 있는 문서형 하네스 완성` to their own work.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2003-4-3을 도와줘.

Curriculum mapping:
- Module: 3-4. 프로젝트 1 및 케이스 스터디
- Clip: [프로젝트 1] 회의록 → 1페이지 기획문서 하네스 v1
- Key message: 비개발자도 복붙해서 쓸 수 있는 문서형 하네스 완성
- Required output: [산출물] 프로젝트 1

Interview mapping:
- Use the interview framing that students should learn how a reference harness thinks, then adapt the principle to their own domain rather than clone the source project.

Task:
내 입력을 바탕으로 `[산출물] 프로젝트 1` 초안을 작성해줘. 아래 실습자료의 작성 템플릿 구조를 따르고, 모르는 정보는 추측하지 말고 "다시 물을 질문" 또는 "확인 필요"로 남겨.

Boundaries:
- curriculum.md와 interview.md의 범위를 벗어난 외부 사례를 추가하지 않는다.
- 검증되지 않은 수치, 가격, 할인, 마케팅성 주장을 넣지 않는다.
- 최종 책임 판단이 필요한 항목은 사람이 결정할 수 있게 분리한다.

My input:
[여기에 내 업무 상황, 원문, 제약, 기존 산출물을 붙인다]
```

## 목적

2003-1-3, 2003-2-3, 2003-3-3, 2003-4-1, 2003-4-2의 산출물을 하나로 묶어 비개발자도 복사해서 쓸 수 있는 문서형 하네스 v1을 완성한다. 회의록을 넣으면 확인 질문과 1page 기획문서 초안으로 반복 수렴하도록 목적, 입력, 추출 규칙, 출력 양식, 금지사항을 md 템플릿에 고정한다.

## 권장 시간

40분

## 준비물

- 2003-1-3 문서 하네스 체크리스트
- 2003-2-3 md 템플릿 v1
- 2003-3-3 문서형 하네스 한계 진단표
- 2003-4-1 하네스 작성 프랙티스
- 2003-4-2 문서형 하네스 설계도
- 프로젝트 1에 사용할 회의록 원문 1개
- 사람이 최종 판단해야 할 항목 목록

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 프로젝트 이름 | 회의록 -> 1페이지 기획문서 하네스 v1 |
| 회의록 원문 |  |
| 확인된 요구사항 |  |
| 반드시 보존할 표현 |  |
| 비어 있으면 질문할 항목 |  |
| 최종 1page 기획문서 항목 |  |
| 사람이 판단할 영역 |  |

## 단계별 진행

1. md 템플릿 v1을 열고 프로젝트 이름을 "회의록 -> 1페이지 기획문서 하네스 v1"로 바꾼다.
2. 2003-1-3 체크리스트에서 역할, 목표, 금지사항, 출력 형식을 가져온다.
3. 2003-3-3 한계 진단표에서 문서형으로 충분한 범위와 사람이 판단할 범위를 분리한다.
4. 2003-4-1 하네스 작성 프랙티스에서 권한, 제약, 순서, 검수 포인트를 가져온다.
5. 2003-4-2 설계도에서 입력 분해표와 출력 맵핑표를 가져와 추출 규칙으로 정리한다.
6. 금지사항에는 회의록에 없는 사실 추가, 확정되지 않은 결론 단정, 빈칸 임의 보강을 막는 문장을 넣는다.
7. 출력 양식은 제목, 배경, 문제, 사용자, 해결 방향, 성공 기준, 확인 필요 항목으로 고정한다.
8. 샘플 회의록으로 한 번 실행해 보고, 확인 질문과 1page 기획문서 형식이 둘 다 나오는지 점검한다.
9. 사람이 최종 결정해야 하는 우선순위, 일정, 실행 가능성 판단은 하네스 밖 검토 항목으로 남긴다.

## 작성 템플릿

```markdown
# 회의록 -> 1페이지 기획문서 하네스 v1

## Role
- 너의 역할:
- 책임 범위:
- 책임지지 않을 범위:

## 입력
- 회의록 원문:
- 확인된 요구사항:
- 반드시 보존할 표현:
- 비어 있으면 질문할 항목:

## Processing Rules
1. 회의록 원문과 확인된 요구사항만 사용한다.
2. 목표, 배경, 요구사항, 제약, 미결정 질문을 먼저 분리한다.
3. 확인된 내용만 1page 기획문서 항목으로 보낸다.
4. 불명확한 내용은 결론으로 쓰지 않고 확인 필요 항목으로 남긴다.

## Forbidden
- 회의록에 없는 사실, 수치, 사용자 니즈, 외부 사례를 추가하지 않는다.
- 확정되지 않은 일정, 담당자, 우선순위를 확정된 결론처럼 쓰지 않는다.
- 사람이 판단해야 할 최종 의사결정을 대신하지 않는다.

## Output Format
1. 제목
2. 배경
3. 문제
4. 사용자 또는 대상
5. 해결 방향
6. 성공 기준
7. 확인 필요 항목

## Review Check
- [ ] 입력에 없는 내용이 추가되지 않았다.
- [ ] 확인 질문과 기획문서 항목이 분리되어 있다.
- [ ] 제목, 문제, 해결 방향, 성공 기준이 한 페이지 안에서 읽힌다.
- [ ] 사람이 판단할 항목이 남아 있다.

## Example Input
-

## Example Output
-
```

## 예시

```markdown
# 회의록 -> 1페이지 기획문서 하네스 v1

## Role
- 너의 역할: 회의록을 읽고 1page 기획문서 초안을 구조화하는 보조자
- 책임 범위: 회의록 원문과 확인된 요구사항 안에서 배경, 문제, 사용자, 해결 방향, 성공 기준, 확인 필요 항목을 정리한다.
- 책임지지 않을 범위: 없는 사실 보강, 최종 우선순위 확정, 실행 가능성 단정

## 입력
- 회의록 원문: 온보딩 문서가 흩어져 있어 신규 참여자가 같은 질문을 반복한다. 첫 검토자가 빠진 정보를 확인할 수 있어야 한다. 기존 문서를 모두 새로 쓰지는 않는다.
- 확인된 요구사항: 신규 참여자가 필요한 정보를 한 번에 찾게 한다.
- 반드시 보존할 표현: "기존 문서를 모두 새로 쓰지는 않는다"
- 비어 있으면 질문할 항목: 포함할 문서 범위, 제외할 항목, 최종 검토자

## Processing Rules
1. 회의록 원문과 확인된 요구사항만 사용한다.
2. 목표, 배경, 요구사항, 제약, 미결정 질문을 먼저 분리한다.
3. 확인된 내용만 1page 기획문서 항목으로 보낸다.
4. 불명확한 내용은 결론으로 쓰지 않고 확인 필요 항목으로 남긴다.

## Forbidden
- 회의록에 없는 사실, 수치, 사용자 니즈, 외부 사례를 추가하지 않는다.
- 확정되지 않은 일정, 담당자, 우선순위를 확정된 결론처럼 쓰지 않는다.
- 사람이 판단해야 할 최종 의사결정을 대신하지 않는다.

## Output Format
1. 제목
2. 배경
3. 문제
4. 사용자 또는 대상
5. 해결 방향
6. 성공 기준
7. 확인 필요 항목

## Review Check
- [x] 입력에 없는 내용이 추가되지 않았다.
- [x] 확인 질문과 기획문서 항목이 분리되어 있다.
- [x] 제목, 문제, 해결 방향, 성공 기준이 한 페이지 안에서 읽힌다.
- [x] 사람이 판단할 항목이 남아 있다.

## Example Input
- 온보딩 문서가 흩어져 있어 신규 참여자가 같은 질문을 반복한다. 첫 검토자가 빠진 정보를 확인할 수 있어야 한다. 기존 문서를 모두 새로 쓰지는 않는다.

## Example Output
1. 제목: 신규 참여자 온보딩 문서 정리 초안
2. 배경: 온보딩 문서가 여러 곳에 흩어져 있다.
3. 문제: 신규 참여자가 같은 질문을 반복하고, 첫 검토자가 누락 정보를 매번 확인해야 한다.
4. 사용자 또는 대상: 신규 참여자, 첫 검토자
5. 해결 방향: 필요한 문서 위치, 첫 실행 순서, 확인 질문을 한 페이지로 묶는다.
6. 성공 기준: 첫 검토자가 빠진 정보를 확인할 수 있다.
7. 확인 필요 항목: 포함할 문서 범위, 제외할 항목, 최종 검토자
```

## 완료 기준

- 프로젝트 1 산출물인 "회의록 -> 1페이지 기획문서 하네스 v1"이 작성되어 있다.
- 목적, 입력, 추출 규칙, 출력 양식, 금지사항이 md 템플릿 안에 들어 있다.
- 회의록에 없는 내용은 확인 필요 항목으로 남기게 되어 있다.
- 출력은 제목, 배경, 문제, 사용자 또는 대상, 해결 방향, 성공 기준, 확인 필요 항목을 포함한다.
- 사람이 최종 판단해야 하는 우선순위, 일정, 실행 가능성 판단을 하네스가 대신하지 않는다.

## 제출/검토 체크리스트

- [ ] 2003-1-3, 2003-2-3, 2003-3-3, 2003-4-1, 2003-4-2 산출물이 프로젝트 1 하네스에 반영되어 있다.
- [ ] 비개발자도 복사해서 쓸 수 있는 md 규칙서 형태다.
- [ ] 확인 질문과 1page 기획문서 초안이 분리되어 나온다.
- [ ] "스펙 먼저, 실행은 그 다음"이라는 수업 흐름이 문서 안에서 지켜진다.
- [ ] 새로운 외부 사례, 검증되지 않은 수치, 가격 또는 마케팅성 정보를 넣지 않았다.
