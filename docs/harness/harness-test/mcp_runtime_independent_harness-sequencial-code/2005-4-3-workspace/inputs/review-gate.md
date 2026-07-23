# Deterministic Review Gate — L3

## 1. Input Contract

| 항목 | 내용 |
|---|---|
| handoff | `handoff-L3-result.md`의 `## From task`와 `## Result` |
| 완료조건 | 6칸(제목·배경·문제·사용자·해결 방향·성공 기준)이 각각 한 줄 이상씩 채워진다 |
| 확인할 근거 | `meeting.md:5`, `meeting.md:10`, `meeting.md:12`, `meeting.md:15`, `meeting.md:22`, `meeting.md:23`, `meeting.md:27` |
| 반드시 맞아야 할 값 | 회의 일시 `2026-05-22`; 합의 범위는 강조 화면과 자동 안내 메일 두 갈래; `CS 문의 60%`는 출처 미확인이라 본문 6칸에서 단정 금지 |
| ask-back 조건 | 성공 기준 `신청 직후 환불·일정 문의를 줄인다`가 목표 문장인지 측정 지표인지 불명확할 때 |
| exit 조건 | 환불 기준 명문화 가능 여부, CS 문의 수치 출처처럼 외부 결정자 답이 verdict를 막거나 같은 rework 사유가 2회 반복될 때 |

## 2. Review Stages

| stage | check | pass condition | fail route |
|---|---|---|---|
| format | handoff가 `From task`, `Result`, 6칸 표, 근거 줄, 남은 질문, 다음 행동을 갖췄는지 확인 | 필수 구조가 모두 있고 6칸이 각각 한 줄 이상 채워져 있음 | rework |
| evidence | 6칸의 주장과 범위 제외 주장을 `meeting.md` 줄 근거로 대조 | 핵심 주장이 지정 줄에 직접 있거나 같은 의미로 확인됨 | rework |
| false-negative | 값 누락·값 단정과 산문 표현 차이를 분리 | 수치·날짜·범위 값은 근거와 일치하고, 표현만 다른 경우는 통과 | rework / ask-back / pass |

## 3. Deterministic Checks

| 순서 | 판정 규칙 | 실패 verdict |
|---|---|---|
| 1 | 6칸 중 하나라도 비어 있으면 실패 | rework |
| 2 | 근거 줄이 없거나 존재하지 않는 파일·줄을 가리키면 실패 | rework |
| 3 | `CS 문의 60%`를 출처 확인된 값처럼 본문 6칸에 쓰면 실패 | rework |
| 4 | 합의 범위를 두 갈래가 아닌 세 갈래 이상으로 늘리면 실패 | rework |
| 5 | 환불 기준 명문화나 결제 모듈 모달 변경을 이번 범위로 넣으면 실패 | rework |
| 6 | 성공 기준이 목표 문장인지 측정 지표인지 결정해야 다음 단계가 가능하면 질문으로 전환 | ask-back |
| 7 | 외부 결정자 회신 없이는 판단할 수 없거나 같은 rework가 2회 반복되면 중단 | exit |
| 8 | 위 실패가 없고 산문 표현 차이만 있으면 통과 | pass |

## 4. Evidence Matrix

| handoff 주장 | 근거 | 판정 |
|---|---|---|
| 신청 직후 환불·일정 변경 문의가 CS에 다수 유입해 회의가 열림 | `meeting.md:5` | pass |
| 일정·환불 안내가 페이지 하단 작은 글씨라 사실상 안 읽힘 | `meeting.md:12` | pass |
| 해결 방향은 신청 직전 강조 화면과 자동 안내 메일 두 갈래 | `meeting.md:22`, `meeting.md:23` | pass |
| 페이지 전체 리뉴얼은 범위 밖 | `meeting.md:15`, `meeting.md:27` | pass |
| 성공 기준은 신청 직후 환불·일정 문의를 줄이는 것 | `meeting.md:10` | pass with ambiguity |
| `CS 문의 60%`를 본문 수치로 단정하지 않음 | `meeting.md:11`, `meeting.md:34`, `handoff-L3-result.md` 남은 질문 | pass |

## 5. False-Negative Correction

| 항목 | 보정 규칙 | 이번 입력 적용 |
|---|---|---|
| 산문 표현 | `다수 유입`, `CS 문의 발생`, `문의를 줄인다`처럼 표현이 달라도 같은 원문 의미면 살린다 | 배경·문제·성공 기준은 표현 차이만 있으므로 통과 |
| 값 단정 | 날짜, 수치, 범위 개수, 외부 결정 상태는 원문과 다르면 살리지 않는다 | `60%`를 단정하지 않았고 두 갈래 범위를 유지했으므로 통과 |
| 모호함 | 값이 틀린 것은 아니지만 다음 단계가 목표·지표를 구분해야 하면 통과로 덮지 않는다 | 성공 기준의 측정 단위·기간 미정은 ask-back |

## 6. Routing Protocol

| verdict | route | first action |
|---|---|---|
| pass | 다음 리프 또는 통합 | L4 결과와 합류해 7칸 문서로 통합 |
| rework | build 세션 | 누락 칸, 잘못된 수치, 없는 근거 줄, 범위 오염 중 하나를 한 줄로 고쳐 재제출 |
| ask-back | ask 세션 또는 사람 | 성공 기준이 목표 문장인지 측정 지표인지 확인하고, 지표라면 측정 단위와 기간을 정한다 |
| exit | 메인 세션 보고 | 운영팀 회신이나 수치 출처 확인처럼 외부 답변 대기 상태로 보존 |

## 7. Dry-run Result

- 입력: `handoff-L3-result.md`
- format: pass — 6칸 표와 근거 줄, 남은 질문, 다음 행동이 있다.
- evidence: pass — 배경, 문제, 해결 방향, 범위 밖, 성공 기준이 `meeting.md` 지정 줄로 확인된다.
- false-negative: ask-back — 산문 표현 차이는 통과하지만 성공 기준의 측정 단위·기간 미정은 다음 단계 판단을 막는 모호함이다.
- verdict: ask-back
- route: ask 세션 또는 사람
- first action: "`신청 직후 환불·일정 문의를 줄인다`를 목표 문장으로만 둘지, 성공 지표로 쓸지 확인한다. 성공 지표라면 측정 단위와 기간을 정한다."

## 8. Determinism Replay

| run | input | verdict | route | reason |
|---|---|---|---|---|
| 1 | `handoff-L3-result.md` | ask-back | ask 세션 또는 사람 | 성공 기준이 목표인지 지표인지 불명확 |
| 2 | 같은 `handoff-L3-result.md` | ask-back | ask 세션 또는 사람 | 같은 모호함이 같은 규칙 6에 걸림 |

## 9. OMC Case Study Note

관찰 근거: <https://github.com/Yeachan-Heo/oh-my-claudecode/blob/main/skills/team/SKILL.md> (read-only)

| OMC에서 관찰한 팀 에이전트 흐름 | 내 gate에 옮길 원칙 |
|---|---|
| 팀 흐름이 `team-plan -> team-prd -> team-exec -> team-verify -> team-fix`처럼 단계로 분리되어 있다 | 생성과 검증을 같은 책임으로 섞지 않는다 |
| `team-verify` 뒤 결함이 있으면 `team-fix`로 돌아가는 흐름이 있다 | 검토 결과는 감상이 아니라 다음 route를 결정해야 한다 |
| verify/fix loop에는 통과, 근거 있는 terminal outcome, 최대 시도 초과 같은 멈춤 조건이 있다 | `pass`, `rework`, `ask-back`, `exit`를 명시해 무한 재작업을 막는다 |
| stage handoff 문서가 단계 전환의 맥락 보존 장치로 쓰인다 | L3 handoff 한 장을 파일·줄 근거로 대조하고 route를 결정한다 |
| OMC에 이 실습의 deterministic review gate가 그대로 있다고 보지 않는다 | 필요성만 읽고, gate는 L3 완료조건과 회의록 근거에 맞춰 별도로 설계한다 |
