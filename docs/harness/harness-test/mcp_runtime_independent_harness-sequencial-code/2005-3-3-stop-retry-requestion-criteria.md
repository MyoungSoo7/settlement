# 2005-3-3. 내 하네스의 exit·retry·ask-back loop 만들기

## 이 산출물

2005-2-3의 Harness Graph에 `exit`, `retry`, `ask-back` route를 붙인 loop protocol. 목표는 양식을 채우는 것이 아니라, 자동 반복이 어디서 멈추고 어디로 되돌아가며 언제 사람에게 묻는지 실행 흐름으로 고정하는 것이다.

## 목적

좋아질 여지가 있다는 이유만으로 루프를 계속 돌리지 않는다. 충분히 쓸 수 있으면 `exit`, 입력은 충분하지만 실패하면 `retry`, 입력이나 판단 기준이 부족하면 `ask-back`으로 보낸다.

## 권장 시간

30분

## 준비물

- 2005-2-3 Runtime-independent Harness Graph
- 각 node의 pass condition
- 실패했던 실행 사례 1개

## 입력

| 항목 | 내용 |
|---|---|
| 하네스 이름 |  |
| 최종 산출물 |  |
| pass condition |  |
| retry 가능한 실패 |  |
| ask-back이 필요한 모호함 |  |
| exit로 멈출 조건 |  |
| checkpoint |  |

## AI에게 시키기

> 이 입력으로 exit·retry·ask-back loop를 작성해줘. 각 route에는 조건, 복귀 node, 다음 행동, 남길 상태를 붙여줘. “더 좋아질 수 있음”은 retry 사유로 쓰지 말고, satisfice 기준을 exit 조건에 포함해줘.

## 작성 템플릿

````markdown
# Loop Protocol — <하네스 이름>

## 1. Loop Graph
```text
execute -> evaluate
  -> pass: exit
  -> fail_with_enough_input: retry(checkpoint)
  -> unclear_input_or_goal: ask-back
  -> blocked_or_external_decision: exit(blocked)
```

## 2. Route Rules
| route | condition | next node | state to record |
|---|---|---|---|
| exit |  |  |  |
| retry |  |  |  |
| ask-back |  |  |  |
| blocked exit |  |  |  |

## 3. Checkpoints
| checkpoint | 저장할 상태 | 돌아오는 조건 |
|---|---|---|
| input |  |  |
| spec |  |  |
| execution |  |  |
| review |  |  |

## 4. Satisfice Rule
- 충분하다고 보는 조건:
- 더 돌리지 않는 이유:
- 남길 불확실성:

## 5. Demo Verdict
| 입력 | verdict | route | first action |
|---|---|---|---|
|  |  |  |  |
````

## 완료 기준

- route가 `exit / retry / ask-back / blocked exit`으로 분리되어 있다.
- retry에는 checkpoint와 다시 실행할 node가 있다.
- ask-back에는 사람에게 물을 질문이 있다.
- exit에는 satisfice 조건과 남길 불확실성이 있다.
- “더 좋아질 수 있음”만으로 retry하지 않는다.
