# 2003-4-1. 하네스 작성 프랙티스

## Source Mapping

- Curriculum: `curriculum.md` row `2003-4-1` in `3-4. 프로젝트 1 및 케이스 스터디` defines the clip as `[Case Study] obra/superpowers: .md로 만드는 행동 강령`, with key message `SUPERPOWERS.md 분석: 권한과 제약을 문서로 주입하는 원리` and required output `하네스 작성 프랙티스`.
- Interview: `interview.md` provides the conceptual boundary for this worksheet: Use the interview framing that students should learn how a reference harness thinks, then adapt the principle to their own domain rather than clone the source project.
- Reference repo: Superpowers is approved by `decks/approved-source-checklist.md` for chapter 03 through `lecture-decks.seed.yaml` reference repo `https://github.com/obra/superpowers`; use only observable repo paths and do not claim install or runtime behavior.
- Source-fidelity note: the `SUPERPOWERS.md` phrase above is preserved only as the canonical `curriculum.md` key-message wording. This worksheet's repo observation steps use verified initial-instruction paths such as `../../../../../AGENTS.md`, `../../../../../CLAUDE.md`, and `GEMINI.md`.
- Guardrail: follow `decks/approved-source-checklist.md`; do not add new external examples, unsupported statistics, prices, discounts, volatile marketing claims, or unverifiable repo behavior.

## Asset File Scope

This is the separate Chapter 03 practice asset for required practice clip 4, `2003-4-1`. The Superpowers reference grounding is limited to the approved chapter 03 mapping and to observable path groups already tracked in the deck/audit materials: `../../../../../README.md`, `../../../../../AGENTS.md`, `../../../../../CLAUDE.md`, `GEMINI.md`, `commands/`, `hooks/`, `tests/`, and selected `skills/.../SKILL.md` files. Learners should use those paths as document-structure observation surfaces for authority, constraints, sequence, and review points, not as install instructions or runtime proof.

## Practice Task and Prompt

- Task: create `하네스 작성 프랙티스` for clip `2003-4-1` so the learner can apply the curriculum message `SUPERPOWERS.md 분석: 권한과 제약을 문서로 주입하는 원리` to their own work.
- Prompt to paste into AI:

```text
Fast Campus 하네스 엔지니어링 실습 2003-4-1을 도와줘.

Curriculum mapping:
- Module: 3-4. 프로젝트 1 및 케이스 스터디
- Clip: [Case Study] obra/superpowers: .md로 만드는 행동 강령
- Key message: SUPERPOWERS.md 분석: 권한과 제약을 문서로 주입하는 원리
- Required output: 하네스 작성 프랙티스

Interview mapping:
- Use the interview framing that students should learn how a reference harness thinks, then adapt the principle to their own domain rather than clone the source project.

Task:
내 입력을 바탕으로 `하네스 작성 프랙티스` 초안을 작성해줘. 아래 실습자료의 작성 템플릿 구조를 따르고, 모르는 정보는 추측하지 말고 "다시 물을 질문" 또는 "확인 필요"로 남겨.

Boundaries:
- curriculum.md와 interview.md의 범위를 벗어난 외부 사례를 추가하지 않는다.
- 검증되지 않은 수치, 가격, 할인, 마케팅성 주장을 넣지 않는다.
- 최종 책임 판단이 필요한 항목은 사람이 결정할 수 있게 분리한다.

My input:
[여기에 내 업무 상황, 원문, 제약, 기존 산출물을 붙인다]
```

## 목적

Superpowers case study를 README 요약이 아니라 문서형 하네스 관찰 자료로 읽는다. repo 설치나 실행 검증 없이, 관찰 가능한 문서 경로에서 권한, 제약, 순서, 검수 포인트를 찾아 회의록 -> 1page 기획안 하네스 작성 규칙으로 옮긴다.

## 권장 시간

30분

## 준비물

- 2003-1-3에서 만든 문서 하네스 체크리스트
- 2003-2-3에서 만든 md 템플릿 v1
- 2003-3-3에서 만든 문서형 하네스 한계 진단표
- Superpowers 관찰 경로 목록: `../../../../../README.md`, `../../../../../AGENTS.md`, `../../../../../CLAUDE.md`, `GEMINI.md`, `agents/`, `commands/`, `hooks/`, `skills/`
- 회의록 -> 1page 기획안 하네스에 넣고 싶은 권한, 제약, 순서, 검수 후보

## 입력

아래 입력을 먼저 채운다.

| 항목 | 내용 |
|---|---|
| 관찰할 Superpowers 경로 |  |
| 이 경로가 보여 주는 역할 |  |
| 프로젝트 1에 옮길 권한 |  |
| 프로젝트 1에 옮길 제약 |  |
| 프로젝트 1에 옮길 순서 |  |
| 프로젝트 1에 옮길 검수 포인트 |  |
| 2003-4-2 설계도로 넘길 항목 |  |

## 단계별 진행

1. `../../../../../README.md`는 전체 흐름을 찾는 목차로만 보고, 기능 소개나 설치 결과를 요약하지 않는다.
2. `../../../../../AGENTS.md`, `../../../../../CLAUDE.md`, `GEMINI.md`에서 역할, 허용 범위, 금지선처럼 초기 지침이 행동을 고정하는 부분을 찾는다.
3. `commands/brainstorm.md`, `commands/write-plan.md`, `commands/execute-plan.md`에서 질문, 설계 확인, 계획, 실행처럼 순서를 나누는 방식을 관찰한다.
4. `skills/writing-skills/SKILL.md`, `skills/subagent-driven-development/SKILL.md`, `skills/using-superpowers/SKILL.md`에서 반복 행동을 작은 문서 단위로 분리하는 방식을 표시한다.
5. `hooks/hooks.json`, `hooks/hooks-cursor.json`, `hooks/session-start`, `tests/`는 검수와 개입 타이밍을 관찰하는 경로로만 기록한다.
6. 관찰한 내용을 권한, 제약, 순서, 검수 네 칸으로 옮긴다.
7. 각 칸을 회의록 -> 1page 기획안 하네스에 맞게 다시 쓴다.
8. 바로 md 템플릿에 넣을 항목과 2003-4-2 설계도에서 더 정리할 항목을 나눈다.

## 작성 템플릿

```markdown
# 하네스 작성 프랙티스

## 관찰 대상
- case study: Superpowers
- 설치/실행 검증 여부: 하지 않음
- 관찰 목적: 문서가 권한, 제약, 순서, 검수 포인트를 어떻게 나누는지 확인

## Superpowers 관찰표
| 관찰 경로 | 관찰할 문서형 역할 | 프로젝트 1에 옮길 해석 |
|---|---|---|
| README.md | 전체 흐름을 찾는 목차 |  |
| AGENTS.md / CLAUDE.md / GEMINI.md | 초기 지침과 금지선 |  |
| commands/ | 호출 가능한 순서 |  |
| skills/ | 반복 행동의 분리 단위 |  |
| hooks/ / tests/ | 검수와 개입 타이밍 |  |

## 프로젝트 1에 옮길 규칙
### 권한
-

### 제약
-

### 순서
1.
2.
3.

### 검수 포인트
-

## 다음 설계도로 넘길 항목
- 회의록 입력에서 먼저 분리할 항목:
- 1page 기획안 출력으로 보낼 항목:
- 확인 질문으로 남길 항목:
- md 템플릿 v1에 바로 넣을 항목:
```

## 예시

```markdown
# 하네스 작성 프랙티스

## 관찰 대상
- case study: Superpowers
- 설치/실행 검증 여부: 하지 않음
- 관찰 목적: 문서가 권한, 제약, 순서, 검수 포인트를 어떻게 나누는지 확인

## Superpowers 관찰표
| 관찰 경로 | 관찰할 문서형 역할 | 프로젝트 1에 옮길 해석 |
|---|---|---|
| README.md | 전체 흐름을 찾는 목차 | 하네스도 먼저 입력, 처리 순서, 출력 순서를 보이게 둔다 |
| AGENTS.md / CLAUDE.md / GEMINI.md | 초기 지침과 금지선 | 회의록에 없는 사실을 만들지 말라는 금지선을 앞단에 둔다 |
| commands/ | 호출 가능한 순서 | 회의록 분해 -> 기획안 맵핑 -> 확인 질문 -> 출력 검수 순서를 정한다 |
| skills/ | 반복 행동의 분리 단위 | 추출 규칙, 출력 양식, 검수 규칙을 한 문단에 섞지 않는다 |
| hooks/ / tests/ | 검수와 개입 타이밍 | 출력 전 확인 질문과 완료 체크포인트를 따로 둔다 |

## 프로젝트 1에 옮길 규칙
### 권한
- AI의 역할은 회의록과 확인된 요구사항 안에서 1page 기획안 초안을 구조화하는 것이다.

### 제약
- 회의록에 없는 배경, 수치, 사용자 니즈, 결론은 만들지 않는다.

### 순서
1. 회의록 원문을 보존한다.
2. 목표, 배경, 요구사항, 제약, 미결정 질문을 분리한다.
3. 확인된 내용만 1page 기획안 항목으로 보낸다.

### 검수 포인트
- 제목, 문제, 사용자, 해결 방향, 성공 기준, 확인 필요 항목이 분리되어 있는지 확인한다.

## 다음 설계도로 넘길 항목
- 회의록 입력에서 먼저 분리할 항목: 목표, 배경, 요구사항, 제약, 미결정 질문
- 1page 기획안 출력으로 보낼 항목: 제목, 문제, 사용자, 해결 방향, 성공 기준
- 확인 질문으로 남길 항목: 결정권자, 제외 범위, 일정, 우선순위
- md 템플릿 v1에 바로 넣을 항목: 역할, 금지사항, 출력 형식, 완료 체크
```

## 완료 기준

- 하네스 작성 프랙티스가 작성되어 있다.
- Superpowers는 설치나 실행 검증 없이 문서 구조 관찰 사례로만 사용했다.
- 권한, 제약, 순서, 검수 포인트가 각각 한 줄 이상 있다.
- 관찰한 경로와 프로젝트 1에 옮길 해석이 한 표에서 연결되어 있다.
- 2003-4-2 문서형 하네스 설계도로 넘길 입력, 출력, 질문, 템플릿 항목이 표시되어 있다.

## 제출/검토 체크리스트

- [ ] Superpowers reference는 3장 case study로만 사용했고 새 외부 사례를 추가하지 않았다.
- [ ] repo 경로는 관찰 가능한 문서 경로로만 적고 실행 결과를 주장하지 않았다.
- [ ] 권한과 제약이 회의록 -> 1page 기획안 프로젝트에 맞게 다시 쓰였다.
- [ ] 순서와 검수 포인트가 다음 설계도 작성에 바로 연결된다.
- [ ] 새로운 통계, 가격, 할인, 마케팅성 문구, 검증되지 않은 성능 주장을 넣지 않았다.
