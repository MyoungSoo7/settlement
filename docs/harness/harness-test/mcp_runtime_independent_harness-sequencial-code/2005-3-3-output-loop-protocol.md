# Loop Protocol — meeting-to-1page 분업 하네스

> 입력: `practice-materials/chapter-05/2005-2-3-mcp-design-draft.md`
> 보조 근거: 같은 입력을 채운 산출물 `practice-materials/chapter-05/2005-2-3-output-runtime-independent-harness-graph.md`
> 목적: Runtime-independent Harness Graph의 `fill_handoff -> dispatch -> review_gate -> route` 흐름에 `exit / retry / ask-back / blocked exit` 전이를 고정한다. "더 좋아질 수 있음"은 retry 사유가 아니며, 충분히 쓸 수 있으면 멈춘다.

---

## 1. Loop Graph

```text
input(meeting.md)
  -> fill_handoff
  -> dispatch
  -> execute(sub agent)
  -> review_gate
  -> route
      -> pass: next leaf or exit
      -> fail_with_enough_input: retry(checkpoint -> return node)
      -> unclear_input_or_goal: ask-back(L1 ask / human)
      -> blocked_or_external_decision: exit(blocked)
```

### Node Map

| node | 역할 | pass condition |
|---|---|---|
| `fill_handoff` | leaf + 직전 verdict/route를 handoff 한 장으로 정리 | From task 4칸, 검수 대상, 근거 경로가 한 화면에 들어온다 |
| `dispatch` | 리프 1개를 sub agent 1명에게 보낸다 | 들어가고 나오는 것이 언제나 handoff 한 장이다 |
| `execute` | ask/build/review 리프 작업을 수행한다 | 결과 요약, 판정, 남은 질문, 다음 행동이 handoff out에 닫힌다 |
| `review_gate` | format/evidence/FN guard와 의미 판정을 route로 바꾼다 | 결정론 3검사는 매번 같은 결과이고, 의미 판정은 verdict 한 줄로 닫힌다 |
| `route` | pass/retry/ask-back/exit 중 하나만 남긴다 | 다음 node, 복귀 checkpoint, 남길 상태가 함께 기록된다 |

---

## 2. Route Rules

| route | condition | return node | next action | state to record |
|---|---|---|---|---|
| `exit` | 모든 필수 칸이 채워졌고, L5/L6 guard가 통과했으며, 남은 불확실성이 "확인 필요 항목"으로 분리되어 다음 사람이 쓸 수 있다. 즉 7칸 1page 초안이 목적에 충분히 맞는다. | 없음 | 최종 1page, 리프별 verdict, 남은 질문, 판단 근거를 보고하고 루프를 닫는다. | `final_artifact`, `verdict=pass`, `route=exit`, `satisfice_reason`, `known_uncertainties`, `source_lines`, `exit_declared_by=main` |
| `retry` | 입력과 판단 기준은 충분하지만 실행 결과가 guard를 어겼다. 예: 누락 칸, 없는 `meeting.md:NN` 근거 줄, 금지값 `60%` 단정, 범위 오염, handoff 형식 누락. 단순히 더 매끄럽게 만들 수 있다는 이유는 제외한다. | 실패한 leaf의 직전 checkpoint. L5 evidence 실패는 `execute(build L3+L4)`, L6 값/범위 실패도 `execute(build L3+L4)`, handoff 형식 실패는 `fill_handoff`. | 실패 사유 한 줄만 고쳐 같은 leaf를 재실행한다. 통과한 leaf와 기존 결정은 보존하고 전체 재작성은 하지 않는다. | `checkpoint_id`, `failed_guard`, `failure_reason`, `retry_count_by_reason`, `preserved_passes`, `return_node`, `patch_scope` |
| `ask-back` | 결과를 고칠 입력이 부족하거나 사람 판단 없이는 다음 node가 의미 있게 진행되지 않는다. 예: 성공 기준이 목표 문장인지 측정 지표인지 불명확함, 지표라면 단위/기간 미정, 회의록 밖 수치·정책·담당자 확정 필요. | `L1 ask` 또는 사람 의사결정자. 답을 받으면 해당 결정을 `spec` checkpoint에 반영하고 `execute(build L3+L4)`로 복귀한다. | 막힌 항목만 질문한다. 질문: "성공 기준 `신청 직후 환불·일정 문의를 줄인다`를 목표 문장으로 둘까요, 측정 지표로 둘까요? 지표라면 감소율과 기간은 무엇인가요?" | `pending_question`, `decision_owner`, `blocked_field`, `source_context`, `resume_target`, `pending_external=true`, `ask_back_round` |
| `blocked exit` | 같은 retry 사유가 2회 반복되었거나, 외부 회신 없이는 더 진행할 수 없고 현재 산출물의 한계가 명확하다. 또는 필요한 파일/권한/회의록 원본이 없어 review 근거를 만들 수 없다. | 없음. 외부 답이나 새 입력이 오면 `targeted_resume`으로 저장된 `resume_target`에서 재개한다. | 진행 상태와 막힌 이유를 보고한다. 추정으로 답을 만들지 않고, 현재까지 통과한 결과와 필요한 외부 결정을 분리해 넘긴다. | `verdict=blocked`, `route=exit(blocked)`, `blocker_reason`, `retry_count_by_reason`, `pending_external`, `resume_target`, `last_good_checkpoint` |

---

## 3. Checkpoints

| checkpoint | 저장할 상태 | 돌아오는 조건 |
|---|---|---|
| `input` | `meeting.md` 경로, 원문 버전, 대상 leaf, 시작 요청 | 원본 회의록이 바뀌었거나 근거 줄이 사라져 evidence guard가 다시 필요할 때 |
| `spec` | handoff 입력, 성공 기준 결정, 사람 확정값, 남은 질문 목록 | ask-back 답이 돌아와 목표/지표/수치/기간/담당자 같은 판단 기준이 바뀔 때 |
| `execution` | leaf별 handoff out, sub agent 결과 요약, 통과한 leaf 목록 | retry가 필요한데 입력과 기준은 충분해서 같은 leaf만 좁혀 고칠 때 |
| `review` | L5/L6 verdict, failed guard, route, retry 카운터, 근거 줄 | review gate 규칙만 다시 적용하거나 같은 실패 반복 여부를 판단할 때 |

State schema:

```yaml
leaf: L6
node: review_gate
verdict: ask-back
route: ask-back
checkpoint_id: spec:success_metric
return_node: L1 ask
resume_target: execute(build L3+L4)
retry_count_by_reason:
  evidence_missing: 0
  value_overclaim: 0
  scope_pollution: 0
pending_external: true
pending_question: "성공 기준을 목표 문장으로 둘지 측정 지표로 둘지, 지표라면 단위와 기간은 무엇인지 확인"
preserved_passes:
  - L1 ask
  - L3+L4 build format
  - L5 evidence
```

---

## 4. Satisfice Rule

- 충분하다고 보는 조건:
  - 7칸 1page 초안이 완성되어 다음 사람이 읽고 판단할 수 있다.
  - 필수 guard(format, evidence, 금지값 단정, 범위 오염)가 통과했다.
  - 회의록 밖 값은 `의사결정자 ask-back 확정` 또는 `확인 필요 항목`으로 구분되어 있다.
  - 남은 불확실성이 본문 단정으로 섞이지 않고 별도 목록에 남아 있다.
  - 리프별 verdict와 근거 줄이 남아 재검토 가능한 상태다.
- 더 돌리지 않는 이유:
  - 목적은 완벽한 문장 개선이 아니라 회의록을 의사결정 가능한 1page 초안으로 옮기는 것이다.
  - guard를 통과한 뒤의 표현 개선, 더 좋은 배열, 더 예쁜 요약은 자동 retry 사유가 아니다.
  - 같은 입력에서 추가 반복이 새 근거를 만들지 못하면 품질 상승보다 drift와 비용이 커진다.
- 남길 불확실성:
  - 최종 담당자와 일정.
  - `60%` 출처.
  - 환불 기준 명문화 여부.
  - 감소율 기준선과 측정 기간이 외부 확정값인지 여부.
  - 인용이 주장을 충분히 뒷받침하는지에 대한 사람의 최종 채택 판단.

---

## 5. Demo Verdict

| 입력 | verdict | route | first action |
|---|---|---|---|
| L5 evidence review에서 6칸 중 하나가 없는 `meeting.md:NN` 줄을 근거로 삼음 | fail_with_enough_input | `retry` | `review` checkpoint에 failed guard를 저장하고 `execute(build L3+L4)`로 돌아가 해당 근거 줄만 교체한다 |
| L6 false-negative review에서 성공 기준이 목표인지 지표인지 불명확함 | unclear_input_or_goal | `ask-back` | `L1 ask / 사람`에게 성공 기준의 유형, 지표라면 단위와 기간을 묻는다 |
| L1 통과, build 통과, L5 재작업 후 통과, L6 ask-back 회수 후 통과 | pass | `exit` | 7칸 1page 초안, 리프별 verdict, 확인 필요 항목을 보고하고 루프를 닫는다 |
| 같은 evidence_missing retry가 2회 반복되거나 외부 의사결정자가 답하지 않음 | blocked_or_external_decision | `blocked exit` | last good checkpoint와 pending question을 남기고 추정 없이 중단한다 |

---

## 6. Route Invariants

- route는 한 번에 하나만 남긴다: `exit`, `retry`, `ask-back`, `blocked exit`.
- retry는 checkpoint와 return node 없이 기록하지 않는다.
- ask-back은 사람에게 물을 질문, decision owner, 답을 받은 뒤 resume target을 함께 남긴다.
- exit은 satisfice reason과 남길 불확실성을 함께 남긴다.
- "더 좋아질 수 있음", "문장이 더 자연스러울 수 있음", "더 자세히 쓸 수 있음"은 retry 사유가 아니다.
- 통과한 leaf는 보존한다. 실패한 leaf의 좁은 범위만 재실행한다.

---

## 7. 자기 검수

- [x] route가 `exit / retry / ask-back / blocked exit`으로 분리되어 있다.
- [x] retry에는 checkpoint와 다시 실행할 node가 있다.
- [x] ask-back에는 사람에게 물을 질문이 있다.
- [x] exit에는 satisfice 조건과 남길 불확실성이 있다.
- [x] "더 좋아질 수 있음"만으로 retry하지 않는다고 명시했다.
