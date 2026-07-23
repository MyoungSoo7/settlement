# 2005-2-3. 분업형 하네스를 런타임 독립 하네스 그래프로 설계하기

## 이 산출물

4-4 분업형 하네스 v1과 5-1에서 가린 MCP 전환 후보·스킬 분류를 입력으로, 같은 워크플로우가 어느 런타임에서도 도는 Runtime-independent Harness Graph로 올린 설계. 특정 런타임 선호가 아니라, 어떤 런타임에서도 유지되어야 하는 workflow layer를 분리하는 것이 핵심이다.

## 목적

분업형 하네스의 안/밖 책임·단계·상태·재시도·출력(§1~6)은 그대로 가져오고, 그 위에 Capability Matrix(§7)와 Skill Bundle(§8) 한 층을 얹어 런타임 독립으로 만든다. §7·§8은 새 작업이 아니라 §1~6을 다른 각도로 분류하는 것이다.

## 권장 시간

35분 (§1~6 기존 25분 + §7~8 신규 10분)

## 준비물

- 4-4 분업형 하네스 v1
- 2005-1-3의 MCP 전환 후보 + 스킬 분류
- capability 후보: skill_dispatch · structured_output · targeted_resume · tool_call · state_store · checkpoint · guard

## 입력

| 항목 | 내용 |
|---|---|
| 분업형 하네스 (4-4) |  |
| MCP 전환 후보 (5-1) |  |
| 스킬 분류 (5-1) |  |
| workflow로 고정할 단계 |  |
| 런타임마다 달라질 표면 |  |
| 사람에게 남길 판단 |  |

## AI에게 시키기

> 4-4 분업형 하네스와 5-1 MCP 후보·스킬 분류를 입력으로, 아래 8 섹션 Runtime-independent Harness Graph를 설계해줘. §1~6은 기존 분업형 하네스를 그대로 옮기고, §7 Capability Matrix로 workflow/runtime/integration을 나누고, §8 Skill Bundle로 SKILL.md 묶음과 런타임별 호출 방식을 정리해줘.

## 작성 템플릿

````markdown
# Runtime-independent Harness Graph — <업무 이름>

## 1. Candidate Workflow
- 업무 / 시작 입력 / 최종 산출물 / 사람 판단 항목

## 2. Outside Responsibility (사람)
- 문제 정의 · 원본 입력 · 최종 판단 · 결과 공유

## 3. Inside Responsibility (코드)
| 단계 | 입력 | 처리 | 출력 | 통과 조건 |
|---|---|---|---|---|
| 입력 정리 |  |  |  |  |
| 명세 |  |  |  |  |
| 실행 |  |  |  |  |
| 검수 |  |  |  |  |

## 4. State & Checkpoints
- 현재 단계 · 결정 · 실패 이력 · 검수 결과를 어떻게 남기나

## 5. Retry & Re-question
- 자동 재시도 vs 사람 재질문 · 복귀 지점 · 다음 행동

## 6. Output Contract
- 최종 산출물 · 판단 근거 · 남은 질문 · 다음 사람이 볼 요약

## 7. Capability Matrix (NEW)
| layer | 고정/변동 | 본인 업무의 항목 |
|---|---|---|
| Workflow Layer | 어디서나 같음 |  |
| Runtime Layer | 런타임마다 다름 |  |
| Integration Surface | UX 차이 |  |

## 8. Skill Bundle (NEW)
| SKILL.md 묶음 | 선언할 capability | 런타임별 호출 방식 |
|---|---|---|
|  | skill_dispatch / structured_output / … | Claude `~/.claude/skills/` · Codex `~/.codex/skills/` · … |
````

## 완료 기준

- §1~6에 4-4 분업형 하네스가 그대로 옮겨져 있다.
- §7 Capability Matrix가 workflow / runtime / integration으로 분리되어 있다.
- §8 Skill Bundle에 SKILL.md 묶음·선언 capability·런타임별 호출이 적혀 있다.
- workflow layer(어디서나 같음)와 runtime layer(런타임마다 다름)가 한 칸에 섞이지 않았다.
- 사람에게 남길 판단(§2)이 코드 안쪽(§3)과 구분되어 있다.
