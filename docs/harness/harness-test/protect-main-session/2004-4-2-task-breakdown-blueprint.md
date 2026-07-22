# 2004-4-2. 내 리프·handoff·review gate를 에이전트 실행 그래프로 잇기

## 이 산출물

4-1에서 만든 AC tree의 분업 가치 있는 리프, 4-2 handoff 양식, 4-3 review gate를 하나로 이은 Agent Execution Graph 한 장. 어떤 리프를 어떤 sub agent가 맡고, 무슨 handoff로 주고받고, 무슨 review로 통과하고, 실패하면 어디로 돌아가는지가 한눈에 보인다. 다음 실습(2004-4-3)에서 이 그래프대로 sub agent를 실제로 돌린다.

## 목적

지금까지 세 가지를 따로 만들었다 — 리프(4-1), handoff 양식(4-2), review gate(4-3). 이 실습은 새로 만드는 게 아니라 셋을 하나로 잇는다. 분업 가치 있다고 본 리프 하나가 sub agent 한 명의 일이 되고, sub agent끼리 handoff로 주고받고, review gate가 통과를 판정하고, 실패하면 어느 리프로 돌아갈지 정한다. "무엇을 할지" 나열이 아니라 실행 순서를 그리는 단계다.

## 권장 시간

20분

## 준비물

- 2004-1-3에서 만든 AC tree (분업 가치 있다고 표시한 리프 포함)
- 2004-2-3에서 만든 handoff 양식 v1
- 2004-3-3에서 만든 review gate

## 입력

먼저 아래를 채운다. 새 내용을 만들지 말고 앞 실습 산출물에서 그대로 옮긴다.

| 항목 | 내용 |
|---|---|
| Parent 한 줄 (AC tree에서) |  |
| 분업 가치 있는 리프 (이름 그대로) |  |
| 리프 사이 순서·의존 |  |
| handoff 양식 위치 |  |
| review gate 위치 |  |

## AI에게 시키기

위 입력을 채워 붙인 뒤, 한 줄을 덧붙인다.

> 이 입력으로 Agent Execution Graph를 그려줘. 내가 4-1에서 분업 가치 있다고 본 리프를 node로 쓰고, node 이름은 intake·plan 같은 일반명 말고 내 리프 이름 그대로 써. 각 node에 담당 sub agent(ask/build/review), handoff로 받는 입력과 돌려주는 출력, review gate guard를 붙여. 리프 순서를 edge로, pass/rework/ask-back/exit를 route로 그려. 표를 채우는 게 아니라 실행 순서가 보이게 써.

## 단계별 진행

1. AC tree에서 분업 가치 있다고 표시한 리프만 골라 node로 옮긴다. 이름은 리프 그대로 쓴다.
2. 각 node에 담당 sub agent를 적는다 (ask / build / review 중 하나).
3. handoff 양식을 node의 입력·출력 계약으로 붙인다 — sub agent가 무엇을 받고 무엇을 돌려주는가.
4. review gate를 node의 guard로 붙인다 — format·evidence·false-negative 중 이 node에서 무엇을 보는가.
5. 리프 순서와 의존을 edge로 잇는다. 보통 ask 먼저, 다음 build, 마지막 review. 서로 독립이면 병렬로 둔다.
6. review gate의 pass·rework·ask-back·exit가 각각 어느 node로 가는지 route로 그린다.

## 작성 템플릿

````markdown
# Agent Execution Graph — <Parent 한 줄>

## 1. Graph Goal
- 시작 입력:
- 최종 산출물:
- 성공 조건: 리프마다 handoff 한 장으로 돌고, review gate가 verdict를 남긴다

## 2. Nodes  (분업 가치 있는 리프만, 이름은 리프 그대로)
| node = 리프 | sub agent | input (handoff) | output (handoff) | guard (review gate) |
|---|---|---|---|---|
|  | ask / build / review |  |  | format / evidence / FN |

## 3. Edges
- 리프 순서를 화살표로:  A -> B -> C
- 병렬이면 같이 적는다

## 4. Routes
| verdict | 어느 node로 | 첫 행동 |
|---|---|---|
| pass | 다음 리프 / 통합 |  |
| rework | 같은 build sub agent |  |
| ask-back | ask sub agent / 사람 |  |
| exit | 메인 세션 보고 |  |
````

## 예시 — meeting-to-1page 분업

````markdown
# Agent Execution Graph — 회의록을 1page 본문으로

## 1. Graph Goal
- 시작 입력: 회의록 원문
- 최종 산출물: 검수까지 통과한 1page 본문
- 성공 조건: 리프마다 handoff 한 장으로 돌고, review gate가 verdict를 남긴다

## 2. Nodes
| node = 리프 | sub agent | input (handoff) | output (handoff) | guard (review gate) |
|---|---|---|---|---|
| 회의록 결정 항목 질문 | ask | 회의록 원문 | 의사결정자 질문 표 | format: 질문·대상 칸이 있나 |
| 본문 채우기 | build | 회의록 확정 + 질문 답 | 1page 본문 + 근거(출처) | evidence: 근거 줄을 대조 |
| 보존 표현 인용 검사 | review | 본문 + 원문 인용 3줄 | 통과 / 위반 줄 | FN 보정: 값이 일치하나 |

## 3. Edges
결정 항목 질문 -> 본문 채우기 -> 보존 표현 인용 검사

## 4. Routes
| verdict | 어느 node로 | 첫 행동 |
|---|---|---|
| pass | 메인 보고 | 1page 본문 확정 |
| rework | 본문 채우기 build | 근거 없는 수치를 빼거나 근거 줄을 단다 |
| ask-back | 결정 항목 질문 ask | 회의록 모호 항목을 의사결정자에게 다시 묻는다 |
| exit | 메인 세션 보고 | 외부 결정 대기 상태로 보존 |
````

## 완료 기준

- node 이름이 일반명이 아니라 내 실제 리프 이름이다.
- 각 node에 담당 sub agent, handoff 입력·출력, review gate guard가 있다.
- 리프 순서가 edge로, pass·rework·ask-back·exit가 route로 보인다.
- 다음 실습에서 이 그래프대로 sub agent를 바로 띄울 수 있다.

## 자기 검수 체크리스트

- [ ] node가 4-1에서 분업 가치 있다고 본 리프와 일치한다.
- [ ] 각 node의 input·output이 4-2 handoff 양식과 같은 칸을 쓴다.
- [ ] 각 node의 guard가 4-3 review gate의 단계(format·evidence·FN)와 연결된다.
- [ ] rework·ask-back·exit가 각각 어느 리프로 가는지 적혀 있다.
- [ ] 같은 AC tree로 두 번 그렸을 때 같은 node·edge가 나온다.

---

# Agent Execution Graph — 회의록 원문을 1page 기획안 초안(7칸)으로 변환

## 0. 입력

- Parent 한 줄: 회의록 원문을 1page 기획안 초안(7칸)으로 변환
- 분업 가치 있는 리프: L1 결정 항목 추출 질문, L5 보존 표현 인용 검사, L6 도메인 제약 위반 검사
- 리프 사이 순서·의존: L1이 먼저 미결정 질문을 정리한다. 메인 세션이 L3 6칸 맵핑과 L4 확인 필요 항목 모음을 inline build로 만든 뒤, L5와 L6을 병렬 review로 돌린다.
- handoff 양식 위치: `practice-materials/chapter-04/2004-2-3-workspace/handoff-L3-build-to-main.md`
- review gate 위치: `practice-materials/chapter-04/2004-3-3-workspace/deterministic-review-gate-L3.md`

## 1. Graph Goal

- 시작 입력: `practice-materials/chapter-04/2004-3-3-workspace/meeting.md` 회의록 원문
- 최종 산출물: L5·L6 review gate를 통과한 1page 기획안 7칸 초안과 리프별 verdict
- 성공 조건: 분업 가치 있는 리프마다 handoff 한 장이 남고, review gate가 pass / rework / ask-back / exit 중 하나의 route를 남긴다
- node 제외 원칙: L3 6칸 맵핑과 L4 확인 필요 항목 한 칸 모음은 4-1에서 분업 가치 낮음으로 판정했으므로 sub agent node로 만들지 않고 메인 세션 inline build 산출물로만 둔다.

## 2. Execution Graph

```text
START
  input: meeting.md 회의록 원문

  -> [L1 결정 항목 추출 질문]
       sub agent: ask
       handoff in:
         - 회의록 원문
         - 비어 있으면 질문할 항목 기준: 결정권자, 일정, 수치 출처, 환불 기준
       handoff out:
         - 의사결정자에게 물을 질문 4개
         - 각 질문의 대상자 한 줄
         - 판정: 통과 / 재질문 / 종료
       guard:
         - format: 질문 4개와 대상자 칸이 모두 있는가
         - evidence: 질문이 meeting.md의 미결정·출처 미확인 줄에서 나왔는가
         - FN: 표현 차이만 있는 질문은 살리고, 없는 외부 질문은 rework

  -> MAIN INLINE BUILD
       not a sub agent node
       action:
         - L1 handoff를 반영한다
         - L3 6칸 맵핑을 만든다
         - L4 확인 필요 항목 한 칸을 만든다
       output:
         - 1page 초안 7칸
         - L2 보존 표현 후보 3줄 또는 원문 인용 후보
         - 각 칸의 근거 파일·줄

  -> PARALLEL REVIEW
       ├─ [L5 보존 표현 인용 검사]
       │    sub agent: review
       │    handoff in:
       │      - 1page 초안 7칸
       │      - 보존 표현 후보 3줄
       │      - meeting.md 원문 줄번호
       │    handoff out:
       │      - 통과 / 위반 verdict
       │      - 위반 인용과 원문 줄번호
       │      - 다음 행동 한 줄
       │    guard:
       │      - format: 보존 표현 3줄과 출처 줄번호가 있는가
       │      - evidence: 큰따옴표 인용이 meeting.md 원문과 직접 대조되는가
       │      - FN: 조사·어미 같은 산문 차이가 아니라 값·문장 누락만 실패로 보는가
       │
       └─ [L6 도메인 제약 위반 검사]
            sub agent: review
            handoff in:
              - 1page 초안 7칸
              - L1 질문·답 상태
              - meeting.md 근거 줄
            handoff out:
              - 통과 / 위반 verdict
              - 외부 사실 단정, 미확인 수치, 범위 밖 항목, 미결정 결론 톤 위반 목록
              - 다음 행동 한 줄
            guard:
              - format: 네 가지 도메인 제약 검사 결과가 모두 있는가
              - evidence: 각 위반 또는 통과 판단이 meeting.md 줄과 연결되는가
              - FN: 표현 차이는 허용하되 CS 문의 60%, 환불 기준, 일정·담당자 같은 값 단정은 실패시키는가

  -> MERGE
       if L5 pass and L6 pass:
         final 1page 초안 확정
       else:
         route by verdict
```

## 3. Edges

```text
START
  -> L1 결정 항목 추출 질문
  -> MAIN INLINE BUILD: L3 6칸 맵핑 + L4 확인 필요 항목 한 칸 모음
  -> L5 보존 표현 인용 검사
  -> MERGE

START
  -> L1 결정 항목 추출 질문
  -> MAIN INLINE BUILD: L3 6칸 맵핑 + L4 확인 필요 항목 한 칸 모음
  -> L6 도메인 제약 위반 검사
  -> MERGE
```

L5와 L6은 같은 1page 초안을 입력으로 받으므로 병렬 실행한다. 둘 중 하나라도 pass가 아니면 MERGE하지 않고 route로 되돌린다.

## 4. Routes

```text
L1 결정 항목 추출 질문
  pass
    -> MAIN INLINE BUILD
       first action: 질문 답 상태를 6칸 본문과 확인 필요 항목에 반영한다
  rework
    -> L1 결정 항목 추출 질문
       first action: 회의록에 근거 없는 질문을 제거하고 미결정 항목 기준 4종으로 다시 묶는다
  ask-back
    -> 사람
       first action: 결정권자, 일정, 수치 출처, 환불 기준 중 막힌 항목만 확인한다
  exit
    -> 메인 세션 보고
       first action: 외부 결정 대기 상태와 막힌 질문을 보존한다

L5 보존 표현 인용 검사
  pass
    -> MERGE 대기
       first action: L6 verdict를 기다린 뒤 둘 다 pass면 최종 초안에 합류한다
  rework
    -> MAIN INLINE BUILD
       first action: 원문과 일치하지 않는 인용만 원문 큰따옴표 표현으로 교체한다
  ask-back
    -> L1 결정 항목 추출 질문
       first action: 보존해야 할 발화가 목표 문장인지 범위 제한 문장인지 다시 묻는다
  exit
    -> 메인 세션 보고
       first action: 원문 인용 후보가 부족하거나 외부 확인 없이는 보존 표현을 고를 수 없다고 남긴다

L6 도메인 제약 위반 검사
  pass
    -> MERGE 대기
       first action: L5 verdict를 기다린 뒤 둘 다 pass면 최종 초안에 합류한다
  rework
    -> MAIN INLINE BUILD
       first action: 외부 사실 단정, 미확인 수치, 범위 밖 항목, 미결정 결론 톤 중 걸린 항목만 수정한다
  ask-back
    -> L1 결정 항목 추출 질문
       first action: 성공 기준의 측정 단위·기간, CS 문의 60% 출처, 환불 기준 명문화 가능 여부 중 막힌 항목만 묻는다
  exit
    -> 메인 세션 보고
       first action: 운영팀 회신 대기 또는 같은 rework 2회 반복 상태로 멈춘다

MERGE
  pass
    -> final
       first action: L5와 L6 verdict를 함께 붙여 1page 초안을 확정한다
  rework
    -> MAIN INLINE BUILD
       first action: 실패한 review node의 첫 행동만 반영해 재제출한다
  ask-back
    -> L1 결정 항목 추출 질문
       first action: 질문으로 풀어야 하는 모호함만 새 handoff로 보낸다
  exit
    -> 메인 세션 보고
       first action: 외부 결정 대기 사유와 현재 초안 상태를 보존한다
```

## 5. 자기 검수

- [x] node가 4-1에서 분업 가치 있다고 본 리프와 일치한다: L1, L5, L6.
- [x] node 이름이 intake, plan 같은 일반명이 아니라 실제 리프 이름이다.
- [x] 각 node의 input·output이 4-2 handoff 양식의 From task / Result 구조로 읽힌다.
- [x] 각 node의 guard가 4-3 review gate의 format·evidence·false-negative 단계와 연결된다.
- [x] rework·ask-back·exit가 각각 어느 리프로 돌아가는지 적혀 있다.
- [x] L3·L4는 4-1 판정에 맞춰 sub agent node가 아니라 inline build 산출물로만 둔다.

## 6. ASCII Tree Graph

```text
START
`- input: meeting.md 회의록 원문
   |
   `- [L1 결정 항목 추출 질문]  sub agent: ask
      |  handoff in
      |  `- 회의록 원문
      |     비어 있으면 질문할 항목 기준:
      |     결정권자 / 일정 / 수치 출처 / 환불 기준
      |
      |  handoff out
      |  `- 질문 4개 + 대상자 한 줄 + 판정
      |
      |  guard
      |  |- format: 질문 4개와 대상자 칸이 모두 있는가
      |  |- evidence: 질문이 meeting.md의 미결정·출처 미확인 줄에서 나왔는가
      |  `- FN: 표현 차이만 있는 질문은 살리고, 없는 외부 질문은 rework
      |
      |- pass
      |  `- MAIN INLINE BUILD
      |     |  not a sub agent node
      |     |  action
      |     |  |- L1 handoff를 반영한다
      |     |  |- L3 6칸 맵핑을 만든다
      |     |  `- L4 확인 필요 항목 한 칸을 만든다
      |     |
      |     |  output
      |     |  `- 1page 초안 7칸 + 보존 표현 후보 + 각 칸의 근거 파일·줄
      |     |
      |     `- PARALLEL REVIEW
      |        |
      |        |- [L5 보존 표현 인용 검사]  sub agent: review
      |        |  |  handoff in
      |        |  |  `- 1page 초안 7칸 + 보존 표현 후보 3줄 + meeting.md 원문 줄번호
      |        |  |
      |        |  |  handoff out
      |        |  |  `- 통과/위반 verdict + 위반 인용과 원문 줄번호 + 다음 행동
      |        |  |
      |        |  |  guard
      |        |  |  |- format: 보존 표현 3줄과 출처 줄번호가 있는가
      |        |  |  |- evidence: 큰따옴표 인용이 meeting.md 원문과 직접 대조되는가
      |        |  |  `- FN: 조사·어미 차이가 아니라 값·문장 누락만 실패로 보는가
      |        |  |
      |        |  |- pass
      |        |  |  `- MERGE 대기
      |        |  |- rework
      |        |  |  `- MAIN INLINE BUILD
      |        |  |     `- 원문과 일치하지 않는 인용만 큰따옴표 표현으로 교체
      |        |  |- ask-back
      |        |  |  `- [L1 결정 항목 추출 질문]
      |        |  |     `- 보존해야 할 발화가 목표 문장인지 범위 제한 문장인지 다시 묻기
      |        |  `- exit
      |        |     `- 메인 세션 보고
      |        |        `- 원문 인용 후보 부족 또는 외부 확인 대기 상태 보존
      |        |
      |        `- [L6 도메인 제약 위반 검사]  sub agent: review
      |           |  handoff in
      |           |  `- 1page 초안 7칸 + L1 질문·답 상태 + meeting.md 근거 줄
      |           |
      |           |  handoff out
      |           |  `- 통과/위반 verdict + 도메인 제약 위반 목록 + 다음 행동
      |           |
      |           |  guard
      |           |  |- format: 네 가지 도메인 제약 검사 결과가 모두 있는가
      |           |  |- evidence: 각 판단이 meeting.md 줄과 연결되는가
      |           |  `- FN: 표현 차이는 허용하되 값 단정은 실패시키는가
      |           |
      |           |- pass
      |           |  `- MERGE 대기
      |           |- rework
      |           |  `- MAIN INLINE BUILD
      |           |     `- 외부 사실 단정 / 미확인 수치 / 범위 밖 / 미결정 결론 톤만 수정
      |           |- ask-back
      |           |  `- [L1 결정 항목 추출 질문]
      |           |     `- 성공 기준·CS 문의 60% 출처·환불 기준 중 막힌 항목만 묻기
      |           `- exit
      |              `- 메인 세션 보고
      |                 `- 운영팀 회신 대기 또는 같은 rework 2회 반복 상태로 중단
      |
      |- rework
      |  `- [L1 결정 항목 추출 질문]
      |     `- 회의록에 근거 없는 질문 제거 후 미결정 항목 기준 4종으로 재정리
      |
      |- ask-back
      |  `- 사람
      |     `- 결정권자 / 일정 / 수치 출처 / 환불 기준 중 막힌 항목만 확인
      |
      `- exit
         `- 메인 세션 보고
            `- 외부 결정 대기 상태와 막힌 질문 보존

MERGE
|- condition: L5 pass + L6 pass
|  `- final: L5와 L6 verdict를 붙여 1page 초안 확정
|
|- condition: L5 또는 L6 rework
|  `- MAIN INLINE BUILD
|     `- 실패한 review node의 첫 행동만 반영해 재제출
|
|- condition: L5 또는 L6 ask-back
|  `- [L1 결정 항목 추출 질문]
|     `- 질문으로 풀어야 하는 모호함만 새 handoff로 보냄
|
`- condition: L5 또는 L6 exit
   `- 메인 세션 보고
      `- 외부 결정 대기 사유와 현재 초안 상태 보존
```

