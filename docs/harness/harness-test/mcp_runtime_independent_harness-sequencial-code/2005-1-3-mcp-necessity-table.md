# MCP 전환 후보 + 스킬 분류 — meeting-to-1page 분업형 하네스

## 1. 분업형 하네스 v1 (4-4)

```text
meeting.md
  -> L1 ask: 결정 항목 추출 질문
  -> MAIN INLINE BUILD: L3 6칸 맵핑 + L4 확인 필요 항목
  -> L5 review: 보존 표현 인용 검사
  -> L6 review: 도메인 제약 위반 검사
  -> MERGE
  -> final 1page draft
```

이 하네스의 좋은 점은 메인 세션이 상태를 소유하고, 보조 세션은 리프 하나의 handoff만 돌려준다는 점이다. 그래서 작업 경계는 이미 분리되어 있다. 다만 v1은 아직 "순서대로 실행했다"는 기록과 "실패했을 때 어디로 돌아가야 하는가"가 문서 규칙에 많이 의존한다. Chapter 5에서 MCP로 올릴 지점은 모든 판단이 아니라, 이 문서 규칙이 실행 중에 흔들리면 전체 산출물이 틀어지는 부분이다.

- 메인이 들고 있는 것: 입력 원문, graph, handoff contract, review gate 순서, leaf별 verdict, route, next action, partial pass 상태.
- 보조에 넘기는 것: leaf brief, 해당 leaf가 볼 수 있는 근거, 금지 범위, 완료조건.
- 보조가 돌려주는 것: handoff markdown 한 장, 판정, 남은 질문, 다음 세션의 첫 행동.

## 2. 단계별 Need Signals

| 단계 | sequence | state | retry | orchestration | MCP 후보? |
|---|---|---|---|---|---|
| intake / graph load | 시작 입력, graph, handoff contract, review gate가 먼저 고정되어야 한다. | 입력 경로와 graph 버전이 사라지면 이후 근거 줄 검사가 무의미해진다. | 입력 누락이면 재작업보다 중단이 맞다. | 파일 존재 확인과 세션 시작 context 생성이 필요하다. | 부분 yes |
| L1 ask | build보다 반드시 먼저 실행되어야 한다. 미결정 질문이 없으면 build가 확인 필요 항목을 만들 수 없다. | 질문 4종, 대상자/팀, 근거 줄, 미회신 상태가 보존되어야 한다. | format/evidence/FN 실패 시 같은 L1로 좁혀 재작업한다. 외부 회신 필요는 ask-back이다. | ask agent 실행, handoff 저장, review gate 호출, route 기록이 한 묶음이다. | yes |
| MAIN INLINE BUILD | L1의 질문표와 meeting.md 이후에만 실행된다. | 7칸 초안, 보존 표현 후보, 각 칸의 근거 줄, 확인 필요 항목이 다음 리뷰의 입력이다. | L5/L6 실패가 발생하면 전체 재생성이 아니라 실패 field만 수정해야 한다. | 현재는 메인 수작업이지만 L5/L6 입력 패키징까지 연결된다. | 부분 yes |
| L5 review | build 이후에만 가능하고 L6와 병렬 실행 가능하다. | 인용 후보 3줄, 원문 줄번호, 위반 인용, verdict가 남아야 한다. | 인용 불일치는 build로 되돌아가 failed quote만 수정한다. | review agent 호출과 deterministic gate가 연결된다. | yes |
| L6 review | build 이후에만 가능하고 L5와 병렬 실행 가능하다. | 외부 사실 단정, 미확인 수치, 범위 밖 항목, 미결정 결론 톤의 검사 결과가 남아야 한다. | 값 단정·범위 오염은 build로, 외부 결정 부족은 L1/사람에게 되돌린다. | review agent 호출, gate, route 판단이 필요하다. | yes |
| MERGE | L5와 L6 verdict가 모두 도착한 뒤에만 실행된다. | partial pass, failed leaf, failed field, 마지막 valid draft가 보존되어야 한다. | 한쪽만 실패하면 pass한 review 결과를 재사용하고 실패 leaf만 재검사해야 한다. | 병렬 결과 수집, route 결정, checkpoint append가 핵심이다. | yes |
| final output | merge pass 이후에만 생성된다. | 최종 1page, 통과한 gate 목록, 후속 확인 항목이 남아야 한다. | final 자체의 실패보다 앞선 leaf로 route된다. | 문서 작성은 메인이 해도 되지만 pass 조건 검증은 잠글 수 있다. | 부분 yes |

## 3. MCP로 잠글 업그레이드 지점

### A. leaf 실행 트랜잭션

현재 v1은 "brief out, handoff in, gate verdict, route"라는 불변식을 문서로 정의한다. 이 불변식은 MCP 함수 하나의 트랜잭션으로 잠그는 것이 좋다.

```text
run_leaf(leaf_id, brief, evidence_files)
  -> spawn/open worker
  -> collect one handoff
  -> save handoff
  -> run review_gate
  -> append checkpoint
  -> return route
```

이 지점은 sequence, state, retry, orchestration 네 신호가 모두 있다. 특히 보조 세션이 handoff 한 장이 아니라 설명문을 돌려주거나, gate를 건너뛰고 다음 leaf로 넘어가면 하네스의 의미가 깨진다.

### B. route_verdict + retry key

v1의 exit condition은 "같은 rework 사유 2회 반복"이다. 하지만 현재 상태 파일에는 반복 판단을 위한 key가 충분히 구조화되어 있지 않다. MCP 안에서는 다음 key를 강제하는 편이 낫다.

```text
retry_key = leaf_id + failed_guard + failed_field
```

예를 들어 L6에서 `FN(value): 60% uncertainty marker missing`이 두 번 반복되면 더 넓은 재작성으로 보내지 않고 exit 또는 ask-back으로 바꿔야 한다. 이건 사람이 매번 기억해서 판단하기보다 코드가 카운트해야 한다.

### C. 병렬 review merge

L5와 L6는 build 이후 병렬 실행 가능하지만 merge는 둘 다 도착한 뒤에만 가능하다. 또한 한쪽만 pass한 경우 pass 결과를 버리지 말아야 한다. 따라서 `merge_review`는 MCP 후보 중 가장 실질적인 업그레이드 지점이다.

```text
merge_review(draft_id, l5_verdict, l6_verdict)
  -> both pass: final
  -> L5 fail only: keep L6 pass, route to build quote fix
  -> L6 fail only: keep L5 pass, route to build constraint fix or ask-back
  -> both fail: route by higher-risk failure
```

이 단계는 sequence와 state가 특히 강하다. 병렬을 허용하되 merge 순서는 잠가야 한다.

### D. checkpoint_state

`state/harness-state.md`와 `state/run-log.md`는 이미 좋은 출발점이다. 다만 MCP가 실행 상태를 갖는다면 markdown 로그와 별개로 구조화된 상태가 필요하다.

```text
checkpoint_state
  - input_id
  - draft_id
  - leaf_id
  - handoff_path
  - verdict
  - route
  - failed_guard
  - failed_field
  - retry_count
  - next_action
```

문서는 사람이 읽는 감사 로그로 남기고, route 계산과 retry 판단은 구조화 상태로 잠그는 편이 맞다.

### E. deterministic review precheck

이미 `.codex/hooks/review_gate.py`가 format, evidence, FN 일부를 코드로 검사한다. 이 부분은 MCP로 올려도 된다. 다만 모든 의미 판단을 코드화하지는 않는다. 코드가 잠글 것은 "필수 섹션, verdict 존재, meeting.md 줄번호 범위, 60% 불확실성 표식, 범위 밖 키워드"처럼 반복 가능하고 결정적인 규칙이다. 산문 표현이 원문과 의미상 같은지 보는 판단은 review leaf나 사람 검토에 남긴다.

## 4. 사람/문서에 남길 단계

### A. 업무 의미와 최종 책임 판단

미확인 수치의 출처, 환불 기준 명문화 가능 여부, 최종 담당자·일정은 MCP가 결론 낼 수 없다. MCP는 ask-back 항목을 보존하고 route할 수 있지만, 답을 만들어서는 안 된다. 이 판단은 사람 또는 업무 문서에 남긴다.

### B. 1page 문안의 제품적 톤 조정

L3/L4 build는 부분 자동화할 수 있지만, 최종 1page의 표현 밀도와 이해관계자에게 보여줄 톤은 메인 세션 또는 사람이 책임지는 편이 낫다. MCP가 할 일은 근거 없는 값 단정과 범위 확장을 막는 것이지, 모든 문장을 고정 템플릿으로 만드는 것이 아니다.

### C. false-negative 보정의 의미 판단

"조사/어미 차이는 허용하고 값/문장 누락만 실패" 같은 규칙은 코드 precheck만으로 충분하지 않다. 원문 의미를 보존했는지, 산문 변환이 허용 범위인지, 확인 필요 항목으로 남긴 표현이 실제로 안전한지는 review agent 또는 사람이 판단해야 한다.

### D. exit 보고 문구

exit 조건 자체는 MCP가 감지할 수 있다. 하지만 외부 결정 대기 사유를 어떤 문장으로 보고하고 누구에게 넘길지는 문서와 사람이 맡는 게 맞다. 이 부분은 자동화하면 오히려 책임 경계가 흐려진다.

## 5. SKILL.md 후보 분류

### 1. `leaf-handoff-runner`

- 묶이는 단계: brief 작성 -> sub agent 실행 -> handoff 회수 -> handoff 저장.
- 역할: 리프 하나를 disposable agent에게 맡기고 handoff 한 장만 회수하는 표준 절차.
- 반복 이유: L1, L5, L6가 모두 같은 handoff 골격을 쓴다.
- MCP와의 관계: 실제 spawn/wait/save는 MCP 후보이고, brief 작성 규칙과 agent별 역할 설명은 SKILL.md에 잘 맞는다.

### 2. `deterministic-review-gate`

- 묶이는 단계: format -> evidence -> FN correction 순서로 handoff를 검사.
- 역할: handoff가 다음 단계로 넘어갈 최소 조건을 검사한다.
- 반복 이유: 모든 leaf handoff가 같은 gate 순서를 탄다.
- MCP와의 관계: 필수 섹션, verdict, 줄번호 범위 같은 결정 규칙은 MCP/코드로 잠그고, "표현 차이 허용" 같은 의미 판단 가이드는 SKILL.md에 둔다.

### 3. `route-and-retry`

- 묶이는 단계: verdict 해석 -> route 결정 -> retry key 계산 -> next action 작성.
- 역할: pass/rework/ask-back/exit를 일관되게 해석하고 같은 실패 2회 반복을 감지한다.
- 반복 이유: L1, L5, L6, MERGE 모두 route 결정을 한다.
- MCP와의 관계: retry count와 route 전환은 MCP로 잠글 가치가 높고, route별 사용자 보고 문구는 SKILL.md 또는 문서 템플릿에 둔다.

### 4. `meeting-to-1page-build`

- 묶이는 단계: L1 질문표 반영 -> 6칸 맵핑 -> 확인 필요 항목 보존 -> 7칸 초안 생성.
- 역할: 회의록을 1page 기획안 초안으로 바꾸되, 미결정 항목을 결론으로 바꾸지 않는다.
- 반복 이유: 회의록 기반 1page 작성 업무마다 재사용 가능하다.
- MCP와의 관계: 문안 생성 자체는 SKILL.md가 적합하고, build 입력 패키징과 review 대상 산출물 ID 발급은 MCP가 적합하다.

### 5. `parallel-review-merge`

- 묶이는 단계: L5/L6 병렬 리뷰 입력 준비 -> 결과 수집 -> partial pass 보존 -> merge route.
- 역할: 두 리뷰가 모두 통과했을 때만 final로 보내고, 실패한 리뷰만 되돌린다.
- 반복 이유: 보존 표현 검사와 도메인 제약 검사는 다른 관점이지만 같은 draft를 공유한다.
- MCP와의 관계: 병렬 결과 대기, partial pass 저장, 실패 leaf만 재실행은 MCP로 잠그는 것이 좋다. 리뷰 관점 설명은 SKILL.md에 둔다.

## 6. 업그레이드 우선순위

1. `checkpoint_state`를 먼저 만든다. 상태가 구조화되지 않으면 retry와 merge를 안정적으로 만들 수 없다.
2. `route_verdict`를 만든다. pass/rework/ask-back/exit 해석과 같은 실패 2회 반복을 코드로 고정한다.
3. `merge_review`를 만든다. L5/L6 병렬 리뷰의 partial pass를 보존해야 재작업 비용이 줄어든다.
4. `run_leaf`를 만든다. agent 실행 도구가 바뀌어도 leaf handoff 계약은 유지되게 한다.
5. `review_gate`를 MCP 내부 precheck로 옮긴다. 단, 의미 판단 전체를 코드화하지 않고 결정 규칙만 잠근다.

## 7. 5-2로 넘길 대상

- MCP로 잠글 흐름 한 줄:

```text
run_leaf -> review_gate -> route_verdict -> checkpoint_state -> merge_review
```

- 묶은 SKILL.md 후보:

```text
leaf-handoff-runner
deterministic-review-gate
route-and-retry
meeting-to-1page-build
parallel-review-merge
```

- 5-2에서 런타임 독립 그래프로 만들 대상:

```text
meeting-to-1page runtime graph:
START
  -> load_inputs
  -> run_leaf(L1)
  -> build_draft
  -> parallel(run_leaf(L5), run_leaf(L6))
  -> merge_review
  -> final_or_route_back
```

5-2의 초점은 "어떤 agent 도구를 쓰는가"가 아니라 "leaf handoff, gate verdict, route, checkpoint가 어떤 런타임에서도 같은 순서와 상태로 남는가"가 되어야 한다.
