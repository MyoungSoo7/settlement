# Spec Contract (chapter-03 integrated) — frozen

> 출처: `practice-materials/chapter-03/2003-4-2-document-harness-blueprint.md`,
> `chapter-03/2003-4-3-workspace/05-2003-4-3-output.md`,
> `.claude/skills/meeting-to-1page/SKILL.md`.
> 3장 문서형 하네스를 다시 실행하지 않는다. 회의록 → 1page 7칸 변환 규칙(고정·남김·질문)을
> v2 안의 spec 계약으로 통합한다. frozen이며 ask-back 때 §4 성공 기준 칸만 갱신된다.

## 0. Contract Identity

| 항목 | 값 |
|---|---|
| contract | spec |
| version | v1 |
| frozen | true |
| derived_from | requirements-contract.md (acceptance 계승) |

## 1. Handoff Template (lock) — 3장/4장 공용 골격

handoff 한 장은 아래 구조로 잠근다. structured_contract가 이 스키마를 검증한다.

```
## From task
- 목적: / 입력: / 범위: / 금지: / 완료조건:
## Result
- 결과 요약: / 판정: (통과|재작업|재질문|종료 중 하나) / 남은 질문: / 다음 세션의 첫 행동:
```

## 2. 1page Output Contract — 7칸 (lock)

| # | 칸 | 작성 규칙 (고정) |
|---|---|---|
| 1 | 제목 | 한 줄 목표. 보존 표현 있으면 인용 포함 |
| 2 | 배경 | 회의록 관찰만 3줄 이내. 미확인 수치는 본문에서 빼고 확인 필요 항목으로 |
| 3 | 문제 | 회의 인용 그대로 |
| 4 | 사용자 또는 대상 | 회의에서 언급된 그룹만. 가공·확장 금지 |
| 5 | 해결 방향 | 확정된 요구사항만. 범위 밖 항목은 인용으로 부기 |
| 6 | 성공 기준 | 보존 표현(목표 한 줄)을 큰따옴표로 유지 (측정 지표 여부는 §4) |
| 7 | 확인 필요 항목 | 미결정 항목 전부. 결론 톤 금지 |

## 3. 고정 / 남김 / 질문 정책 (3장 척추)

| 구분 | 규칙 | 이번 입력 적용 |
|---|---|---|
| 고정(fixed) | 회의록에서 확정된 값·범위·인용은 그대로 잠근다 | 합의 범위 두 갈래(강조 화면 + 자동 메일), 범위 밖 3건 |
| 남김(remaining) | 미결정·미확인은 확인 필요 항목 한 칸으로 분리해 남긴다 | 담당자·일정, CS 60% 출처, 환불 기준 명문화, 작업 일정 승인 |
| 질문(question) | 회의록 밖 결정이 필요한 칸은 질문으로 돌린다 (L1 ask / ask-back) | 성공 기준의 목표 vs 지표 구분 |

## 4. 성공 기준 칸 — ask-back 연동 (requirements 계승)

- 초기 spec의 성공 기준(칸 6)은 보존 표현 그대로: "신청 직후 환불·일정 문의를 줄인다".
- 이 문장이 **측정 지표인지 목표 문장인지**가 미확정이면, review L6가 ask-back을 낸다.
- requirements_update가 `acceptance.metric`을 채우면, spec 성공 기준 칸도 같이 갱신해 v2로 freeze 한다.
  (예: "다음 회의까지 4주 동안 신청 직후 환불·일정 문의 비율을 15%로 감소" + 보존 표현 인용 유지)

## 5. 보존 표현 (도메인, 큰따옴표 인용 유지)

- "신청 직후 환불·일정 문의를 줄인다"
- "페이지 전체 리뉴얼은 이번 범위가 아니다"
- "환불 기준 자체가 명문화돼 있지 않다"

## 6. review_gate Profile (deterministic precheck 명세)

세 검사는 3장 `check-1page-output.sh`와 4장 `review_gate.py`를 v2 precheck로 통합한 것이다.
review_gate 노드가 이 profile대로 결정론 검사를 먼저 돌리고, 통과해야만 review_parallel로 넘어간다.

| 검사 | 규칙 | 출처 |
|---|---|---|
| format | handoff 필수 섹션(From task/Result/판정/다음 행동)과 7칸 구조 존재 | 4장 review_gate.py REQUIRED_SECTIONS / 3장 hook |
| evidence(line) | 본문의 `meeting.md:NN` 줄번호가 실제 meeting.md(36줄) 안에 존재 | 4장 review_gate.py deterministic_post_tool_review |
| forbidden(60%) | "60%"가 확인 필요 항목 위쪽 본문에 불확실성 표지 없이 단정되면 실패 | 3장 hook NUM_LEAK / 4장 review_gate.py FN(value) |
| preserve-phrase | 보존 표현 3줄이 큰따옴표 인용으로 살아 있는지 | 3장 hook PHRASES |
| confirm-section | "확인 필요 항목" 섹션 존재 | 3장 hook CONFIRM_SECTION_OK |

## 7. 잠금 규칙

- §1 handoff 스키마, §2 7칸, §3 정책, §5 보존 표현, §6 profile은 run 중 frozen.
- §4 성공 기준 칸만 ask-back으로 한 번 갱신된다.
