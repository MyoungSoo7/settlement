# 2004-3-3. deterministic review gate와 exit rule 만들기

## 이 산출물

본인 리프 하나를 `review -> rework -> ask-back -> exit` 흐름으로 돌릴 수 있는 deterministic review gate 한 장. `oh-my-claudecode`는 설치하거나 실행하는 대상이 아니며, 우리가 만들 검증 gate가 이미 들어 있는 사례도 아니다. 팀 에이전트가 실행·검토·수정 흐름을 어떻게 운영하는지 살펴보고, 왜 별도의 deterministic gate가 필요한지 읽는 case study로만 사용한다.

## 목적

검수는 점수를 매기는 일이 아니라 다음 행동을 결정하는 일이다. 4-2 handoff로 돌아온 결과를 형식, 증거, false-negative 보정으로 확인하고, 통과·재작업·ask-back·exit 중 하나로 라우팅한다. 목표는 “더 오래 돌리기”가 아니라 같은 입력이면 같은 verdict와 route가 나오는 deterministic review gate를 만드는 것이다.

## 권장 시간

25분

## 준비물

- 2004-2-3에서 만든 handoff 양식 v1과 가상 결과 한 장
- 그 리프의 완료조건 한 줄
- `oh-my-claudecode` repo URL: `https://github.com/Yeachan-Heo/oh-my-claudecode` (read-only 구조 관찰만)

## 입력

| 항목 | 내용 |
|---|---|
| 리프 ID |  |
| 리프 완료조건 |  |
| handoff 결과 요약 |  |
| 근거로 확인할 파일·줄 |  |
| 값으로 반드시 맞아야 할 항목 |  |
| ask-back이 필요한 모호함 |  |
| exit로 멈출 조건 |  |

## AI에게 시키기

위 입력을 채워 그대로 붙인 뒤, 한 줄을 덧붙인다.

> 이 입력으로 deterministic review gate를 만들어줘. 형식 -> 증거 -> false-negative 보정 순서로 검수하고, 결과는 pass / rework / ask-back / exit 중 하나로 라우팅해. oh-my-claudecode는 검증 gate 구현 사례로 말하지 말고, 팀 에이전트 흐름에서 gate 필요성을 읽는 read-only 관찰 근거로만 써. 설치·실행·성능 주장은 하지 마.

## 단계별 진행

1. handoff가 필수 칸을 갖췄는지 확인한다.
2. 각 주장이 근거 줄에 실제로 있는지 대조한다.
3. 수치·날짜·ID 같은 값은 근거와 일치해야 통과시킨다.
4. 산문 표현 차이만 있는 경우는 false-negative로 보고 되살린다.
5. 입력 자체가 모호하면 `ask-back`으로 돌린다.
6. 같은 실패가 반복되거나 외부 결정이 필요하면 `exit`로 멈춘다.
7. 같은 가상 결과를 새 세션에 다시 넣어 같은 라우팅이 나오는지 확인한다.

## 작성 템플릿

````markdown
# Deterministic Review Gate — <리프 ID>

## 1. Input Contract
| 항목 | 내용 |
|---|---|
| handoff |  |
| 완료조건 |  |
| 확인할 근거 |  |

## 2. Review Stages
| stage | check | pass condition | fail route |
|---|---|---|---|
| format | 필수 칸과 handoff 구조 | 모두 채워져 있음 | rework |
| evidence | 주장과 파일:줄 근거 대조 | 주장마다 근거가 있음 | rework |
| false-negative | 값 누락과 표현 차이 분리 | 값은 일치, 산문 차이는 허용 | rework / pass |

## 3. Routing Protocol
| verdict | route | first action |
|---|---|---|
| pass | 다음 리프 또는 통합 |  |
| rework | build 세션 |  |
| ask-back | ask 세션 또는 사람 |  |
| exit | 메인 세션 보고 |  |

## 4. Dry-run Result
- 입력:
- verdict:
- route:
- first action:

## 5. OMC Case Study Note
| OMC에서 관찰한 흐름 | 내 gate에 옮길 원칙 |
|---|---|
| team 단계가 plan/execute/verify/fix처럼 분리됨 | 생성과 검증을 같은 책임으로 섞지 않는다 |
| 검토와 수정 흐름이 실행 뒤에 따로 붙음 | handoff 한 장을 deterministic gate에 통과시킨다 |
| fix/rework가 별도 흐름으로 돌아감 | 실패 사유를 한 줄로 좁혀 route를 결정한다 |
| OMC에 우리 gate가 그대로 있지는 않음 | 필요성을 읽고, gate는 우리 업무 단위로 직접 설계한다 |
````

## 예시

````markdown
# Deterministic Review Gate — L3

## 1. Input Contract
| 항목 | 내용 |
|---|---|
| handoff | 기능 후보 F1~F3 + 근거 |
| 완료조건 | 후보마다 사용자 행동, 우선순위, 미확인 항목이 있다 |
| 확인할 근거 | meeting.md:10, meeting.md:22 |

## 2. Review Stages
| stage | check | pass condition | fail route |
|---|---|---|---|
| format | 후보 표 4칸과 근거 칸 | 모두 채워짐 | rework |
| evidence | 후보 주장과 회의록 줄 대조 | 각 주장에 근거 줄 있음 | rework |
| false-negative | 수치·날짜·ID 확인 | 값은 근거와 일치 | rework / pass |

## 3. Routing Protocol
| verdict | route | first action |
|---|---|---|
| pass | 통합 | L4와 합류 |
| rework | build 세션 | F2의 30% 근거를 달거나 수치를 제거 |
| ask-back | ask 세션 | “재방문율 개선”이 목표인지 지표인지 확인 |
| exit | 메인 세션 보고 | 외부 결정자 답변 대기 상태로 보존 |

## 4. Dry-run Result
- 입력: F2에 “30% 개선”이 있으나 meeting.md:22에 30% 없음
- verdict: rework
- route: build 세션
- first action: F2의 수치 근거를 추가하거나 수치를 제거
````

## 완료 기준

- 산출물이 `deterministic review gate` 형태로 정리되어 있다.
- 결과 라우팅이 `pass / rework / ask-back / exit` 네 가지로 분리되어 있다.
- evidence stage가 자기보고가 아니라 파일·줄 근거를 직접 대조한다.
- false-negative stage가 값 누락과 산문 표현 차이를 분리한다.
- 같은 가상 결과를 두 번 넣었을 때 같은 verdict가 나온다.
