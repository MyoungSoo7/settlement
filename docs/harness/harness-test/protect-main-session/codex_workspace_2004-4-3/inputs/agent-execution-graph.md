# Agent Execution Graph — 회의록 원문을 1page 기획안 초안(7칸)으로 변환

> 입력 체인
> - 4-1 AC tree: `2004-1-3-workspace/ac-tree-meeting-to-1page.md` (분업 가치 있다고 본 리프)
> - 4-2 handoff 양식 v1: `2004-2-3-workspace/handoff-L3-build-to-main.md`
> - 4-3 review gate: `2004-3-3-workspace/deterministic-review-gate-L3.md`
> 근거 대조 원문: `2004-3-3-workspace/meeting.md`

## 0. 입력표 (앞 실습 산출물에서 옮김)

| 항목 | 내용 |
|---|---|
| Parent 한 줄 | 회의록 원문을 1page 기획안 초안(7칸)으로 변환 |
| 분업 가치 있는 리프 (이름 그대로) | L1 결정 항목 추출 질문 · L3+L4 6칸 맵핑+확인 필요 항목 · L5 보존 표현 인용 검사 · L6 도메인 제약 위반 검사 |
| 리프 사이 순서·의존 | L1(ask) → L3+L4(build) → L5(review) → L6(review). build는 L1 답 없이 시작하지 않고, review는 build 산출 없이 시작하지 않는다 |
| handoff 양식 위치 | `2004-2-3-workspace/handoff-L3-build-to-main.md` §1 빈 골격 (ask/build/review 공용) |
| review gate 위치 | `2004-3-3-workspace/deterministic-review-gate-L3.md` (format → evidence → false-negative → route) |

> build 노드 결정: 4-1 §5는 L3·L4 build를 "분업 가치 낮음"으로 적었으나, 본 그래프에서는 4-2 handoff·4-3 gate가 L3 build를 축으로 만들어진 연속성을 살려 build를 별도 sub agent node로 둔다. 메인 세션이 직접 도는 1-shot으로 돌릴 경우 L3+L4 노드만 메인 단계로 옮기면 나머지 edge·route는 그대로 성립한다.

---

## 1. Graph Goal

- **시작 입력**: 회의록 원문 (`meeting.md`)
- **최종 산출물**: L5·L6 review를 통과한 1page 7칸 초안 (6칸 본문 + 확인 필요 항목 1칸)
- **성공 조건**: 리프마다 handoff 한 장으로 돌고, review gate가 매번 같은 verdict를 남긴다 (같은 입력 → 같은 route, 4장 척추)

---

## 2. 실행 순서 (이 그래프의 본체)

메인 세션이 위에서 아래로 sub agent를 띄우고, review gate의 verdict가 다음 화살표를 정한다.

```text
                       ┌──────────────────────────────────────────────┐
            시작 입력 →│  meeting.md (회의록 원문)                       │
                       └───────────────────┬──────────────────────────┘
                                           │ dispatch
                                           ▼
   ┌───────────────────────────────────────────────────────────────────┐
   │ NODE  L1  결정 항목 추출 질문            sub agent: ask              │
   │ in  ← 회의록 원문                                                    │
   │ out → 의사결정자 질문 표 (질문 4개 + 대상)                            │
   │ guard: format — 질문·대상 칸이 다 있나                               │
   └───────────────────┬──────────────────────────────┬────────────────┘
            pass        │                              │  ask-back
   (질문 표 확정)        ▼                              ▼ (회의록만으론 결정 항목이 안 잡힘)
   ┌───────────────────────────────────────────┐   사람(의사결정자)에게 회신 요청
   │ NODE  L3+L4  6칸 맵핑 + 확인 필요 항목       │
   │       sub agent: build                     │◀──────────── rework ──────────┐
   │ in  ← L1 질문 표(답) + 회의록 확정 묶음       │                               │
   │       + 보존 인용 3줄                       │                               │
   │ out → 4-2 handoff 한 장:                    │                               │
   │       6칸 표 + 근거(출처) 줄 + 판정          │                               │
   │ guard: format — handoff 구조·6칸·근거 줄    │                               │
   └───────────────────┬───────────────────────┘                               │
                       │ handoff 제출                                           │
                       ▼                                                        │
   ┌───────────────────────────────────────────┐                               │
   │ NODE  L5  보존 표현 인용 검사  sub agent: review                            │
   │ in  ← L3+L4 handoff + L2 보존 인용 3줄       │                               │
   │ out → 통과 / 위반 줄번호                     │                               │
   │ guard: evidence — 주장↔meeting.md 줄 대조    │                               │
   └──────┬──────────────────┬─────────────┬────┘                               │
   pass   │          ask-back │      rework │ (인용 불일치 줄 있음) ──────────────┘
   (인용   ▼                  ▼             
   일치)  ┌──────────────────────────────────┐   사람/ask로 회신 요청
          │ NODE  L6  도메인 제약 위반 검사     │
          │       sub agent: review           │◀──────────── rework ───────────┐
          │ in  ← L3+L4 handoff                │                               │
          │ out → 통과 / 위반 항목 줄          │                               │
          │ guard: false-negative —           │                               │
          │   값 단정·범위 오염 vs 산문 차이    │                               │
          └──┬───────────┬──────────┬─────────┘                               │
       pass  │   ask-back │     rework │ (값 단정·범위 오염) ───────────────────┘
             ▼            ▼            
   ┌──────────────────┐  사람/ask로
   │ main 세션 통합     │  회신 요청
   │ 7칸 1page 확정/보고│
   └──────────────────┘

   exit (어느 review 노드든): 외부 결정자 답이 verdict를 막거나
        같은 rework 사유 2회 반복 → main 세션 보고, 대기 상태로 보존
```

---

## 3. Nodes (분업 가치 있는 리프만, 이름은 리프 그대로)

| node = 리프 | sub agent | input (handoff로 들어오는 것) | output (handoff로 돌려주는 것) | guard (4-3 gate 단계) |
|---|---|---|---|---|
| **L1 결정 항목 추출 질문** | ask | 회의록 원문 (`meeting.md`) | 의사결정자 질문 표 — 결정권자·일정·수치 출처·환불 기준 4종 질문 + 대상 한 줄씩 (답 작성·결론 단정 금지) | **format**: 질문·대상 칸이 다 있나 |
| **L3+L4 6칸 맵핑 + 확인 필요 항목** | build | L1 질문 표(회신 답) + 회의록 확정 묶음 + L2 보존 인용 3줄 | 4-2 handoff 한 장 — 6칸 표(제목·배경·문제·사용자·해결 방향·성공 기준) + 확인 필요 항목 1칸 + 근거(출처) 줄 + 판정 | **format**: handoff 구조·6칸·근거 줄을 갖췄나 (gate stage 1) |
| **L5 보존 표현 인용 검사** | review | L3+L4 handoff + L2 보존 인용 3줄 | 통과 / 위반 줄번호 한 줄씩 (본문 재작성 금지) | **evidence**: 주장을 `meeting.md` 줄과 직접 대조 (gate stage 2) |
| **L6 도메인 제약 위반 검사** | review | L3+L4 handoff | 통과 / 위반 항목 한 줄씩 (외부 사실 단정·미확인 수치·범위 밖·미결정 결론 톤 4항목) | **false-negative**: 값 단정·범위 오염은 잡고 산문 표현 차이는 통과 (gate stage 3) |

---

## 4. Edges (리프 순서·의존)

```text
L1 결정 항목 추출 질문  ──▶  L3+L4 6칸 맵핑+확인 필요 항목  ──▶  L5 보존 표현 인용 검사  ──▶  L6 도메인 제약 위반 검사  ──▶  main 통합
```

- 직렬이다. build는 L1 답이 없으면 시작하지 않고, review는 build handoff가 없으면 시작하지 않는다.
- L5·L6은 둘 다 review지만 가드가 다르다 — L5는 보존 인용(evidence), L6은 도메인 제약(false-negative). 같은 build 산출을 서로 다른 잣대로 보므로 L5 통과 뒤 L6으로 직렬 연결한다 (한 review 세션이 두 가드를 겸하면 자기 결과를 자기가 통과시키는 관성이 생긴다 — 4-1 §5 분리 근거).

---

## 5. Routes (review gate verdict → 다음 node)

verdict는 L5·L6 review 노드가 낸다. L1 ask도 회의록만으론 결정 항목이 안 잡히면 ask-back/exit를 낸다.

| verdict | 어느 node로 | 첫 행동 |
|---|---|---|
| **pass** | L5 pass → L6 / L6 pass → main 통합 | L6까지 통과하면 6칸 + 확인 필요 항목을 7칸 1page로 통합해 확정·보고 |
| **rework** | 같은 build node (L3+L4) | 누락 칸·잘못된 수치·없는 근거 줄·범위 오염 중 하나를 한 줄로 고쳐 재제출 (예: `CS 문의 60%`를 본문에서 빼거나 출처를 단다) |
| **ask-back** | L1 ask sub agent / 사람(의사결정자) | 성공 기준 `신청 직후 환불·일정 문의를 줄인다`가 목표 문장인지 측정 지표인지 확인, 지표면 측정 단위·기간을 정한다 |
| **exit** | 메인 세션 보고 | 운영팀 회신·수치 출처 확인처럼 외부 답이 verdict를 막거나 같은 rework가 2회 반복되면 대기 상태로 보존 |

---

## 6. Dry-run — 실행 한 바퀴 추적 (4-3 실제 verdict 재사용)

`handoff-L3-result.md`를 이 그래프에 그대로 흘려보낸 결과다. 다음 실습(2004-4-3)에서 sub agent를 띄울 때 같은 경로가 나와야 한다.

```text
1. main → L1 ask 띄움
   결과: 결정권자·일정·수치 출처·환불 기준 4종 질문 표.  guard format 통과.
   route: pass → build

2. main → L3+L4 build 띄움 (L1 답 + 회의록 확정 + 보존 인용 3줄)
   결과: 6칸 표 + 확인 필요 항목 + 근거 줄 (= handoff-L3-result.md). 6칸 채움, 근거 줄 있음.  guard format 통과.
   route: handoff 제출 → L5

3. main → L5 review 띄움 (evidence)
   결과: 배경 ←:5 / 문제 ←:12 / 해결 방향 ←:22,:23 / 범위 밖 ←:15,:27 / 성공 기준 ←:10 모두 대조 일치.
   route: pass → L6

4. main → L6 review 띄움 (false-negative)
   결과: `CS 문의 60%` 본문 단정 없음, 합의 범위 두 갈래 유지 → 값·범위 통과.
         단, 성공 기준이 목표 문장인지 측정 지표인지 미정 → 다음 단계 판단을 막는 모호함.
   verdict: ask-back
   route: L1 ask / 사람(의사결정자)
   first action: "신청 직후 환불·일정 문의를 줄인다"를 목표로만 둘지 지표로 쓸지 확인,
                 지표면 측정 단위·기간을 정한다.
```

같은 handoff를 한 번 더 넣어도 같은 규칙(L6 false-negative)에 걸려 **ask-back → L1/사람**으로 떨어진다 (4-3 §8 Determinism Replay와 동일).

---

## 7. 자기 검수 체크리스트

- [x] node 이름이 일반명(intake·plan)이 아니라 4-1 리프 이름 그대로다 (L1·L3+L4·L5·L6).
- [x] node가 4-1에서 분업 가치 있다고 본 리프와 일치한다 — L1·L5·L6은 §5 "분업 가치 있음", L3+L4 build는 본 그래프에서 node로 승격(§0 결정 메모).
- [x] 각 node에 담당 sub agent(ask/build/review), input·output(handoff), guard(review gate 단계)가 있다.
- [x] 각 node의 input·output이 4-2 handoff 양식과 같은 칸(From task / Result / 근거 줄)을 쓴다.
- [x] 각 node의 guard가 4-3 review gate 단계(format·evidence·false-negative)와 연결된다.
- [x] 리프 순서가 edge로(L1→L3+L4→L5→L6), pass·rework·ask-back·exit가 route로 보인다.
- [x] rework는 build node(L3+L4)로, ask-back은 L1/사람으로, exit은 main 보고로 각각 어디로 가는지 적혀 있다.
- [x] 같은 AC tree로 두 번 그렸을 때 같은 node·edge가 나온다 (직렬 ask→build→review→review, 재현 가능).
- [x] 다음 실습(2004-4-3)에서 이 그래프대로 sub agent를 바로 띄울 수 있다 — §6 dry-run이 실행 경로를 보여준다.
